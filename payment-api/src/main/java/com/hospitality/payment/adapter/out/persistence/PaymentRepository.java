package com.hospitality.payment.adapter.out.persistence;

import com.hospitality.payment.domain.Payment;
import com.hospitality.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Vue du portail hotel : un hotelier ne voit que ses propres paiements. */
    Page<Payment> findByHotelIdOrderByCreatedAtDesc(String hotelId, Pageable pageable);

    Page<Payment> findByHotelIdAndStatusOrderByCreatedAtDesc(String hotelId, PaymentStatus status,
                                                             Pageable pageable);

    List<Payment> findByReservationIdOrderByCreatedAtDesc(String reservationId);

    /**
     * Autorisations arrivees a echeance sans capture : le hold est tombe chez
     * l'emetteur, il faut re-autoriser pour pouvoir encaisser. Un job balaye cette
     * requete et alerte l'hotel avant que le paiement ne devienne irrecuperable.
     */
    @Query("""
            select p from Payment p
            where p.status = :status and p.authorizationExpiresAt < :now
            """)
    List<Payment> findExpiredAuthorizations(@Param("status") PaymentStatus status,
                                            @Param("now") Instant now);

    /**
     * Paiements restes PENDING anormalement longtemps : le processus est probablement
     * mort pendant l'appel au PSP. Il faut interroger le PSP pour savoir si
     * l'autorisation a abouti -- c'est la que se cachent les paiements fantomes, et
     * les clients debites qui n'ont pas de reservation.
     */
    @Query("""
            select p from Payment p
            where p.status = :status and p.createdAt < :threshold
            """)
    List<Payment> findStalePending(@Param("status") PaymentStatus status,
                                   @Param("threshold") Instant threshold);

    /** Alimente le tableau de bord : encaissement net d'un hotel sur une periode. */
    @Query("""
            select coalesce(sum(p.capturedAmount - p.refundedAmount), 0)
            from Payment p
            where p.hotelId = :hotelId and p.createdAt between :from and :to
            """)
    long netCapturedAmount(@Param("hotelId") String hotelId,
                           @Param("from") Instant from,
                           @Param("to") Instant to);
}
