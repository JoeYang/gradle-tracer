package io.joeyang.tracer.app.b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppBTest {
    @Test void defaultsToWorld() { assertEquals("Hello,    world!", AppB.run(new String[0])); }
    @Test void usesFirstArgument() { assertEquals("Hello,    Bob!", AppB.run(new String[] {"Bob"})); }
    @Test void rejectsBlankArgument() {
        assertEquals(
                "name must not be blank",
                assertThrows(IllegalArgumentException.class, () -> AppB.run(new String[] {""})).getMessage());
    }
}
