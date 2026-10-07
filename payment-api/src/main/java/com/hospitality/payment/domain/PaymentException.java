package com.hospitality.payment.domain;

import java.util.UUID;

/**
 * Erreurs metier du domaine paiement.
 *
 * <p>Chaque sous-type porte un {@code code} stable, expose tel quel dans l'API. Un
 * client d'API de paiement doit pouvoir brancher sa logique sur un code machine, pas
 * sur un message en francais susceptible de changer a la prochaine relecture.</p>
 */
public abstract class PaymentException extends RuntimeException {

    private final String code;

    protected PaymentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Transition interdite par la machine a etats : capture d'un paiement refuse, double annulation... */
    public static class InvalidState extends PaymentException {
        public InvalidState(UUID paymentId, PaymentStatus from, PaymentStatus to) {
            super("invalid_state_transition",
                    "Paiement " + paymentId + " : transition " + from + " -> " + to + " interdite");
        }
    }

    /** On tente de capturer ou rembourser plus que ce qui est disponible. */
    public static class AmountExceeded extends PaymentException {
        public AmountExceeded(String operation, Money requested, Money available) {
            super("amount_exceeds_available",
                    operation + " de " + requested + " impossible : " + available + " disponible");
        }
    }

    public static class NotFound extends PaymentException {
        public NotFound(UUID paymentId) {
            super("payment_not_found", "Paiement " + paymentId + " inconnu");
        }
    }

    /**
     * Meme cle d'idempotence, corps de requete different. C'est une erreur
     * d'integration cote appelant : la rejouer silencieusement masquerait un bug qui,
     * en paiement, coute de l'argent.
     */
    public static class IdempotencyConflict extends PaymentException {
        public IdempotencyConflict(String key) {
            super("idempotency_key_reuse",
                    "La cle d'idempotence '" + key + "' a deja ete utilisee avec une requete differente");
        }
    }

    /**
     * Une requete portant la meme cle est encore en cours de traitement. On refuse
     * plutot que de laisser deux autorisations partir en parallele.
     */
    public static class RequestInProgress extends PaymentException {
        public RequestInProgress(String key) {
            super("request_in_progress",
                    "Une requete portant la cle '" + key + "' est en cours de traitement, reessayez");
        }
    }

    /** Le PSP est injoignable ou a renvoye une erreur technique : rejouable. */
    public static class PspUnavailable extends PaymentException {
        public PspUnavailable(String detail) {
            super("psp_unavailable", "Prestataire de paiement indisponible : " + detail);
        }
    }

    /** L'emetteur a refuse. Decision metier, pas une panne : code HTTP 402. */
    public static class Declined extends PaymentException {
        private final String pspCode;

        public Declined(String pspCode, String message) {
            super("payment_declined", "Paiement refuse (" + pspCode + ") : " + message);
            this.pspCode = pspCode;
        }

        public String pspCode() {
            return pspCode;
        }
    }
}
