package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultProcessRunnerTest {
    final ProcessRunner runner = new DefaultProcessRunner();
    @TempDir Path tmp;

    @Test void capturesExitCodeStdoutAndStderr() throws Exception {
        var r = runner.run(tmp, Duration.ofSeconds(10), List.of("sh", "-c", "echo out; echo err 1>&2; exit 3"));
        assertEquals(3, r.exitCode());
        assertEquals("out\n", r.stdout());
        assertEquals("err\n", r.stderr());
    }

    @Test void runsInTheGivenDirectory() throws Exception {
        var r = runner.run(tmp, Duration.ofSeconds(10), List.of("pwd"));
        assertEquals(tmp.toRealPath().toString(), r.stdout().strip());
    }

    @Test void missingBinaryCannotStart() {
        ProcessException e = assertThrows(ProcessException.class,
                () -> runner.run(tmp, Duration.ofSeconds(10), List.of("definitely-not-a-binary-xyz", "--version")));
        assertTrue(e.getMessage().contains("cannot start 'definitely-not-a-binary-xyz'"), e.getMessage());
    }

    @Test void timeoutKillsTheProcessAndReportsIt() {
        long start = System.nanoTime();
        ProcessException e = assertThrows(ProcessException.class,
                () -> runner.run(tmp, Duration.ofMillis(300), List.of("sh", "-c", "sleep 30")));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
        assertTrue(elapsedMs < 5_000, "took " + elapsedMs + "ms; the child must be killed, not awaited");
    }

    @Test void largeOutputDoesNotDeadlock() throws Exception {
        var r = runner.run(tmp, Duration.ofSeconds(30), List.of("sh", "-c", "head -c 3000000 /dev/zero | tr '\\0' x; echo e 1>&2"));
        assertEquals(0, r.exitCode());
        assertEquals(3_000_000, r.stdout().length());
        assertEquals("e\n", r.stderr());
    }

    @Test void nullCwdIsRejected() {
        assertThrows(NullPointerException.class,
                () -> runner.run(null, Duration.ofSeconds(1), List.of("pwd")));
    }

    @Test void nullTimeoutIsRejected() {
        assertThrows(NullPointerException.class,
                () -> runner.run(tmp, null, List.of("pwd")));
    }

    @Test void nullCommandIsRejected() {
        assertThrows(NullPointerException.class,
                () -> runner.run(tmp, Duration.ofSeconds(1), null));
    }

    @Test void emptyCommandIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(tmp, Duration.ofSeconds(1), List.of()));
    }

    @Test void nonPositiveTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(tmp, Duration.ZERO, List.of("pwd")));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(tmp, Duration.ofSeconds(-1), List.of("pwd")));
    }

    @Test void aBackgroundOrphanDoesNotExtendTheWaitPastTheDeadline() {
        long start = System.nanoTime();
        ProcessException e = assertThrows(ProcessException.class,
                () -> runner.run(tmp, Duration.ofSeconds(2),
                        List.of("sh", "-c", "sleep 4 & sleep 0.5; echo hi")));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
        assertTrue(elapsedMs < 3_500,
                "took " + elapsedMs + "ms; a backgrounded grandchild holding the pipe open must not extend the wait to 4s");
    }

    @Test void hugeTimeoutDoesNotOverflow() throws Exception {
        var r = runner.run(tmp, Duration.ofSeconds(Long.MAX_VALUE), List.of("pwd"));
        assertEquals(0, r.exitCode());
    }

    @Test void commandReadingStdinReturnsPromptlyBecauseStdinIsClosed() throws Exception {
        long start = System.nanoTime();
        var r = runner.run(tmp, Duration.ofSeconds(5), List.of("cat"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(0, r.exitCode());
        assertEquals("", r.stdout());
        assertTrue(elapsedMs < 2_000, "took " + elapsedMs + "ms; cat must see immediate EOF on stdin, not hang");
    }
}
