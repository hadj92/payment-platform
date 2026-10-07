package com.hospitality.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentStatusTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING, AUTHORIZED, true",
            "PENDING, DECLINED, true",
            "PENDING, CAPTURED, false",          // on ne capture jamais sans autorisation
            "AUTHORIZED, CAPTURED, true",
            "AUTHORIZED, REFUNDED, false",       // on annule une autorisation, on ne la rembourse pas
            "CAPTURED, REFUNDED, true",
            "CAPTURED, CANCELLED, false",        // apres capture, le retour se fait par remboursement
            "DECLINED, AUTHORIZED, false",       // un refus ne se reecrit pas
            "REFUNDED, CAPTURED, false",
            "FAILED, PENDING, true"              // seul un echec technique est rejouable
    })
    @DisplayName("les transitions autorisees sont declarees une seule fois, dans l'enum")
    void transitionRules(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"REFUNDED", "CANCELLED", "DECLINED", "EXPIRED"})
    @DisplayName("les etats terminaux n'autorisent plus aucune transition")
    void terminalStatesAreClosed(PaymentStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"PARTIALLY_CAPTURED", "CAPTURED", "PARTIALLY_REFUNDED", "REFUNDED"})
    @DisplayName("hasCapturedFunds identifie les etats ou de l'argent a reellement bouge")
    void identifiesStatesWithMovedFunds(PaymentStatus status) {
        // C'est le critere qui separe ce qui doit apparaitre dans la reconciliation
        // comptable de ce qui n'est qu'une reservation de fonds.
        assertThat(status.hasCapturedFunds()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "AUTHORIZED", "DECLINED", "CANCELLED"})
    @DisplayName("un hold ou un refus ne compte pas comme un encaissement")
    void holdsAndDeclinesAreNotEarnings(PaymentStatus status) {
        assertThat(status.hasCapturedFunds()).isFalse();
    }
}
