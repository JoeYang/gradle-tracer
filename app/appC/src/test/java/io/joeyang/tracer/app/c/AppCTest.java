package io.joeyang.tracer.app.c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppCTest {
    @Test void defaultsToTwelveThirtyFour() {
        assertEquals("{\"amount\":\"$12.34\",\"label\":\"total   \"}", AppC.run(new String[0]));
    }
    @Test void usesFirstArgumentAsCents() {
        assertEquals("{\"amount\":\"-$0.99\",\"label\":\"total   \"}", AppC.run(new String[] {"-99"}));
    }
    @Test void rejectsNonNumericArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AppC.run(new String[] {"ten"}));
        assertEquals("expected a whole number of cents, got 'ten'", e.getMessage());
        assertInstanceOf(NumberFormatException.class, e.getCause());
    }
    @Test void rejectsLongMinValue() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> AppC.run(new String[] {"-9223372036854775808"}));
        assertEquals("cents must be greater than Long.MIN_VALUE", e.getMessage());
    }
    @Test void rejectsOverflowingArgument() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> AppC.run(new String[] {"9223372036854775808"}));
        assertEquals("expected a whole number of cents, got '9223372036854775808'", e.getMessage());
    }
    @Test void rejectsEmptyArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AppC.run(new String[] {""}));
        assertEquals("expected a whole number of cents, got ''", e.getMessage());
    }
}
