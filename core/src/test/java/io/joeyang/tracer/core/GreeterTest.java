package io.joeyang.tracer.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GreeterTest {
    @Test void greetPadsTheLabelToTheColumn() { assertEquals("Hello,    Ada!", new Greeter(10).greet("Ada")); }
    @Test void greetWithZeroColumnHasNoPadding() { assertEquals("Hello,Ada!", new Greeter(0).greet("Ada")); }
    @Test void greetRejectsNullName() { assertThrows(IllegalArgumentException.class, () -> new Greeter(1).greet(null)); }
    @Test void greetRejectsBlankName() { assertThrows(IllegalArgumentException.class, () -> new Greeter(1).greet("  ")); }
    @Test void rejectsNegativeColumn() { assertThrows(IllegalArgumentException.class, () -> new Greeter(-1)); }
}
