package com.hospitality.payment.adapter.out.psp;

import com.hospitality.payment.domain.Money;
import com.hospitality.payment.port.PspPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simulateur de PSP : permet de faire tourner la plateforme de bout en bout sans
 * compte prestataire, et surtout de <b>provoquer a volonte</b> les scenarios qu'on ne
 * peut pas declencher chez un vrai PSP -- refus, timeout, panne.
 *
 * <p>Les scenarios sont pilotes par un prefixe de token, dans l'esprit des numeros de
 * test publies par les PSP (4000 0000 0000 0002 = refus chez la plupart) :</p>
 * <ul>
 *   <li>{@code tok_decline_*} -> refus de l'emetteur</li>
 *   <li>{@code tok_insufficient_*} -> fonds insuffisants (soft decline, rejouable plus tard)</li>
 *   <li>{@code tok_error_*} -> panne technique, alimente le circuit breaker</li>
 *   <li>{@code tok_3ds_*} -> challenge 3DS requis</li>
 *   <li>tout le reste -> autorise</li>
 * </ul>
 *
 * <p><b>Les annotations de resilience sont le vrai sujet ici.</b> {@code @CircuitBreaker}
 * ouvre le circuit quand le PSP accumule les erreurs : on cesse de l'appeler pendant
 * une fenetre, ce qui evite de saturer nos propres threads en attente de timeouts et
 * de transformer une panne PSP en panne generale. {@code @Retry} ne couvre que les
 * erreurs techniques -- retenter un refus serait inutile et facture par les schemes.</p>
 */
@Component
public class SimulatedPspAdapter implements PspPort {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPspAdapter.class);

    private final int latencyMillis;

    public SimulatedPspAdapter(@Value("${psp.simulated.latency-ms:40}") int latencyMillis) {
        this.latencyMillis = latencyMillis;
    }

    @Override
    @CircuitBreaker(name = "psp", fallbackMethod = "authorizeFallback")
    @Retry(name = "psp")
    public AuthorizationResult authorize(AuthorizationRequest request, String idempotencyKey) {
        simulateNetwork();
        String token = request.paymentMethodToken();

        // Une MIT (no-show) sans chainage de la CIT initiale : l'emetteur a le droit de
        // refuser, et la responsabilite du chargeback retombe sur le commercant.
        if (!request.customerPresent() && request.initialNetworkTransactionId() == null) {
            log.warn("MIT sans networkTransactionId initial pour la reservation {} : refus attendu",
                    request.reservationReference());
            return AuthorizationResult.declined("1A", "Authentication required for MIT without initial CIT");
        }

        if (token.startsWith("tok_decline")) {
            return AuthorizationResult.declined("05", "Do not honor");
        }
        if (token.startsWith("tok_insufficient")) {
            return AuthorizationResult.declined("51", "Insufficient funds");
        }
        if (token.startsWith("tok_error")) {
            throw new PspTechnicalException("Le PSP a renvoye HTTP 503");
        }
        if (token.startsWith("tok_3ds")) {
            return new AuthorizationResult(AuthorizationResult.Outcome.CHALLENGE_REQUIRED,
                    null, null, "CHALLENGE", "1A", "3DS authentication required");
        }

        String threeDs = request.customerPresent() ? "FRICTIONLESS" : "MIT_EXEMPT";
        return AuthorizationResult.authorized(newPspReference(), newNetworkTransactionId(), threeDs);
    }

    /**
     * Appele par Resilience4j quand le circuit est ouvert ou que les retries sont epuises.
     *
     * <p>On renvoie une erreur <b>technique</b>, jamais un refus : la nuance compte. Un
     * refus cloturerait le paiement alors que le PSP a peut-etre autorise sans qu'on
     * ait recu la reponse. En ERROR, le paiement reste rejouable et le reconciliateur
     * tranchera en interrogeant le PSP.</p>
     */
    @SuppressWarnings("unused")
    private AuthorizationResult authorizeFallback(AuthorizationRequest request, String idempotencyKey,
                                                  Throwable cause) {
        log.error("Autorisation indisponible pour la reservation {} : {}",
                request.reservationReference(), cause.getMessage());
        return AuthorizationResult.error("psp_unavailable", cause.getMessage());
    }

    @Override
    @CircuitBreaker(name = "psp", fallbackMethod = "operationFallback")
    @Retry(name = "psp")
    public OperationResult capture(String pspReference, Money amount, String idempotencyKey) {
        simulateNetwork();
        return OperationResult.ok(pspReference);
    }

    @Override
    @CircuitBreaker(name = "psp", fallbackMethod = "operationFallback")
    @Retry(name = "psp")
    public OperationResult refund(String pspReference, Money amount, String idempotencyKey) {
        simulateNetwork();
        return OperationResult.ok(pspReference);
    }

    @Override
    @CircuitBreaker(name = "psp", fallbackMethod = "cancelFallback")
    @Retry(name = "psp")
    public OperationResult cancel(String pspReference, String idempotencyKey) {
        simulateNetwork();
        return OperationResult.ok(pspReference);
    }

    @SuppressWarnings("unused")
    private OperationResult operationFallback(String pspReference, Money amount, String idempotencyKey,
                                              Throwable cause) {
        log.error("Operation PSP indisponible pour {} : {}", pspReference, cause.getMessage());
        return OperationResult.failed("psp_unavailable", cause.getMessage());
    }

    @SuppressWarnings("unused")
    private OperationResult cancelFallback(String pspReference, String idempotencyKey, Throwable cause) {
        log.error("Annulation PSP indisponible pour {} : {}", pspReference, cause.getMessage());
        return OperationResult.failed("psp_unavailable", cause.getMessage());
    }

    private void simulateNetwork() {
        if (latencyMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(latencyMillis / 2 + 1, latencyMillis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PspTechnicalException("Appel PSP interrompu");
        }
    }

    private String newPspReference() {
        return "PSP" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private String newNetworkTransactionId() {
        return "NTI" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /** Panne du prestataire : rejouable, et comptabilisee par le circuit breaker. */
    public static class PspTechnicalException extends RuntimeException {
        public PspTechnicalException(String message) {
            super(message);
        }
    }
}
