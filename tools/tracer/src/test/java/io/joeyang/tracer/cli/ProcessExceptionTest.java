package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessExceptionTest {

    @Test void ioFailedWrapsTheCauseAndIsNotAStartFailure() {
        IOException cause = new IOException("broken pipe");
        ProcessException e = ProcessException.ioFailed(List.of("sh", "-c", "echo hi"), cause);
        assertEquals("reading output of 'sh' failed: broken pipe", e.getMessage());
        assertSame(cause, e.getCause());
        assertFalse(e.startFailed());
    }

    @Test void timeoutFormatsSubSecondDurationsInMilliseconds() {
        ProcessException e = ProcessException.timeout(List.of("sh"), Duration.ofMillis(300));
        assertEquals("'sh' did not finish within 300ms", e.getMessage());
    }

    @Test void timeoutFormatsWholeSecondsWithoutADecimalPoint() {
        ProcessException e = ProcessException.timeout(List.of("sh"), Duration.ofSeconds(10));
        assertEquals("'sh' did not finish within 10s", e.getMessage());
    }

    @Test void timeoutFormatsFractionalSecondsWithOneDecimalPlace() {
        ProcessException e = ProcessException.timeout(List.of("sh"), Duration.ofMillis(1500));
        assertEquals("'sh' did not finish within 1.5s", e.getMessage());
    }
}
