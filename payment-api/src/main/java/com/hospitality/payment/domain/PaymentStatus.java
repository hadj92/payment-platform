package com.hospitality.payment.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Machine a etats d'un paiement. Les transitions autorisees sont declarees ici, une
 * fois, plutot que dispersees en {@code if} dans les services.
 *
 * <p>En entretien : rendre la machine a etats explicite est ce qui permet de garantir
 * qu'on ne capture pas deux fois, qu'on ne rembourse pas un paiement refuse, et qu'un
 * message rejoue depuis une file SQS (qui est <i>at-least-once</i>) ne corrompt pas
 * l'etat du paiement.</p>
 *
 * <pre>
 *   PENDING ──► AUTHORIZED ──► PARTIALLY_CAPTURED ──► CAPTURED ──► PARTIALLY_REFUNDED ──► REFUNDED
 *      │            │                   │                │                  │
 *      ├─► DECLINED ├─► CANCELLED       └────────────────►│                  └──────────►│
 *      └─► FAILED   └─► EXPIRED                           └─► PARTIALLY_REFUNDED / REFUNDED
 * </pre>
 */
public enum PaymentStatus {

    /** Intention enregistree, autorisation pas encore confirmee par l'emetteur. */
    PENDING,
    /** Fonds reserves chez l'emetteur (hold). Aucun argent n'a encore bouge. */
    AUTHORIZED,
    /** Une partie du montant autorise a ete capturee (sejour raccourci, folio partiel). */
    PARTIALLY_CAPTURED,
    /** Capture totale demandee : l'argent est reclame. */
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    /** Autorisation annulee avant capture : le hold est libere, invisible pour le client. */
    CANCELLED,
    /** Refus de l'emetteur (fonds insuffisants, carte bloquee...). */
    DECLINED,
    /** Echec technique : timeout, PSP indisponible. A distinguer d'un refus metier. */
    FAILED,
    /** L'autorisation a depasse sa duree de validite sans capture. */
    EXPIRED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = Map.of(
            PENDING,            EnumSet.of(AUTHORIZED, DECLINED, FAILED),
            AUTHORIZED,         EnumSet.of(PARTIALLY_CAPTURED, CAPTURED, CANCELLED, EXPIRED),
            PARTIALLY_CAPTURED, EnumSet.of(PARTIALLY_CAPTURED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED),
            CAPTURED,           EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            REFUNDED,           Collections.emptySet(),
            CANCELLED,          Collections.emptySet(),
            DECLINED,           Collections.emptySet(),
            FAILED,             EnumSet.of(PENDING),   // rejeu possible apres echec technique
            EXPIRED,            Collections.emptySet()
    );

    public boolean canTransitionTo(PaymentStatus target) {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    /** Etat final : plus aucune operation n'est possible, inutile de garder le paiement en file. */
    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).isEmpty();
    }

    /** Vrai si de l'argent a ete effectivement reclame (base de la reconciliation comptable). */
    public boolean hasCapturedFunds() {
        return this == PARTIALLY_CAPTURED || this == CAPTURED
                || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
