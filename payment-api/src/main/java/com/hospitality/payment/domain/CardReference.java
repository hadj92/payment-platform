package com.hospitality.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * Reference non sensible a un moyen de paiement.
 *
 * <p><b>Ce qui n'est PAS ici, et c'est volontaire :</b> le PAN complet et le CVV.
 * Le formulaire de carte est servi par le PSP (hosted fields / iframe) : le numero
 * va du navigateur du client directement au PSP, qui nous renvoie un {@code token}.
 * Notre VPC ne voit jamais un numero de carte.</p>
 *
 * <p>Consequence PCI DSS : on reste en SAQ A / A-EP (~30 a 100 exigences) au lieu de
 * SAQ D (~300 exigences + audit QSA lourd). C'est la decision d'architecture la plus
 * rentable de toute la plateforme.</p>
 *
 * <p>Le BIN et les 4 derniers chiffres sont conserves car PCI DSS les autorise
 * explicitement a l'affichage (req. 3.4) et qu'ils sont indispensables : le BIN sert
 * au routage et au scoring de risque, les 4 derniers a l'identification par le client
 * et par le support.</p>
 */
@Embeddable
public class CardReference {

    /** Token opaque emis par le PSP. Sans valeur exploitable hors du vault du PSP. */
    @Column(name = "payment_method_token", nullable = false, length = 128)
    private String token;

    @Column(name = "card_brand", length = 20)
    private String brand;

    /** 6 ou 8 premiers chiffres : identifie la banque emettrice, le pays, le type de carte. */
    @Column(name = "card_bin", length = 8)
    private String bin;

    @Column(name = "card_last4", length = 4)
    private String last4;

    @Column(name = "card_expiry", length = 7)
    private String expiryYearMonth;

    /** Requis par JPA. */
    protected CardReference() {
    }

    public CardReference(String token, String brand, String bin, String last4, String expiryYearMonth) {
        this.token = Objects.requireNonNull(token, "token");
        this.brand = brand;
        this.bin = bin;
        this.last4 = last4;
        this.expiryYearMonth = expiryYearMonth;
    }

    public String token() {
        return token;
    }

    public String brand() {
        return brand;
    }

    public String bin() {
        return bin;
    }

    public String last4() {
        return last4;
    }

    public String expiryYearMonth() {
        return expiryYearMonth;
    }

    /** Format impose par PCI DSS pour tout affichage : BIN + masque + 4 derniers. */
    public String masked() {
        String safeBin = bin == null ? "" : bin;
        String safeLast4 = last4 == null ? "" : last4;
        int stars = Math.max(0, 16 - safeBin.length() - safeLast4.length());
        return safeBin + "*".repeat(stars) + safeLast4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CardReference other)) {
            return false;
        }
        return Objects.equals(token, other.token);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token);
    }

    @Override
    public String toString() {
        // Surcharge deliberee : un toString() naif finit dans un log ou une stack trace,
        // et c'est la premiere cause de fuite de donnees carte en pratique.
        return "CardReference[" + brand + " " + masked() + "]";
    }
}
