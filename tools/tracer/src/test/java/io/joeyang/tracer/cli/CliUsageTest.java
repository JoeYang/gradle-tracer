package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CliUsageTest {
    @Test void usagePrintsTheProblemAndUsageTextAndReturnsExitUsage() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Cli.usage(new PrintStream(err, true, StandardCharsets.UTF_8), "no arguments");
        String text = err.toString(StandardCharsets.UTF_8);
        assertEquals(Cli.EXIT_USAGE, code);
        assertTrue(text.contains("no arguments"), text);
        assertTrue(text.contains("--from"), text);
        assertTrue(text.contains("--task"), text);
    }
}
