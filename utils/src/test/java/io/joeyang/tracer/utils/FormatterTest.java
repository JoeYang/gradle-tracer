package io.joeyang.tracer.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class FormatterTest {
    @Test void moneyFormatsDollarsAndCents() { assertEquals("$12.34", Formatter.money(1234)); }
    @Test void moneyPadsSingleDigitCents() { assertEquals("$0.05", Formatter.money(5)); }
    @Test void moneyZero() { assertEquals("$0.00", Formatter.money(0)); }
    @Test void moneyNegativeKeepsSignInFront() { assertEquals("-$7.50", Formatter.money(-750)); }
    @Test void moneyNegativeSingleDigitCents() { assertEquals("-$0.05", Formatter.money(-5)); }
    @Test void moneyLongMaxValue() { assertEquals("$92233720368547758.07", Formatter.money(Long.MAX_VALUE)); }

    @Test void moneyRejectsLongMinValue() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Formatter.money(Long.MIN_VALUE));
        assertEquals("cents must be greater than Long.MIN_VALUE", ex.getMessage());
    }

    @Test void moneyIsLocaleIndependent() {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
        try {
            assertEquals("$0.05", Formatter.money(5));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    @Test void padAddsSpacesToWidth() { assertEquals("ab   ", Formatter.pad("ab", 5)); }
    @Test void padLeavesEqualWidthAlone() { assertEquals("abcde", Formatter.pad("abcde", 5)); }
    @Test void padLeavesLongerTextAlone() { assertEquals("abcdef", Formatter.pad("abcdef", 5)); }
    @Test void padZeroWidth() { assertEquals("", Formatter.pad("", 0)); }

    @Test void padRejectsNull() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Formatter.pad(null, 3));
        assertEquals("text must not be null", ex.getMessage());
    }

    @Test void padRejectsNegativeWidth() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> Formatter.pad("a", -1));
        assertEquals("width must be >= 0, got -1", ex.getMessage());
    }
}
