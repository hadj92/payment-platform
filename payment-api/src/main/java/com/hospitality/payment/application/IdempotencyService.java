package com.hospitality.payment.application;

import com.hospitality.payment.adapter.out.persistence.IdempotencyRecordRepository;
import com.hospitality.payment.domain.IdempotencyRecord;
import com.hospitality.payment.domain.PaymentException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion des cles d'idempotence : reservation, rejeu et purge.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository repository;

    public IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /** Empreinte stable du corps de la requete, pour detecter une cle reutilisee ailleurs. */
    public String hash(String canonicalRequestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonicalRequestBody.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 est garanti par la specification de la JVM : inatteignable en pratique.
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /**
     * Cherche une requete deja traitee avec cette cle.
     *
     * @return la reponse a rejouer, ou {@link Optional#empty()} si la cle est inconnue
     *         et que le traitement peut commencer
     * @throws PaymentException.RequestInProgress    si une requete identique est en cours
     * @throws PaymentException.IdempotencyConflict  si la cle a servi pour un autre corps
     */
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findReplay(String key, String requestHash) {
        Optional<IdempotencyRecord> existing = repository.findById(key);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord record = existing.get();

        if (!record.matches(requestHash)) {
            // Cle recyclee sur une autre requete : on refuse bruyamment plutot que de
            // renvoyer la reponse d'une operation qui n'a rien a voir.
            throw new PaymentException.IdempotencyConflict(key);
        }
        if (record.isInProgress()) {
            // La premiere requete appelle encore le PSP. Laisser passer celle-ci
            // produirait deux autorisations pour un seul paiement.
            throw new PaymentException.RequestInProgress(key);
        }
        log.info("Rejeu idempotent de la cle {} -> HTTP {}", key, record.responseStatus());
        return Optional.of(record);
    }

    /**
     * Reserve la cle avant l'appel au PSP, dans sa <b>propre transaction</b>.
     *
     * <p>{@code REQUIRES_NEW} est delibere : la reservation doit etre visible des autres
     * requetes immediatement, sans attendre la fin du traitement complet. Si elle
     * participait a la transaction englobante, la cle ne serait commitee qu'a la fin --
     * trop tard pour bloquer une requete concurrente arrivee entre-temps.</p>
     *
     * @return {@code false} si la cle vient d'etre prise par une requete concurrente
     *         (violation de cle primaire), ce qui signifie qu'on a perdu la course
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReserve(IdempotencyRecord record) {
        if (repository.existsById(record.key())) {
            return false;
        }
        repository.save(record);
        return true;
    }

    /** Memorise la reponse definitive pour les rejeux ulterieurs. */
    @Transactional
    public void complete(String key, int httpStatus, String responseBody) {
        repository.findById(key).ifPresent(record -> {
            record.complete(httpStatus, responseBody);
            repository.save(record);
        });
    }

    /**
     * Libere une cle dont le traitement a echoue techniquement, pour que le client
     * puisse retenter avec la meme cle.
     *
     * <p>Nuance importante : on ne libere que sur echec <b>technique</b>. Un refus de
     * l'emetteur est une reponse definitive, qu'on memorise et qu'on rejoue -- sinon
     * un client en boucle de retry bombarderait le PSP de tentatives deja refusees.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        repository.findById(key).ifPresent(record -> {
            if (record.isInProgress()) {
                repository.delete(record);
                log.warn("Cle d'idempotence {} liberee apres echec technique", key);
            }
        });
    }

    /**
     * Purge horaire des cles expirees.
     *
     * <p>En production multi-instances, un {@code @Scheduled} s'execute sur chaque
     * replica. Pour un simple {@code DELETE} idempotent ce n'est pas grave ; pour tout
     * job avec des effets de bord il faut un verrou distribue (ShedLock) ou un
     * declenchement externe (EventBridge Scheduler vers une seule tache).</p>
     */
    @Scheduled(fixedDelayString = "${idempotency.purge-interval-ms:3600000}")
    @Transactional
    public void purgeExpired() {
        int deleted = repository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("{} cles d'idempotence expirees purgees", deleted);
        }
    }
}
