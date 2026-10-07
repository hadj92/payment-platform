package com.hospitality.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Evenement metier en attente de publication (pattern <b>Transactional Outbox</b>).
 *
 * <p><b>Le probleme :</b> apres une autorisation reussie, il faut (1) persister le
 * paiement et (2) publier {@code PaymentAuthorized} pour que la reservation se
 * confirme. Ces deux actions visent deux systemes differents -- une base et un broker --
 * et il n'existe pas de transaction commune fiable entre les deux. Si on publie puis
 * que la transaction echoue, on annonce un paiement qui n'existe pas. Si on commite
 * puis que la publication echoue, la reservation reste bloquee alors que le client est
 * autorise. Le 2PC/XA resoudrait theoriquement le probleme, mais au prix d'une
 * disponibilite degradee et d'un couplage que personne ne veut sur un chemin critique.</p>
 *
 * <p><b>La solution :</b> on ecrit l'evenement dans <i>la meme transaction</i> que le
 * paiement, dans cette table. Un publieur separe lit les {@code PENDING} et pousse vers
 * SNS/EventBridge/SQS. Soit les deux ecritures sont commitees, soit aucune : l'atomicite
 * est garantie par la base.</p>
 *
 * <p><b>La contrepartie :</b> la livraison est <i>at-least-once</i> -- un publieur qui
 * meurt entre l'envoi et le marquage republiera. Tout consommateur doit donc etre
 * idempotent, en se basant sur {@link #id()} comme identifiant de deduplication.</p>
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public enum Status {
        PENDING, PUBLISHED, FAILED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 32)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    /** Nom d'evenement stable : c'est un contrat public, il se versionne. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload", nullable = false, length = 4000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = Status.PENDING;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public static OutboxEvent forPayment(Payment payment, String eventType, String payload) {
        return new OutboxEvent("Payment", payment.id().toString(), eventType, payload);
    }

    public void markPublished() {
        this.status = Status.PUBLISHED;
        this.publishedAt = Instant.now();
        this.attempts++;
    }

    /**
     * Echec de publication. Au-dela du plafond on bascule en FAILED : l'evenement sort
     * de la boucle de rejeu et part en alerte, exactement comme une DLQ. Rejouer
     * indefiniment un evenement empoisonne bloquerait les suivants.
     */
    public void markFailed(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (this.attempts >= maxAttempts) {
            this.status = Status.FAILED;
        }
    }

    public UUID id() {
        return id;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public String payload() {
        return payload;
    }

    public Status status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public String lastError() {
        return lastError;
    }
}
