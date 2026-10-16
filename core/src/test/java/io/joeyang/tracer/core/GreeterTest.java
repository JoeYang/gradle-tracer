package io.joeyang.tracer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GreeterTest {
    @Test void greetPadsTheLabelToTheColumn() { assertEquals("Hello,    Ada!", new Greeter(10).greet("Ada")); }
    @Test void greetWithZeroColumnHasNoPadding() { assertEquals("Hello,Ada!", new Greeter(0).greet("Ada")); }
    @Test void greetRejectsNullName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new Greeter(1).greet(null));
        assertEquals("name must not be blank", ex.getMessage());
    }
    @Test void greetRejectsBlankName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new Greeter(1).greet("  "));
        assertEquals("name must not be blank", ex.getMessage());
    }
    @Test void rejectsNegativeColumn() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> new Greeter(-1));
        assertEquals("column must be >= 0, got -1", ex.getMessage());
    }
}
