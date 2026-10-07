package com.hospitality.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Agregat paiement : la source de verite d'une transaction, du cycle hotelier complet
 * (garantie -> sejour -> check-out) jusqu'au remboursement.
 *
 * <p><b>Pourquoi la logique est ici et pas dans le service :</b> les invariants
 * monetaires (ne jamais capturer plus que l'autorise, ne jamais rembourser plus que
 * le capture) doivent etre impossibles a contourner. S'ils vivent dans un service,
 * le prochain appelant les oubliera. Ici, l'etat ne peut changer que par une methode
 * qui valide d'abord.</p>
 *
 * <p><b>Verrouillage optimiste</b> ({@code @Version}) : deux captures concurrentes sur
 * le meme paiement -- parce qu'un message SQS a ete livre deux fois, ce qui arrive par
 * conception en <i>at-least-once</i> -- feraient un double debit. La seconde ecriture
 * leve une {@code OptimisticLockException} et echoue proprement, au lieu d'ecraser la
 * premiere. On prefere ici l'optimiste au pessimiste : la contention reelle est faible
 * (peu d'ecritures concurrentes sur un meme paiement) et on ne veut pas tenir un verrou
 * de base pendant un appel reseau au PSP.</p>
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Reference fonctionnelle de la reservation : sert a la reconciliation metier. */
    @Column(name = "reservation_id", nullable = false, length = 64)
    private String reservationId;

    /** Hotel beneficiaire : porte le cloisonnement multi-tenant du portail. */
    @Column(name = "hotel_id", nullable = false, length = 32)
    private String hotelId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "authorized_amount", nullable = false)
    private long authorizedAmount;

    @Column(name = "captured_amount", nullable = false)
    private long capturedAmount;

    @Column(name = "refunded_amount", nullable = false)
    private long refundedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentStatus status;

    @Embedded
    private CardReference card;

    /** Identifiant de la transaction chez le PSP : la cle de jointure de la reconciliation. */
    @Column(name = "psp_reference", length = 64)
    private String pspReference;

    /**
     * Identifiant de transaction reseau renvoye par l'emetteur lors de la transaction
     * initiale authentifiee (CIT). Indispensable pour chainer une MIT ulterieure --
     * typiquement une facturation de no-show -- sans se faire refuser ni perdre
     * l'exemption SCA.
     */
    @Column(name = "network_transaction_id", length = 64)
    private String networkTransactionId;

    @Column(name = "three_ds_outcome", length = 24)
    private String threeDsOutcome;

    @Column(name = "failure_code", length = 48)
    private String failureCode;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /** Une autorisation a une duree de vie : au-dela, le hold tombe et il faut re-autoriser. */
    @Column(name = "authorization_expires_at")
    private Instant authorizationExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Requis par JPA. */
    protected Payment() {
    }

    /**
     * Cree une intention de paiement a l'etat {@link PaymentStatus#PENDING}.
     *
     * <p>On persiste l'intention <b>avant</b> d'appeler le PSP, jamais apres : si le
     * processus meurt pendant l'appel reseau, on garde une trace d'un paiement
     * potentiellement autorise chez le PSP. Sans cette trace, on a un paiement
     * fantome -- le client est debite, nous n'en savons rien. Le reconciliateur
     * retrouve ces PENDING orphelins et interroge le PSP pour trancher.</p>
     */
    public static Payment initiate(String reservationId, String hotelId, Money amount, CardReference card) {
        Payment payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        payment.hotelId = Objects.requireNonNull(hotelId, "hotelId");
        payment.currencyCode = amount.currency().getCurrencyCode();
        payment.authorizedAmount = amount.minorUnits();
        payment.capturedAmount = 0L;
        payment.refundedAmount = 0L;
        payment.status = PaymentStatus.PENDING;
        payment.card = Objects.requireNonNull(card, "card");
        payment.createdAt = Instant.now();
        payment.updatedAt = payment.createdAt;
        return payment;
    }

    // ---------------------------------------------------------------- transitions

    /** L'emetteur a accorde le hold : les fonds sont reserves, rien n'est encore debite. */
    public void markAuthorized(String pspReference, String networkTransactionId,
                               String threeDsOutcome, int holdValidityDays) {
        transitionTo(PaymentStatus.AUTHORIZED);
        this.pspReference = pspReference;
        this.networkTransactionId = networkTransactionId;
        this.threeDsOutcome = threeDsOutcome;
        this.authorizationExpiresAt = Instant.now().plus(holdValidityDays, ChronoUnit.DAYS);
        touch();
    }

    /** Refus de l'emetteur : decision metier definitive, on ne retente pas a l'identique. */
    public void markDeclined(String code, String reason) {
        transitionTo(PaymentStatus.DECLINED);
        this.failureCode = code;
        this.failureReason = reason;
        touch();
    }

    /** Echec technique (timeout, PSP down) : rejouable, contrairement a un refus. */
    public void markFailed(String code, String reason) {
        transitionTo(PaymentStatus.FAILED);
        this.failureCode = code;
        this.failureReason = reason;
        touch();
    }

    /**
     * Capture totale ou partielle. Cas hotelier courant : le client part un jour plus
     * tot, on capture moins que ce qu'on avait autorise.
     */
    public void capture(Money amount) {
        Money available = capturableAmount();
        if (amount.isGreaterThan(available)) {
            throw new PaymentException.AmountExceeded("Capture", amount, available);
        }
        long nextCaptured = Math.addExact(this.capturedAmount, amount.minorUnits());
        PaymentStatus target = nextCaptured >= this.authorizedAmount
                ? PaymentStatus.CAPTURED
                : PaymentStatus.PARTIALLY_CAPTURED;
        transitionTo(target);
        this.capturedAmount = nextCaptured;
        touch();
    }

    /** Remboursement : seul ce qui a ete reellement capture peut etre rembourse. */
    public void refund(Money amount) {
        Money available = refundableAmount();
        if (amount.isGreaterThan(available)) {
            throw new PaymentException.AmountExceeded("Remboursement", amount, available);
        }
        long nextRefunded = Math.addExact(this.refundedAmount, amount.minorUnits());
        PaymentStatus target = nextRefunded >= this.capturedAmount
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED;
        transitionTo(target);
        this.refundedAmount = nextRefunded;
        touch();
    }

    /** Annulation avant capture : libere le hold, n'apparait pas sur le releve du client. */
    public void cancel() {
        transitionTo(PaymentStatus.CANCELLED);
        touch();
    }

    /** Le hold est tombe sans capture : il faudra re-autoriser pour encaisser. */
    public void expire() {
        transitionTo(PaymentStatus.EXPIRED);
        touch();
    }

    // ---------------------------------------------------------------- calculs

    public Money capturableAmount() {
        return new Money(this.authorizedAmount - this.capturedAmount, currency());
    }

    public Money refundableAmount() {
        return new Money(this.capturedAmount - this.refundedAmount, currency());
    }

    public boolean isAuthorizationExpired(Instant now) {
        return authorizationExpiresAt != null
                && now.isAfter(authorizationExpiresAt)
                && !status.hasCapturedFunds();
    }

    private void transitionTo(PaymentStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new PaymentException.InvalidState(this.id, this.status, target);
        }
        this.status = target;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ---------------------------------------------------------------- accesseurs

    public UUID id() {
        return id;
    }

    public String reservationId() {
        return reservationId;
    }

    public String hotelId() {
        return hotelId;
    }

    public Currency currency() {
        return Currency.getInstance(currencyCode);
    }

    public Money authorizedAmount() {
        return new Money(authorizedAmount, currency());
    }

    public Money capturedAmount() {
        return new Money(capturedAmount, currency());
    }

    public Money refundedAmount() {
        return new Money(refundedAmount, currency());
    }

    public PaymentStatus status() {
        return status;
    }

    public CardReference card() {
        return card;
    }

    public String pspReference() {
        return pspReference;
    }

    public String networkTransactionId() {
        return networkTransactionId;
    }

    public String threeDsOutcome() {
        return threeDsOutcome;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant authorizationExpiresAt() {
        return authorizationExpiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return "Payment[" + id + " " + status + " " + authorizedAmount() + " hotel=" + hotelId + "]";
    }
}
