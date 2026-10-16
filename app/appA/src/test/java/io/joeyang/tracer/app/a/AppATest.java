package io.joeyang.tracer.app.a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppATest {
    @Test void defaultsToWorld() {
        assertEquals("Hello,  World!" + System.lineSeparator() + "Hello,  world!", AppA.run(new String[0]));
    }
    @Test void usesFirstArgument() {
        assertEquals("Hello,  Ada!" + System.lineSeparator() + "Hello,  ada!", AppA.run(new String[] {"ada"}));
    }
    @Test void rejectsBlankArgument() {
        assertThrows(IllegalArgumentException.class, () -> AppA.run(new String[] {"  "}));
    }
}
