package com.hospitality.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Trace d'une requete mutante deja recue, indexee par la cle d'idempotence fournie
 * par l'appelant.
 *
 * <p><b>Le probleme resolu :</b> le client envoie une autorisation, le reseau coupe
 * avant la reponse. Le client ne sait pas si le paiement est passe. Il retente. Sans
 * idempotence, le voyageur est debite deux fois -- et un double debit sur une carte,
 * c'est un incident client, un chargeback et un ticket de support.</p>
 *
 * <p><b>Le mecanisme :</b> l'appelant genere un identifiant et l'envoie dans l'en-tete
 * {@code Idempotency-Key}. On l'insere en base avec la <b>cle primaire sur la cle
 * d'idempotence</b> : c'est l'insertion elle-meme qui arbitre la course. Un
 * {@code SELECT} suivi d'un {@code INSERT} laisserait une fenetre entre les deux, et
 * sous charge cette fenetre finit toujours par etre exploitee.</p>
 *
 * <p><b>Les trois etats possibles</b> a l'arrivee d'une requete portant une cle deja vue :
 * <ul>
 *   <li><i>En cours</i> ({@code responseStatus == 0}) : la premiere requete appelle
 *       encore le PSP. On repond <b>409 Conflict</b> -- surtout pas une tentative en
 *       parallele, qui autoriserait deux fois.</li>
 *   <li><i>Terminee, meme corps</i> : on rejoue la reponse memorisee, meme code HTTP,
 *       meme contenu. Le client ne voit aucune difference avec la premiere fois.</li>
 *   <li><i>Terminee, corps different</i> : erreur d'integration cote appelant. On refuse
 *       en 422 plutot que de renvoyer la reponse d'une autre operation.</li>
 * </ul>
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    /** Fenetre de rejeu. Au-dela, la cle est purgee : retenter des jours plus tard est
     *  une nouvelle operation, pas un rejeu. */
    public static final int RETENTION_HOURS = 24;

    /** Convention interne : 0 signifie "traitement en cours, pas encore de reponse". */
    private static final int IN_PROGRESS = 0;

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String key;

    /** SHA-256 du corps de la requete : detecte la reutilisation de cle sur une autre requete. */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", length = 4000)
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    /** Reserve la cle avant d'appeler le PSP : c'est cette insertion qui exclut les doublons. */
    public static IdempotencyRecord reserve(String key, String requestHash, UUID paymentId) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.key = key;
        record.requestHash = requestHash;
        record.paymentId = paymentId;
        record.responseStatus = IN_PROGRESS;
        record.createdAt = Instant.now();
        record.expiresAt = record.createdAt.plus(RETENTION_HOURS, ChronoUnit.HOURS);
        return record;
    }

    /** Memorise la reponse definitive, qui sera rejouee a l'identique en cas de retry. */
    public void complete(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    public boolean isInProgress() {
        return this.responseStatus == IN_PROGRESS;
    }

    /** Vrai si la requete rejouee est bien la meme que celle d'origine. */
    public boolean matches(String otherRequestHash) {
        return this.requestHash.equals(otherRequestHash);
    }

    public String key() {
        return key;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public int responseStatus() {
        return responseStatus;
    }

    public String responseBody() {
        return responseBody;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
