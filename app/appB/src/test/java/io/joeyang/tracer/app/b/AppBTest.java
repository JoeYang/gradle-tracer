package io.joeyang.tracer.app.b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class AppBTest {
    @Test void defaultsToWorld() { assertEquals("Hello,    world!", AppB.run(new String[0])); }
    @Test void usesFirstArgument() { assertEquals("Hello,    Bob!", AppB.run(new String[] {"Bob"})); }
    @Test void rejectsBlankArgument() {
        assertEquals(
                "name must not be blank",
                assertThrows(IllegalArgumentException.class, () -> AppB.run(new String[] {""})).getMessage());
    }

    @Test void main0PrintsResultAndReturnsZero() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = AppB.main0(new String[] {"Bob"}, new PrintStream(outBytes), new PrintStream(errBytes));
        assertEquals(0, code);
        assertEquals("Hello,    Bob!" + System.lineSeparator(), outBytes.toString());
        assertEquals("", errBytes.toString());
    }

    @Test void main0ReportsBadArgumentOnStderr() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = AppB.main0(new String[] {""}, new PrintStream(outBytes), new PrintStream(errBytes));
        assertEquals(2, code);
        assertEquals("", outBytes.toString());
        assertEquals("appB: name must not be blank" + System.lineSeparator(), errBytes.toString());
    }
}
