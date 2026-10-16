package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProcessRunnerResultTest {
    @Test void okOnlyForExitZero() {
        assertTrue(new ProcessRunner.Result(0, "", "").ok());
        assertFalse(new ProcessRunner.Result(1, "", "").ok());
    }

    @Test void firstErrorLinePrefersStderr() {
        var r = new ProcessRunner.Result(1, "FAILURE: Build failed", "\n  error: cannot find symbol\n");
        assertEquals("error: cannot find symbol", r.firstErrorLine());
    }

    @Test void firstErrorLineFallsBackToStdoutLineMentioningFailure() {
        var r = new ProcessRunner.Result(1, "> Task :x\nFAILURE: Build failed with an exception.\n", "");
        assertEquals("FAILURE: Build failed with an exception.", r.firstErrorLine());
    }

    @Test void firstErrorLineFallsBackToExitCode() {
        assertEquals("exit code 7", new ProcessRunner.Result(7, "", "").firstErrorLine());
    }
}
