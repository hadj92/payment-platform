package com.hospitality.payment.application;

import com.hospitality.payment.adapter.out.persistence.OutboxEventRepository;
import com.hospitality.payment.domain.OutboxEvent;
import com.hospitality.payment.port.EventPublisherPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publieur de l'outbox : lit les evenements PENDING et les pousse sur le bus.
 *
 * <p><b>Pourquoi un composant separe de l'ecriture :</b> le chemin critique du paiement
 * ne doit pas dependre de la disponibilite du broker. Si SNS est indisponible, les
 * autorisations continuent de fonctionner et les evenements s'accumulent ; ils
 * partiront au retablissement. L'inverse -- publier en synchrone dans la requete --
 * ferait d'une panne du bus une panne de l'encaissement.</p>
 *
 * <p><b>La contrepartie, a assumer en entretien :</b> on introduit une latence de
 * publication (ici une seconde au plus) et la livraison est <i>at-least-once</i>. Si le
 * processus meurt entre {@code publish()} et {@code markPublished()}, l'evenement
 * repartira. Les consommateurs doivent donc dedupliquer sur l'identifiant d'evenement.
 * On echange une incoherence possible contre un retard garanti : c'est le bon sens du
 * compromis quand il s'agit d'argent.</p>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository repository;
    private final EventPublisherPort publisher;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxPublisher(OutboxEventRepository repository,
                           EventPublisherPort publisher,
                           MeterRegistry meterRegistry,
                           @Value("${outbox.batch-size:50}") int batchSize,
                           @Value("${outbox.max-attempts:5}") int maxAttempts) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;

        // Deux jauges a alerter : un backlog PENDING qui croit signale un publieur ou un
        // broker en panne ; un compteur FAILED non nul est l'equivalent d'une DLQ qui se
        // remplit et demande une intervention humaine.
        meterRegistry.gauge("payment.outbox.pending", this,
                p -> p.repository.countByStatus(OutboxEvent.Status.PENDING));
        meterRegistry.gauge("payment.outbox.failed", this,
                p -> p.repository.countByStatus(OutboxEvent.Status.FAILED));
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.findByStatusOrderByCreatedAtAsc(
                OutboxEvent.Status.PENDING, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
                event.markPublished();
            } catch (RuntimeException e) {
                event.markFailed(e.getMessage(), maxAttempts);
                if (event.status() == OutboxEvent.Status.FAILED) {
                    // Evenement empoisonne : on le sort de la boucle de rejeu, sinon il
                    // bloquerait indefiniment les evenements suivants du meme lot.
                    log.error("Evenement {} ({}) abandonne apres {} tentatives : {}",
                            event.id(), event.eventType(), event.attempts(), e.getMessage());
                } else {
                    log.warn("Echec de publication de l'evenement {} (tentative {}) : {}",
                            event.id(), event.attempts(), e.getMessage());
                }
            }
        }
        repository.saveAll(batch);
    }
}
