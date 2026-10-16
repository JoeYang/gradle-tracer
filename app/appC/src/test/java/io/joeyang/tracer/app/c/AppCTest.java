package io.joeyang.tracer.app.c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(e.getMessage().contains("ten"));
    }
}
