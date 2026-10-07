package com.hospitality.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospitality.payment.adapter.out.persistence.OutboxEventRepository;
import com.hospitality.payment.domain.OutboxEvent;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests de bout en bout de l'API, du controleur jusqu'a la base.
 *
 * <p>Ils couvrent ce qu'un test unitaire ne peut pas verifier : la traduction des
 * erreurs du domaine en codes HTTP, le rejeu idempotent reel passant par la contrainte
 * de cle primaire, et la production effective des evenements d'outbox dans la meme
 * transaction que le paiement.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxEventRepository outbox;

    private static String authorizeBody(String token, long amount) {
        return """
                {
                  "reservationId": "RES-%s",
                  "hotelId": "HOTEL-PAR-001",
                  "amount": %d,
                  "currency": "EUR",
                  "paymentMethodToken": "%s",
                  "cardBrand": "VISA",
                  "cardBin": "411111",
                  "cardLast4": "1111",
                  "cardExpiry": "2030-12"
                }
                """.formatted(UUID.randomUUID().toString().substring(0, 8), amount, token);
    }

    private static String amountBody(long amount) {
        return """
                {"amount": %d, "currency": "EUR", "reason": "test"}
                """.formatted(amount);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Nested
    @DisplayName("Idempotence")
    class Idempotency {

        @Test
        @DisplayName("rejouer la meme requete avec la meme cle ne cree pas un second paiement")
        void replayReturnsSamePaymentInsteadOfChargingTwice() throws Exception {
            String key = UUID.randomUUID().toString();
            String body = authorizeBody("tok_ok_visa", 12_500);

            MvcResult first = mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                    .andExpect(jsonPath("$.replayed").value(false))
                    .andReturn();

            // Le scenario reel : le client n'a pas recu la reponse (timeout, onglet
            // recharge) et retente a l'identique.
            MvcResult second = mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.replayed").value(true))
                    .andReturn();

            // Le meme paiement, pas un second debit.
            assertThat(json(second).get("id").asText())
                    .isEqualTo(json(first).get("id").asText());
        }

        @Test
        @DisplayName("reutiliser une cle avec un corps different est refuse en 422")
        void rejectsKeyReuseWithDifferentPayload() throws Exception {
            String key = UUID.randomUUID().toString();

            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_ok_visa", 10_000)))
                    .andExpect(status().isCreated());

            // Montant different, meme cle : erreur d'integration cote appelant. Rejouer
            // la premiere reponse masquerait un bug qui, ici, porte sur un montant.
            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_ok_visa", 99_000)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("idempotency_key_reuse"));
        }

        @Test
        @DisplayName("l'absence de cle d'idempotence est refusee des le contrat")
        void rejectsMissingIdempotencyKey() throws Exception {
            mockMvc.perform(post("/v1/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_ok_visa", 10_000)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("missing_header"));
        }
    }

    @Nested
    @DisplayName("Cycle de vie complet")
    class Lifecycle {

        @Test
        @DisplayName("autorisation, capture partielle au check-out, puis remboursement")
        void authorizeThenPartialCaptureThenRefund() throws Exception {
            MvcResult authorized = mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_ok_visa", 20_000)))
                    .andExpect(status().isCreated())
                    .andReturn();
            String paymentId = json(authorized).get("id").asText();

            // Check-out : le folio reel est inferieur au montant autorise.
            mockMvc.perform(post("/v1/payments/" + paymentId + "/capture")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(amountBody(15_000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PARTIALLY_CAPTURED"))
                    .andExpect(jsonPath("$.capturedAmount").value(15_000));

            // Geste commercial apres le sejour.
            mockMvc.perform(post("/v1/payments/" + paymentId + "/refund")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(amountBody(5_000)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
                    .andExpect(jsonPath("$.refundedAmount").value(5_000));

            mockMvc.perform(get("/v1/payments/" + paymentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.card").value("411111******1111"));
        }

        @Test
        @DisplayName("rembourser plus que le montant capture est refuse en 422")
        void cannotRefundMoreThanCaptured() throws Exception {
            MvcResult authorized = mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_ok_visa", 10_000)))
                    .andReturn();
            String paymentId = json(authorized).get("id").asText();

            mockMvc.perform(post("/v1/payments/" + paymentId + "/capture")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(amountBody(5_000)))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/v1/payments/" + paymentId + "/refund")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(amountBody(9_000)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("amount_exceeds_available"));
        }

        @Test
        @DisplayName("annuler un paiement deja capture est refuse en 409")
        void cannotCancelCapturedPayment() throws Exception {
            MvcResult authorized = mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_ok_visa", 8_000)))
                    .andReturn();
            String paymentId = json(authorized).get("id").asText();

            mockMvc.perform(post("/v1/payments/" + paymentId + "/capture")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(amountBody(8_000)))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/v1/payments/" + paymentId + "/cancel")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"erreur de saisie\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("invalid_state_transition"));
        }
    }

    @Nested
    @DisplayName("Refus et erreurs du prestataire")
    class DeclinesAndErrors {

        @Test
        @DisplayName("un refus de l'emetteur renvoie 402, pas 400 ni 500")
        void declineReturnsPaymentRequired() throws Exception {
            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_decline_visa", 10_000)))
                    // La requete etait valide et notre service fonctionne : c'est la
                    // banque qui refuse. 402 le dit exactement.
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.code").value("payment_declined"))
                    .andExpect(jsonPath("$.retryable").value(false))
                    .andExpect(jsonPath("$.issuerCode").value("05"));
        }

        @Test
        @DisplayName("fonds insuffisants : refus distinct, avec le code de l'emetteur")
        void insufficientFundsCarriesIssuerCode() throws Exception {
            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_insufficient_funds", 10_000)))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.issuerCode").value("51"));
        }

        @Test
        @DisplayName("une panne du prestataire renvoie 503 et se declare rejouable")
        void pspOutageReturnsServiceUnavailable() throws Exception {
            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_error_timeout", 10_000)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("psp_unavailable"))
                    // Le client doit savoir qu'il peut retenter, et avec la meme cle.
                    .andExpect(jsonPath("$.retryable").value(true));
        }

        @Test
        @DisplayName("une facturation de no-show sans chainage de la CIT initiale est refusee")
        void mitWithoutInitialTransactionIsDeclined() throws Exception {
            String body = """
                    {
                      "reservationId": "RES-NOSHOW-1",
                      "hotelId": "HOTEL-PAR-001",
                      "amount": 15_000,
                      "currency": "EUR",
                      "paymentMethodToken": "tok_ok_visa",
                      "customerPresent": false
                    }
                    """.replace("15_000", "15000");

            // Le client n'est pas la, donc pas de 3DS possible : sans reference a la
            // transaction initiale authentifiee, l'emetteur a le droit de refuser et la
            // responsabilite du chargeback retombe sur le commercant.
            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.issuerCode").value("1A"));
        }

        @Test
        @DisplayName("un montant negatif ou nul est rejete par la validation")
        void rejectsInvalidAmount() throws Exception {
            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_ok_visa", 0)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_failed"));
        }
    }

    @Nested
    @DisplayName("Outbox transactionnel")
    class Outbox {

        @Test
        @DisplayName("une autorisation reussie produit un evenement dans la meme transaction")
        void authorizationWritesOutboxEvent() throws Exception {
            long before = outbox.countByStatus(OutboxEvent.Status.PENDING)
                    + outbox.countByStatus(OutboxEvent.Status.PUBLISHED);

            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_ok_visa", 10_000)))
                    .andExpect(status().isCreated());

            long after = outbox.countByStatus(OutboxEvent.Status.PENDING)
                    + outbox.countByStatus(OutboxEvent.Status.PUBLISHED);

            // L'evenement existe parce que la transaction du paiement a commite. Il n'y
            // a aucun cas ou le paiement est enregistre sans son evenement, ni
            // l'inverse : c'est precisement ce que le pattern garantit.
            assertThat(after).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("aucun evenement ne contient de donnee carte exploitable")
        void outboxPayloadCarriesNoCardData() throws Exception {
            mockMvc.perform(post("/v1/payments")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(authorizeBody("tok_secret_value_123", 10_000)))
                    .andExpect(status().isCreated());

            // Un evenement est copie, archive et rejoue chez tous les consommateurs :
            // c'est le pire endroit pour une donnee sensible. Ce test echoue si
            // quelqu'un ajoute un jour le token au payload.
            assertThat(outbox.findAll())
                    .allSatisfy(event ->
                            assertThat(event.payload()).doesNotContain("tok_secret_value_123"));
        }
    }
}
