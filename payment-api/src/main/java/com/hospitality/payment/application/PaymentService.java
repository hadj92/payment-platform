package com.hospitality.payment.application;

import com.hospitality.payment.domain.IdempotencyRecord;
import com.hospitality.payment.domain.Payment;
import com.hospitality.payment.domain.PaymentException;
import com.hospitality.payment.domain.PaymentStatus;
import com.hospitality.payment.port.PspPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Orchestration des operations de paiement.
 *
 * <p>Cette classe n'est <b>pas</b> annotee {@code @Transactional}, et c'est le point
 * central de sa conception : elle ouvre une transaction courte pour persister
 * l'intention, la referme, appelle le PSP hors transaction, puis ouvre une seconde
 * transaction pour enregistrer le resultat. Les transactions vivent dans
 * {@link PaymentTransactions}.</p>
 *
 * <p>Sequence d'une autorisation :</p>
 * <ol>
 *   <li>Rejeu idempotent ? -> on renvoie la reponse memorisee, on ne retouche a rien</li>
 *   <li>TX courte : paiement PENDING + reservation de la cle d'idempotence</li>
 *   <li><b>Hors transaction</b> : appel au PSP (circuit breaker, retry, timeout)</li>
 *   <li>TX courte : etat final + evenement d'outbox, atomiquement</li>
 * </ol>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentTransactions transactions;
    private final IdempotencyService idempotency;
    private final PspPort psp;
    private final int holdValidityDays;

    private final Counter authorizedCounter;
    private final Counter declinedCounter;
    private final Counter failedCounter;
    private final Counter replayCounter;
    private final Timer authorizationTimer;

    public PaymentService(PaymentTransactions transactions,
                          IdempotencyService idempotency,
                          PspPort psp,
                          MeterRegistry meterRegistry,
                          @Value("${payment.authorization.hold-validity-days:7}") int holdValidityDays) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.psp = psp;
        this.holdValidityDays = holdValidityDays;

        // Les metriques qui comptent sur une plateforme de paiement sont metier, pas
        // techniques : une chute du taux d'autorisation est un incident revenu, meme
        // quand le CPU et la latence sont parfaitement normaux.
        this.authorizedCounter = Counter.builder("payment.authorization")
                .tag("outcome", "authorized").register(meterRegistry);
        this.declinedCounter = Counter.builder("payment.authorization")
                .tag("outcome", "declined").register(meterRegistry);
        this.failedCounter = Counter.builder("payment.authorization")
                .tag("outcome", "failed").register(meterRegistry);
        this.replayCounter = Counter.builder("payment.idempotency.replay").register(meterRegistry);
        this.authorizationTimer = Timer.builder("payment.authorization.duration")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Autorise un paiement : fait reserver les fonds chez l'emetteur, sans debiter.
     *
     * @param idempotencyKey en-tete {@code Idempotency-Key}, obligatoire sur toute
     *                       operation mutante
     * @return le resultat, qui peut etre un rejeu d'une requete anterieure
     */
    public Result authorize(PaymentCommands.Authorize command, String idempotencyKey, String requestBody) {
        String requestHash = idempotency.hash(requestBody);

        Optional<IdempotencyRecord> replay = idempotency.findReplay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            replayCounter.increment();
            return replayOf(replay.get());
        }

        Payment pending = transactions.persistIntent(
                Payment.initiate(command.reservationId(), command.hotelId(), command.amount(), command.card()));

        if (!idempotency.tryReserve(
                IdempotencyRecord.reserve(idempotencyKey, requestHash, pending.id()))) {
            // Course perdue : une requete concurrente portant la meme cle a reserve la
            // premiere. On s'arrete la plutot que d'autoriser une seconde fois.
            throw new PaymentException.RequestInProgress(idempotencyKey);
        }

        Timer.Sample sample = Timer.start();
        try {
            PspPort.AuthorizationResult result = psp.authorize(
                    new PspPort.AuthorizationRequest(
                            command.card().token(),
                            command.amount(),
                            command.reservationId(),
                            command.hotelId(),
                            command.customerPresent(),
                            command.initialNetworkTransactionId()),
                    idempotencyKey);

            return switch (result.outcome()) {
                case AUTHORIZED -> {
                    authorizedCounter.increment();
                    log.info("Paiement {} autorise, pspReference={}", pending.id(), result.pspReference());
                    Payment authorized = transactions.applyAuthorized(pending.id(), result.pspReference(),
                            result.networkTransactionId(), result.threeDsOutcome(), holdValidityDays);
                    idempotency.complete(idempotencyKey, 201, summary(authorized));
                    yield Result.created(authorized);
                }
                case DECLINED -> {
                    declinedCounter.increment();
                    log.info("Paiement {} refuse par l'emetteur : {} {}",
                            pending.id(), result.code(), result.message());
                    // Un refus est une reponse definitive : on la memorise pour que les
                    // retries la rejouent, au lieu de renvoyer le PSP a chaque fois.
                    Payment declined = transactions.applyDeclined(pending.id(), result.code(),
                            result.message());
                    idempotency.complete(idempotencyKey, 402, summary(declined));
                    throw new PaymentException.Declined(result.code(), result.message());
                }
                case CHALLENGE_REQUIRED -> {
                    log.info("Paiement {} necessite un challenge 3DS", pending.id());
                    yield Result.challengeRequired(pending, result.message());
                }
                case ERROR -> {
                    failedCounter.increment();
                    transactions.applyFailed(pending.id(), result.code(), result.message());
                    // Echec technique : on libere la cle pour que le client puisse
                    // retenter a l'identique. L'issue reste indeterminee cote PSP,
                    // c'est la reconciliation qui tranchera.
                    idempotency.release(idempotencyKey);
                    throw new PaymentException.PspUnavailable(result.message());
                }
            };
        } catch (PaymentException e) {
            throw e;
        } catch (RuntimeException e) {
            failedCounter.increment();
            log.error("Echec inattendu de l'autorisation du paiement {}", pending.id(), e);
            transactions.applyFailed(pending.id(), "internal_error", e.getMessage());
            idempotency.release(idempotencyKey);
            throw new PaymentException.PspUnavailable(e.getMessage());
        } finally {
            sample.stop(authorizationTimer);
        }
    }

    /**
     * Capture totale ou partielle, typiquement au check-out sur le montant reel du folio.
     */
    public Result capture(PaymentCommands.Capture command, String idempotencyKey, String requestBody) {
        String requestHash = idempotency.hash(requestBody);

        Optional<IdempotencyRecord> replay = idempotency.findReplay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            replayCounter.increment();
            return replayOf(replay.get());
        }

        Payment payment = transactions.find(command.paymentId());
        if (!idempotency.tryReserve(
                IdempotencyRecord.reserve(idempotencyKey, requestHash, payment.id()))) {
            throw new PaymentException.RequestInProgress(idempotencyKey);
        }

        PspPort.OperationResult result =
                psp.capture(payment.pspReference(), command.amount(), idempotencyKey);
        if (!result.success()) {
            idempotency.release(idempotencyKey);
            throw new PaymentException.PspUnavailable(result.message());
        }
        Payment updated = transactions.applyCaptured(payment.id(), command.amount());
        idempotency.complete(idempotencyKey, 200, summary(updated));
        return Result.ok(updated);
    }

    /** Remboursement total ou partiel, apres capture uniquement. */
    public Result refund(PaymentCommands.Refund command, String idempotencyKey, String requestBody) {
        String requestHash = idempotency.hash(requestBody);

        Optional<IdempotencyRecord> replay = idempotency.findReplay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            replayCounter.increment();
            return replayOf(replay.get());
        }

        Payment payment = transactions.find(command.paymentId());
        if (!idempotency.tryReserve(
                IdempotencyRecord.reserve(idempotencyKey, requestHash, payment.id()))) {
            throw new PaymentException.RequestInProgress(idempotencyKey);
        }

        PspPort.OperationResult result =
                psp.refund(payment.pspReference(), command.amount(), idempotencyKey);
        if (!result.success()) {
            idempotency.release(idempotencyKey);
            throw new PaymentException.PspUnavailable(result.message());
        }
        log.info("Paiement {} rembourse de {} ({})", payment.id(), command.amount(), command.reason());
        Payment updated = transactions.applyRefunded(payment.id(), command.amount());
        idempotency.complete(idempotencyKey, 200, summary(updated));
        return Result.ok(updated);
    }

    /** Annulation avant capture : libere le hold, invisible sur le releve du client. */
    public Result cancel(PaymentCommands.Cancel command, String idempotencyKey, String requestBody) {
        String requestHash = idempotency.hash(requestBody);

        Optional<IdempotencyRecord> replay = idempotency.findReplay(idempotencyKey, requestHash);
        if (replay.isPresent()) {
            replayCounter.increment();
            return replayOf(replay.get());
        }

        Payment payment = transactions.find(command.paymentId());
        if (!idempotency.tryReserve(
                IdempotencyRecord.reserve(idempotencyKey, requestHash, payment.id()))) {
            throw new PaymentException.RequestInProgress(idempotencyKey);
        }

        PspPort.OperationResult result = psp.cancel(payment.pspReference(), idempotencyKey);
        if (!result.success()) {
            idempotency.release(idempotencyKey);
            throw new PaymentException.PspUnavailable(result.message());
        }
        log.info("Paiement {} annule ({})", payment.id(), command.reason());
        Payment updated = transactions.applyCancelled(payment.id());
        idempotency.complete(idempotencyKey, 200, summary(updated));
        return Result.ok(updated);
    }

    public Payment get(UUID paymentId) {
        return transactions.find(paymentId);
    }

    /**
     * Reconstruit la reponse d'une requete deja traitee.
     *
     * <p>Un refus doit etre <b>rejoue comme un refus</b> : si on renvoyait un 200 avec
     * le paiement en etat DECLINED, un appelant qui ne lit que le code HTTP croirait
     * son paiement accepte. Le rejeu doit etre indiscernable de la premiere reponse,
     * code d'erreur compris.</p>
     *
     * <p><b>Choix assume :</b> on memorise le code HTTP et un resume, puis on
     * reconstruit la reponse depuis l'etat courant du paiement, au lieu de stocker le
     * corps JSON exact. C'est moins fidele -- si le paiement a evolue entre-temps, le
     * rejeu refletera l'etat le plus recent -- mais ca evite de maintenir deux
     * representations du meme paiement et de rejouer indefiniment une reponse perimee.
     * Pour une fidelite bit a bit, il faudrait stocker le corps serialise ; c'est le
     * choix de certaines API publiques, au prix du stockage et de la duplication.</p>
     */
    private Result replayOf(IdempotencyRecord record) {
        Payment payment = transactions.find(record.paymentId());
        if (payment.status() == PaymentStatus.DECLINED) {
            throw new PaymentException.Declined(payment.failureCode(), payment.failureReason());
        }
        return Result.replayed(payment, record.responseStatus());
    }

    /**
     * Resume stocke avec la cle d'idempotence : de quoi tracer et deboguer une
     * integration, sans jamais aucune donnee carte -- cette ligne est conservee 24 h
     * et lue par le support.
     */
    private String summary(Payment payment) {
        return "{\"paymentId\":\"" + payment.id() + "\",\"status\":\"" + payment.status() + "\"}";
    }

    /**
     * Resultat d'une operation, avec le code HTTP a renvoyer.
     *
     * @param httpStatus      201 a la creation, 200 sur une mise a jour ou un rejeu, 202
     *                        quand un challenge 3DS est attendu
     * @param replayed        vrai si la reponse provient du cache d'idempotence
     * @param challengeDetail message de redirection 3DS, le cas echeant
     */
    public record Result(Payment payment, int httpStatus, boolean replayed, String challengeDetail) {

        static Result created(Payment payment) {
            return new Result(payment, 201, false, null);
        }

        static Result ok(Payment payment) {
            return new Result(payment, 200, false, null);
        }

        static Result replayed(Payment payment, int originalStatus) {
            return new Result(payment, originalStatus == 0 ? 200 : originalStatus, true, null);
        }

        static Result challengeRequired(Payment payment, String detail) {
            return new Result(payment, 202, false, detail);
        }
    }
}
