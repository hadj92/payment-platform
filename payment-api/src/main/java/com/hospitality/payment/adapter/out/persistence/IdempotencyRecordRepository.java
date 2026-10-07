package com.hospitality.payment.adapter.out.persistence;

import com.hospitality.payment.domain.IdempotencyRecord;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Purge des cles expirees.
     *
     * <p>Equivalent du TTL de DynamoDB, qui ferait ce menage tout seul : si ce store
     * etait en DynamoDB -- un choix tout a fait defendable vu son profil cle/valeur a
     * fort debit -- on activerait un TTL sur {@code expires_at}. En relationnel il
     * faut un job. C'est l'arbitrage assume : on garde l'idempotence dans la meme base
     * que les paiements pour pouvoir l'ecrire dans la meme transaction, et on paie un
     * job de purge en echange.</p>
     */
    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
