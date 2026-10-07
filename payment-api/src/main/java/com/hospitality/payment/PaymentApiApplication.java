package com.hospitality.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Service d'autorisation, capture, remboursement et annulation de paiements par carte,
 * concu pour le cycle hotelier : garantie a la reservation, autorisations
 * incrementales pendant le sejour, capture du folio reel au check-out, facturation de
 * no-show en MIT.
 *
 * <p>Projet de demonstration personnel : le prestataire de paiement est simule, aucune
 * donnee carte reelle n'est manipulee et le code n'est affilie a aucun groupe hotelier.</p>
 */
@SpringBootApplication
@EnableScheduling
public class PaymentApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApiApplication.class, args);
    }
}
