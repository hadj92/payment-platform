package com.hospitality.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("les montants sont exacts : aucune erreur d'arrondi binaire")
        // La demonstration de pourquoi on n'utilise pas de double : en flottant,
        // 0.1 + 0.2 vaut 0.30000000000000004. Repete sur un volume de transactions,
        // l'ecart devient une anomalie de reconciliation que la Finance remonte.
    void arithmeticIsExact() {
        Money ten = Money.of(10, "EUR");
        Money twenty = Money.of(20, "EUR");

        assertThat(ten.plus(twenty).minorUnits()).isEqualTo(30L);
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);
    }

    @Test
    @DisplayName("melanger deux devises est un bug, pas un cas a arrondir")
    void rejectsCurrencyMismatch() {
        Money euros = Money.of(1_000, "EUR");
        Money dollars = Money.of(1_000, "USD");

        assertThatThrownBy(() -> euros.plus(dollars))
                .isInstanceOf(Money.CurrencyMismatchException.class)
                .hasMessageContaining("EUR")
                .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("un montant negatif est rejete a la construction")
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> Money.of(-1, "EUR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("la conversion decimale respecte le nombre de decimales de la devise")
    void convertsToDecimalUsingCurrencyScale() {
        assertThat(Money.of(1_050, "EUR").toDecimal()).isEqualByComparingTo(new BigDecimal("10.50"));
        // Le yen n'a pas de sous-unite : 1050 JPY, ce sont bien 1050 yens.
        assertThat(Money.of(1_050, "JPY").toDecimal()).isEqualByComparingTo(new BigDecimal("1050"));
    }

    @Test
    @DisplayName("un debordement de long leve une exception plutot que de boucler")
    void detectsOverflow() {
        Money huge = Money.of(Long.MAX_VALUE, "EUR");

        // Math.addExact plutot que + : un debordement silencieux produirait un montant
        // negatif ou absurde, et le CHECK en base rejetterait l'ecriture sans qu'on
        // comprenne pourquoi.
        assertThatThrownBy(() -> huge.plus(Money.of(1, "EUR")))
                .isInstanceOf(ArithmeticException.class);
    }
}
