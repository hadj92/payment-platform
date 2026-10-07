package com.hospitality.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests du cycle de vie d'un paiement.
 *
 * <p>Aucun mock, aucune base, aucun conteneur : le domaine est volontairement sans
 * dependance d'infrastructure, donc ces tests tournent en millisecondes. C'est la
 * contrepartie concrete de l'architecture hexagonale -- les regles qui coutent de
 * l'argent quand elles sont fausses sont aussi les plus rapides a verifier.</p>
 */
class PaymentTest {

    private static final Money HUNDRED_EUROS = Money.of(10_000, "EUR");

    private Payment newPendingPayment() {
        return Payment.initiate("RES-12345", "HOTEL-PAR-001", HUNDRED_EUROS, aCard());
    }

    private Payment newAuthorizedPayment() {
        Payment payment = newPendingPayment();
        payment.markAuthorized("PSP123", "NTI456", "FRICTIONLESS", 7);
        return payment;
    }

    private static CardReference aCard() {
        return new CardReference("tok_live_abc123", "VISA", "411111", "1111", "2030-12");
    }

    @Nested
    @DisplayName("Autorisation")
    class Authorization {

        @Test
        @DisplayName("un paiement nait PENDING, avant tout appel au PSP")
        void startsPending() {
            Payment payment = newPendingPayment();

            assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.authorizedAmount()).isEqualTo(HUNDRED_EUROS);
            assertThat(payment.capturedAmount().isZero()).isTrue();
            assertThat(payment.id()).isNotNull();
        }

        @Test
        @DisplayName("l'autorisation enregistre le networkTransactionId, indispensable aux MIT futures")
        void authorizationStoresNetworkTransactionId() {
            Payment payment = newAuthorizedPayment();

            assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
            // Sans cet identifiant, une facturation de no-show emise plus tard serait
            // refusee par l'emetteur et perdrait son exemption SCA.
            assertThat(payment.networkTransactionId()).isEqualTo("NTI456");
            assertThat(payment.authorizationExpiresAt()).isNotNull();
        }

        @Test
        @DisplayName("un paiement refuse est terminal : aucune capture possible ensuite")
        void declinedIsTerminal() {
            Payment payment = newPendingPayment();
            payment.markDeclined("05", "Do not honor");

            assertThat(payment.status().isTerminal()).isTrue();
            assertThatThrownBy(() -> payment.capture(HUNDRED_EUROS))
                    .isInstanceOf(PaymentException.InvalidState.class);
        }

        @Test
        @DisplayName("un echec technique reste rejouable, contrairement a un refus")
        void technicalFailureIsReplayable() {
            Payment payment = newPendingPayment();
            payment.markFailed("psp_unavailable", "timeout");

            // La nuance est centrale : un refus est une decision definitive de
            // l'emetteur, une panne est un incident dont on peut se remettre.
            assertThat(payment.status().isTerminal()).isFalse();
            assertThat(payment.status().canTransitionTo(PaymentStatus.PENDING)).isTrue();
        }
    }

    @Nested
    @DisplayName("Capture")
    class Capture {

        @Test
        @DisplayName("capturer le montant total passe le paiement a CAPTURED")
        void fullCapture() {
            Payment payment = newAuthorizedPayment();
            payment.capture(HUNDRED_EUROS);

            assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.capturableAmount().isZero()).isTrue();
        }

        @Test
        @DisplayName("capture partielle : le client part un jour plus tot que prevu")
        void partialCaptureAtCheckout() {
            Payment payment = newAuthorizedPayment();
            payment.capture(Money.of(7_000, "EUR"));

            assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_CAPTURED);
            assertThat(payment.capturedAmount()).isEqualTo(Money.of(7_000, "EUR"));
            assertThat(payment.capturableAmount()).isEqualTo(Money.of(3_000, "EUR"));
        }

        @Test
        @DisplayName("captures incrementales successives jusqu'au montant autorise")
        void incrementalCaptures() {
            Payment payment = newAuthorizedPayment();
            payment.capture(Money.of(4_000, "EUR"));
            payment.capture(Money.of(3_000, "EUR"));
            payment.capture(Money.of(3_000, "EUR"));

            assertThat(payment.status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(payment.capturedAmount()).isEqualTo(HUNDRED_EUROS);
        }

        @Test
        @DisplayName("capturer plus que l'autorise est refuse par le domaine")
        void cannotCaptureMoreThanAuthorized() {
            Payment payment = newAuthorizedPayment();

            // L'invariant est porte par l'agregat : aucun appelant ne peut le contourner,
            // meme un futur service ecrit par quelqu'un qui ne connait pas la regle.
            assertThatThrownBy(() -> payment.capture(Money.of(10_001, "EUR")))
                    .isInstanceOf(PaymentException.AmountExceeded.class)
                    .hasMessageContaining("disponible");
        }

        @Test
        @DisplayName("un paiement deja entierement capture ne peut pas l'etre deux fois")
        void cannotDoubleCapture() {
            Payment payment = newAuthorizedPayment();
            payment.capture(HUNDRED_EUROS);

            // C'est exactement le scenario d'un message SQS livre deux fois : la
            // seconde capture doit echouer plutot que de debiter une seconde fois.
            assertThatThrownBy(() -> payment.capture(Money.of(1, "EUR")))
                    .isInstanceOf(PaymentException.AmountExceeded.class);
        }
    }

    @Nested
    @DisplayName("Remboursement")
    class Refund {

        @Test
        @DisplayName("on ne rembourse que ce qui a ete reellement capture")
        void cannotRefundMoreThanCaptured() {
            Payment payment = newAuthorizedPayment();
            payment.capture(Money.of(5_000, "EUR"));

            assertThatThrownBy(() -> payment.refund(Money.of(5_001, "EUR")))
                    .isInstanceOf(PaymentException.AmountExceeded.class);
        }

        @Test
        @DisplayName("un paiement autorise mais non capture ne peut pas etre rembourse")
        void cannotRefundUncapturedPayment() {
            Payment payment = newAuthorizedPayment();

            // Erreur frequente en support : on annule une autorisation, on ne la
            // rembourse pas. Le remboursement creerait un mouvement d'argent inverse
            // alors qu'aucun argent n'a bouge.
            assertThatThrownBy(() -> payment.refund(Money.of(1, "EUR")))
                    .isInstanceOf(PaymentException.AmountExceeded.class);
        }

        @Test
        @DisplayName("remboursement partiel puis total")
        void partialThenFullRefund() {
            Payment payment = newAuthorizedPayment();
            payment.capture(HUNDRED_EUROS);

            payment.refund(Money.of(3_000, "EUR"));
            assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

            payment.refund(Money.of(7_000, "EUR"));
            assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(payment.status().isTerminal()).isTrue();
            assertThat(payment.refundableAmount().isZero()).isTrue();
        }
    }

    @Nested
    @DisplayName("Annulation et expiration")
    class CancelAndExpire {

        @Test
        @DisplayName("annuler avant capture libere le hold")
        void cancelBeforeCapture() {
            Payment payment = newAuthorizedPayment();
            payment.cancel();

            assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(payment.status().isTerminal()).isTrue();
        }

        @Test
        @DisplayName("on ne peut pas annuler un paiement deja capture : il faut rembourser")
        void cannotCancelCapturedPayment() {
            Payment payment = newAuthorizedPayment();
            payment.capture(HUNDRED_EUROS);

            assertThatThrownBy(payment::cancel)
                    .isInstanceOf(PaymentException.InvalidState.class);
        }

        @Test
        @DisplayName("une autorisation echue est detectee tant qu'aucun fonds n'a ete capture")
        void detectsExpiredAuthorization() {
            Payment payment = newPendingPayment();
            payment.markAuthorized("PSP1", "NTI1", "FRICTIONLESS", 0);

            assertThat(payment.isAuthorizationExpired(
                    java.time.Instant.now().plusSeconds(60))).isTrue();
        }

        @Test
        @DisplayName("une autorisation deja capturee n'est jamais consideree comme echue")
        void capturedPaymentNeverExpires() {
            Payment payment = newPendingPayment();
            payment.markAuthorized("PSP1", "NTI1", "FRICTIONLESS", 0);
            payment.capture(HUNDRED_EUROS);

            assertThat(payment.isAuthorizationExpired(
                    java.time.Instant.now().plusSeconds(86_400))).isFalse();
        }
    }

    @Nested
    @DisplayName("Protection des donnees carte")
    class CardDataProtection {

        @Test
        @DisplayName("le masque respecte le format autorise par PCI DSS : BIN + 4 derniers")
        void maskedCardFollowsPciFormat() {
            CardReference card = aCard();

            assertThat(card.masked()).isEqualTo("411111******1111");
            assertThat(card.masked()).doesNotContain("tok_live");
        }

        @Test
        @DisplayName("le toString ne laisse jamais fuiter le token du PSP")
        void toStringNeverLeaksToken() {
            // Premiere cause reelle de fuite de donnees carte : un toString() par defaut
            // qui part dans un log d'erreur ou une stack trace envoyee a un agregateur.
            assertThat(aCard().toString()).doesNotContain("tok_live_abc123");
            assertThat(newPendingPayment().toString()).doesNotContain("tok_live_abc123");
        }
    }
}
