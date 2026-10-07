package com.hospitality.payment.application;

import com.hospitality.payment.domain.CardReference;
import com.hospitality.payment.domain.Money;
import java.util.UUID;

/** Commandes d'entree de la couche applicative, decouplees des DTO HTTP. */
public final class PaymentCommands {

    private PaymentCommands() {
    }

    /**
     * @param customerPresent {@code false} pour une facturation de no-show : le client
     *                        n'est pas la, donc pas de 3DS possible. L'operation doit
     *                        alors chainer la transaction initiale authentifiee.
     */
    public record Authorize(
            String reservationId,
            String hotelId,
            Money amount,
            CardReference card,
            boolean customerPresent,
            String initialNetworkTransactionId
    ) {
    }

    public record Capture(UUID paymentId, Money amount) {
    }

    public record Refund(UUID paymentId, Money amount, String reason) {
    }

    public record Cancel(UUID paymentId, String reason) {
    }
}
