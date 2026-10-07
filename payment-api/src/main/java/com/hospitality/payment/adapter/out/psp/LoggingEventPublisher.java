package com.hospitality.payment.adapter.out.psp;

import com.hospitality.payment.domain.OutboxEvent;
import com.hospitality.payment.port.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementation locale du bus d'evenements : journalise au lieu de publier.
 *
 * <p>En production, cette classe est remplacee par un adaptateur SNS ou EventBridge
 * (profil {@code aws}). C'est tout l'interet d'avoir defini un port : le changement de
 * transport ne touche ni le domaine, ni le publieur d'outbox, ni les tests.</p>
 */
@Component
public class LoggingEventPublisher implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("[event] {} agregat={} payload={}", event.eventType(), event.aggregateId(), event.payload());
    }
}
