package io.joeyang.tracer.app.a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class AppATest {
    @Test void defaultsToWorld() {
        assertEquals("Hello,  World!" + System.lineSeparator() + "Hello,  world!", AppA.run(new String[0]));
    }
    @Test void usesFirstArgument() {
        assertEquals("Hello,  Ada!" + System.lineSeparator() + "Hello,  ada!", AppA.run(new String[] {"ada"}));
    }
    @Test void stripsSurroundingWhitespaceConsistently() {
        assertEquals("Hello,  Ada!" + System.lineSeparator() + "Hello,  ada!", AppA.run(new String[] {" ada "}));
    }
    @Test void rejectsBlankArgument() {
        assertEquals(
                "name must not be blank",
                assertThrows(IllegalArgumentException.class, () -> AppA.run(new String[] {"  "})).getMessage());
    }
    @Test void rejectsExtraArguments() {
        assertEquals(
                "expected at most one argument, got 2",
                assertThrows(IllegalArgumentException.class, () -> AppA.run(new String[] {"a", "b"})).getMessage());
    }

    @Test void main0PrintsResultAndReturnsZero() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = AppA.main0(new String[] {"ada"}, new PrintStream(outBytes), new PrintStream(errBytes));
        assertEquals(0, code);
        assertEquals(
                "Hello,  Ada!" + System.lineSeparator() + "Hello,  ada!" + System.lineSeparator(),
                outBytes.toString());
        assertEquals("", errBytes.toString());
    }

    @Test void main0ReportsBadArgumentOnStderr() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = AppA.main0(new String[] {"  "}, new PrintStream(outBytes), new PrintStream(errBytes));
        assertEquals(2, code);
        assertEquals("", outBytes.toString());
        assertEquals("appA: name must not be blank" + System.lineSeparator(), errBytes.toString());
    }
}
