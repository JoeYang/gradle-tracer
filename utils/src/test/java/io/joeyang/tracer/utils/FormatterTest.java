package io.joeyang.tracer.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FormatterTest {
    @Test void moneyFormatsDollarsAndCents() { assertEquals("$12.34", Formatter.money(1234)); }
    @Test void moneyPadsSingleDigitCents() { assertEquals("$0.05", Formatter.money(5)); }
    @Test void moneyZero() { assertEquals("$0.00", Formatter.money(0)); }
    @Test void moneyNegativeKeepsSignInFront() { assertEquals("-$7.50", Formatter.money(-750)); }
    @Test void moneyRejectsLongMinValue() { assertThrows(ArithmeticException.class, () -> Formatter.money(Long.MIN_VALUE)); }

    @Test void padAddsSpacesToWidth() { assertEquals("ab   ", Formatter.pad("ab", 5)); }
    @Test void padLeavesEqualWidthAlone() { assertEquals("abcde", Formatter.pad("abcde", 5)); }
    @Test void padLeavesLongerTextAlone() { assertEquals("abcdef", Formatter.pad("abcdef", 5)); }
    @Test void padZeroWidth() { assertEquals("", Formatter.pad("", 0)); }
    @Test void padRejectsNull() { assertThrows(IllegalArgumentException.class, () -> Formatter.pad(null, 3)); }
    @Test void padRejectsNegativeWidth() { assertThrows(IllegalArgumentException.class, () -> Formatter.pad("a", -1)); }
}
