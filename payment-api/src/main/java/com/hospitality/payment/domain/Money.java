package com.hospitality.payment.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Montant monetaire exprime en <b>unites mineures</b> (centimes pour l'EUR).
 *
 * <p>Regle non negociable d'un systeme de paiement : on ne represente jamais de l'argent
 * avec un {@code double} ou un {@code float}. 0.1 + 0.2 != 0.3 en binaire, et un ecart
 * d'un centime sur un million de transactions est un ecart de reconciliation que la
 * Finance va remonter. Les PSP (Adyen, Stripe, Worldline) exposent tous leurs montants
 * en unites mineures pour cette raison exacte.</p>
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (minorUnits < 0) {
            throw new IllegalArgumentException("Un montant ne peut pas etre negatif : " + minorUnits);
        }
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, Currency.getInstance(currencyCode));
    }

    public Money plus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.addExact(this.minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        assertSameCurrency(other);
        return new Money(Math.subtractExact(this.minorUnits, other.minorUnits), currency);
    }

    public boolean isGreaterThan(Money other) {
        assertSameCurrency(other);
        return this.minorUnits > other.minorUnits;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    /** Representation lisible pour l'UI et les logs ; jamais utilisee pour calculer. */
    public BigDecimal toDecimal() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new CurrencyMismatchException(this.currency, other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        assertSameCurrency(other);
        return Long.compare(this.minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return toDecimal().toPlainString() + " " + currency.getCurrencyCode();
    }

    /** Melanger deux devises dans un calcul est un bug, pas un cas metier a arrondir. */
    public static class CurrencyMismatchException extends IllegalArgumentException {
        public CurrencyMismatchException(Currency expected, Currency actual) {
            super("Devises incompatibles : attendu " + expected.getCurrencyCode()
                    + ", recu " + actual.getCurrencyCode());
        }
    }
}
