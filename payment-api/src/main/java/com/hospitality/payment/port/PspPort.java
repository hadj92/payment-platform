package com.hospitality.payment.port;

import com.hospitality.payment.domain.Money;

/**
 * Port de sortie vers le prestataire de paiement (Adyen, Worldline, Stripe...).
 *
 * <p><b>Pourquoi une interface et pas un appel direct :</b> le choix du PSP est une
 * decision commerciale qui change -- renegociation de taux, besoin de redondance, PSP
 * different par pays ou par devise. Si le domaine depend d'un SDK concret, changer de
 * PSP devient une refonte. Ici, le domaine depend d'un contrat que nous possedons ;
 * ajouter un second PSP, c'est une implementation de plus et une regle de routage.</p>
 *
 * <p>Deuxieme benefice, immediat celui-la : les tests du domaine tournent sans reseau,
 * et on peut simuler un refus, un timeout ou un challenge 3DS a volonte.</p>
 */
public interface PspPort {

    /**
     * Demande d'autorisation : fait reserver les fonds chez l'emetteur.
     *
     * @param idempotencyKey propage jusqu'au PSP. Les PSP exposent eux aussi une
     *                       idempotence : si notre retry atteint le PSP alors que la
     *                       premiere tentative avait abouti, c'est lui qui deduplique.
     *                       L'idempotence doit etre tenue a chaque maillon, pas seulement
     *                       chez nous.
     */
    AuthorizationResult authorize(AuthorizationRequest request, String idempotencyKey);

    /** Conversion de l'autorisation en demande de paiement reelle (totale ou partielle). */
    OperationResult capture(String pspReference, Money amount, String idempotencyKey);

    OperationResult refund(String pspReference, Money amount, String idempotencyKey);

    /** Annulation avant capture : libere le hold cote emetteur. */
    OperationResult cancel(String pspReference, String idempotencyKey);

    // ------------------------------------------------------------------ contrats

    record AuthorizationRequest(
            String paymentMethodToken,
            Money amount,
            String reservationReference,
            String hotelId,
            /** false pour une MIT (no-show) : le client n'est pas la, pas de 3DS possible. */
            boolean customerPresent,
            /** Chainage de la CIT initiale, requis pour qu'une MIT soit acceptee et exemptee de SCA. */
            String initialNetworkTransactionId
    ) {
    }

    /**
     * Resultat d'autorisation.
     *
     * <p>La distinction entre {@link Outcome#DECLINED} et {@link Outcome#ERROR} est
     * structurante : un refus est une decision de l'emetteur qu'il ne faut pas
     * retenter a l'identique (les schemes facturent les retries abusifs et degradent
     * le taux d'autorisation), une erreur technique est rejouable.</p>
     */
    record AuthorizationResult(
            Outcome outcome,
            String pspReference,
            String networkTransactionId,
            String threeDsOutcome,
            String code,
            String message
    ) {
        public enum Outcome {
            AUTHORIZED,
            /** Refus de l'emetteur : definitif pour cette tentative. */
            DECLINED,
            /** 3DS requis : il faut rediriger le porteur vers un challenge. */
            CHALLENGE_REQUIRED,
            /** Panne technique : rejouable avec la meme cle d'idempotence. */
            ERROR
        }

        public static AuthorizationResult authorized(String pspReference, String networkTxId, String threeDs) {
            return new AuthorizationResult(Outcome.AUTHORIZED, pspReference, networkTxId, threeDs, "00", "Approved");
        }

        public static AuthorizationResult declined(String code, String message) {
            return new AuthorizationResult(Outcome.DECLINED, null, null, null, code, message);
        }

        public static AuthorizationResult error(String code, String message) {
            return new AuthorizationResult(Outcome.ERROR, null, null, null, code, message);
        }
    }

    record OperationResult(boolean success, String pspReference, String code, String message) {
        public static OperationResult ok(String pspReference) {
            return new OperationResult(true, pspReference, "00", "Success");
        }

        public static OperationResult failed(String code, String message) {
            return new OperationResult(false, null, code, message);
        }
    }
}
