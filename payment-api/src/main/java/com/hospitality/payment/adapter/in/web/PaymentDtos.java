package com.hospitality.payment.adapter.in.web;

import com.hospitality.payment.domain.Payment;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Contrats HTTP.
 *
 * <p>Deliberement distincts des entites du domaine : exposer directement une entite JPA
 * couple le contrat public au schema de base -- une colonne renommee casse les clients --
 * et finit toujours par laisser fuiter un champ qu'on ne voulait pas publier.</p>
 *
 * <p>Les montants circulent en <b>unites mineures</b>, comme chez tous les PSP : un
 * entier n'a pas d'ambiguite de representation, contrairement a un decimal JSON que
 * chaque langage client arrondira a sa facon.</p>
 */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record AuthorizeRequest(

            @NotBlank @Size(max = 64)
            String reservationId,

            @NotBlank @Size(max = 32)
            String hotelId,

            /** Montant en unites mineures (1050 = 10,50 EUR). Plafond : garde-fou anti-erreur de saisie. */
            @Positive @Max(99_999_999L)
            long amount,

            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "Code devise ISO 4217 attendu, ex. EUR")
            String currency,

            /** Token emis par le PSP. Le PAN n'apparait jamais dans ce contrat, par construction. */
            @NotBlank @Size(max = 128)
            String paymentMethodToken,

            @Size(max = 20) String cardBrand,
            @Pattern(regexp = "^[0-9]{6,8}$|^$", message = "BIN de 6 a 8 chiffres") String cardBin,
            @Pattern(regexp = "^[0-9]{4}$|^$", message = "4 derniers chiffres") String cardLast4,
            @Pattern(regexp = "^[0-9]{4}-[0-9]{2}$|^$", message = "Format attendu AAAA-MM") String cardExpiry,

            /**
             * {@code false} pour une facturation de no-show : le client n'est pas la,
             * donc pas de 3DS. Il faut alors fournir {@code initialNetworkTransactionId}.
             */
            Boolean customerPresent,

            /** Chainage de la CIT initiale authentifiee, requis pour qu'une MIT soit exemptee de SCA. */
            @Size(max = 64) String initialNetworkTransactionId
    ) {
        public boolean customerPresentOrDefault() {
            return customerPresent == null || customerPresent;
        }
    }

    public record AmountRequest(
            @Positive @Max(99_999_999L) long amount,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Size(max = 255) String reason
    ) {
    }

    public record CancelRequest(@Size(max = 255) String reason) {
    }

    /** Vue publique d'un paiement. Seul le masque autorise par PCI DSS sort d'ici. */
    public record PaymentResponse(
            UUID id,
            String reservationId,
            String hotelId,
            String status,
            String currency,
            long authorizedAmount,
            long capturedAmount,
            long refundedAmount,
            BigDecimal authorizedAmountDecimal,
            String card,
            String cardBrand,
            String pspReference,
            String threeDsOutcome,
            String failureCode,
            String failureReason,
            Instant authorizationExpiresAt,
            Instant createdAt,
            Instant updatedAt,
            /** Vrai quand la reponse provient du cache d'idempotence : utile au debug d'integration. */
            boolean replayed
    ) {
        public static PaymentResponse from(Payment payment, boolean replayed) {
            return new PaymentResponse(
                    payment.id(),
                    payment.reservationId(),
                    payment.hotelId(),
                    payment.status().name(),
                    payment.currency().getCurrencyCode(),
                    payment.authorizedAmount().minorUnits(),
                    payment.capturedAmount().minorUnits(),
                    payment.refundedAmount().minorUnits(),
                    payment.authorizedAmount().toDecimal(),
                    payment.card().masked(),
                    payment.card().brand(),
                    payment.pspReference(),
                    payment.threeDsOutcome(),
                    payment.failureCode(),
                    payment.failureReason(),
                    payment.authorizationExpiresAt(),
                    payment.createdAt(),
                    payment.updatedAt(),
                    replayed);
        }
    }
}
