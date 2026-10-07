package com.hospitality.payment.application;

import com.hospitality.payment.adapter.out.persistence.OutboxEventRepository;
import com.hospitality.payment.adapter.out.persistence.PaymentRepository;
import com.hospitality.payment.domain.Money;
import com.hospitality.payment.domain.OutboxEvent;
import com.hospitality.payment.domain.Payment;
import com.hospitality.payment.domain.PaymentException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Frontieres transactionnelles de l'ecriture des paiements.
 *
 * <p><b>Pourquoi une classe separee de {@link PaymentService} :</b> les transactions
 * Spring sont implementees par un proxy. Un appel d'une methode {@code @Transactional}
 * depuis une autre methode du <i>meme</i> bean ne traverse pas le proxy : l'annotation
 * est silencieusement ignoree. C'est un piege classique. En sortant les methodes
 * transactionnelles dans un bean distinct, chaque appel passe bien par le proxy.</p>
 *
 * <p><b>Et surtout :</b> l'orchestration doit pouvoir <b>fermer la transaction avant</b>
 * d'appeler le PSP, puis en ouvrir une seconde pour enregistrer le resultat. Garder une
 * transaction ouverte pendant un appel reseau de plusieurs centaines de millisecondes
 * immobilise une connexion du pool pour rien : sous charge, le pool se vide et toute
 * l'application s'arrete, y compris les lectures. C'est un mode de defaillance classique
 * des services qui appellent un tiers depuis l'interieur d'une transaction.</p>
 */
@Service
public class PaymentTransactions {

    private final PaymentRepository payments;
    private final OutboxEventRepository outbox;

    public PaymentTransactions(PaymentRepository payments, OutboxEventRepository outbox) {
        this.payments = payments;
        this.outbox = outbox;
    }

    /**
     * Transaction 1 : on enregistre l'intention <b>avant</b> tout appel reseau.
     *
     * <p>Si le processus meurt pendant l'appel au PSP, cette ligne PENDING est la seule
     * trace qu'une autorisation a peut-etre abouti. Sans elle, on aurait un paiement
     * fantome : client debite, aucune trace chez nous, et une reclamation impossible a
     * instruire. Le reconciliateur balaye ces PENDING orphelins et interroge le PSP.</p>
     */
    @Transactional
    public Payment persistIntent(Payment payment) {
        return payments.save(payment);
    }

    /**
     * Transaction 2 : resultat de l'autorisation + evenement metier, atomiquement.
     *
     * <p>L'ecriture de l'outbox dans la <i>meme</i> transaction que le paiement est tout
     * l'interet du pattern : soit les deux sont commites, soit aucun. On n'annonce
     * jamais un paiement qui n'existe pas, et on ne laisse jamais une reservation en
     * attente sur un paiement pourtant autorise.</p>
     */
    @Transactional
    public Payment applyAuthorized(UUID paymentId, String pspReference, String networkTransactionId,
                                   String threeDsOutcome, int holdValidityDays) {
        Payment payment = load(paymentId);
        payment.markAuthorized(pspReference, networkTransactionId, threeDsOutcome, holdValidityDays);
        payments.save(payment);
        outbox.save(OutboxEvent.forPayment(payment, "PaymentAuthorized", eventPayload(payment)));
        return payment;
    }

    @Transactional
    public Payment applyDeclined(UUID paymentId, String code, String reason) {
        Payment payment = load(paymentId);
        payment.markDeclined(code, reason);
        payments.save(payment);
        outbox.save(OutboxEvent.forPayment(payment, "PaymentDeclined", eventPayload(payment)));
        return payment;
    }

    @Transactional
    public Payment applyFailed(UUID paymentId, String code, String reason) {
        Payment payment = load(paymentId);
        payment.markFailed(code, reason);
        return payments.save(payment);
    }

    @Transactional
    public Payment applyCaptured(UUID paymentId, Money amount) {
        Payment payment = load(paymentId);
        payment.capture(amount);
        payments.save(payment);
        outbox.save(OutboxEvent.forPayment(payment, "PaymentCaptured", eventPayload(payment)));
        return payment;
    }

    @Transactional
    public Payment applyRefunded(UUID paymentId, Money amount) {
        Payment payment = load(paymentId);
        payment.refund(amount);
        payments.save(payment);
        outbox.save(OutboxEvent.forPayment(payment, "PaymentRefunded", eventPayload(payment)));
        return payment;
    }

    @Transactional
    public Payment applyCancelled(UUID paymentId) {
        Payment payment = load(paymentId);
        payment.cancel();
        payments.save(payment);
        outbox.save(OutboxEvent.forPayment(payment, "PaymentCancelled", eventPayload(payment)));
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment find(UUID paymentId) {
        return load(paymentId);
    }

    private Payment load(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(() -> new PaymentException.NotFound(paymentId));
    }

    /**
     * Charge utile de l'evenement.
     *
     * <p>Deux regles : aucune donnee carte au-dela du masque autorise par PCI DSS -- un
     * evenement est copie, archive et rejoue chez tous les consommateurs, donc c'est le
     * pire endroit pour une donnee sensible -- et un {@code version} explicite, parce
     * qu'un nom d'evenement est un contrat public qui devra evoluer sans casser les
     * consommateurs existants.</p>
     *
     * <p>Ici en concatenation pour rester lisible ; en production on passe par un
     * serialiseur avec un schema enregistre (EventBridge Schema Registry, Avro) qui
     * valide la compatibilite a la publication.</p>
     */
    private String eventPayload(Payment payment) {
        return "{"
                + "\"version\":1,"
                + "\"paymentId\":\"" + payment.id() + "\","
                + "\"reservationId\":\"" + payment.reservationId() + "\","
                + "\"hotelId\":\"" + payment.hotelId() + "\","
                + "\"status\":\"" + payment.status() + "\","
                + "\"currency\":\"" + payment.currency().getCurrencyCode() + "\","
                + "\"authorizedAmount\":" + payment.authorizedAmount().minorUnits() + ","
                + "\"capturedAmount\":" + payment.capturedAmount().minorUnits() + ","
                + "\"refundedAmount\":" + payment.refundedAmount().minorUnits() + ","
                + "\"cardMasked\":\"" + payment.card().masked() + "\","
                + "\"occurredAt\":\"" + payment.updatedAt() + "\""
                + "}";
    }
}
