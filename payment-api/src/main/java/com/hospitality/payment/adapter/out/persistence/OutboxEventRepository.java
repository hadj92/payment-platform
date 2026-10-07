package com.hospitality.payment.adapter.out.persistence;

import com.hospitality.payment.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Prochain lot a publier, dans l'ordre de creation.
     *
     * <p>Sur une seule instance, cette requete suffit. Des qu'il y a plusieurs
     * replicas du publieur, deux instances liraient le meme lot et publieraient en
     * double. Deux parades, selon le contexte :</p>
     * <ul>
     *   <li>{@code SELECT ... FOR UPDATE SKIP LOCKED} : chaque instance verrouille son
     *       lot et les autres passent a la suite. Simple, efficace, supporte par
     *       PostgreSQL.</li>
     *   <li>Remplacer le polling par du <b>CDC</b> (Debezium sur le WAL PostgreSQL),
     *       qui supprime a la fois le polling et la concurrence.</li>
     * </ul>
     * <p>La livraison reste at-least-once dans tous les cas : les consommateurs doivent
     * dedupliquer sur l'identifiant d'evenement.</p>
     */
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxEvent.Status status, Pageable pageable);

    /**
     * Compteur expose en metrique. Deux signaux a alerter : une outbox PENDING qui
     * gonfle (publieur en panne), et un compteur FAILED non nul -- l'equivalent
     * fonctionnel d'une DLQ qui se remplit.
     */
    long countByStatus(OutboxEvent.Status status);
}
