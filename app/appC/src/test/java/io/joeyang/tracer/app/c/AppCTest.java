package io.joeyang.tracer.app.c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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

    @Test void main0PrintsResultAndReturnsZero() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = AppC.main0(new String[] {"250"}, new PrintStream(outBytes), new PrintStream(errBytes));
        assertEquals(0, code);
        assertEquals(
                "{\"amount\":\"$2.50\",\"label\":\"total   \"}" + System.lineSeparator(), outBytes.toString());
        assertEquals("", errBytes.toString());
    }

    @Test void main0ReportsBadArgumentOnStderr() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = AppC.main0(new String[] {"ten"}, new PrintStream(outBytes), new PrintStream(errBytes));
        assertEquals(2, code);
        assertEquals("", outBytes.toString());
        assertEquals(
                "appC: expected a whole number of cents, got 'ten'" + System.lineSeparator(), errBytes.toString());
    }
}
