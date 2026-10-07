package com.hospitality.payment.port;

import com.hospitality.payment.domain.OutboxEvent;

/**
 * Port de sortie vers le bus d'evenements.
 *
 * <p>En production, l'implementation pousse vers SNS ou EventBridge : les consommateurs
 * (reservation, CRM, data) s'abonnent chacun a leur file SQS, de sorte qu'un
 * consommateur en panne remplit sa propre file sans impacter les autres.</p>
 *
 * <p>En local et en test, une implementation qui journalise suffit : le comportement
 * qu'on veut verifier -- l'evenement est bien produit dans la meme transaction que le
 * paiement -- ne depend pas du transport.</p>
 */
public interface EventPublisherPort {

    /**
     * @throws RuntimeException si la publication echoue ; l'evenement reste PENDING
     *                          dans l'outbox et sera rejoue.
     */
    void publish(OutboxEvent event);
}
