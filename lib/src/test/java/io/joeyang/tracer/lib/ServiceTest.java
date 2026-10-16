package io.joeyang.tracer.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ServiceTest {
    @Test void describeCapitalisesAndTrims() { assertEquals("Hello,  Grace!", new Service().describe("  grace ")); }
    @Test void describeRejectsNull() { assertThrows(IllegalArgumentException.class, () -> new Service().describe(null)); }
    @Test void describeRejectsBlank() { assertThrows(IllegalArgumentException.class, () -> new Service().describe(" ")); }
    @Test void greeterIsExposedAndStable() {
        Service s = new Service();
        assertSame(s.greeter(), s.greeter());
        assertEquals("Hello,  Bob!", s.greeter().greet("Bob"));
    }
}
