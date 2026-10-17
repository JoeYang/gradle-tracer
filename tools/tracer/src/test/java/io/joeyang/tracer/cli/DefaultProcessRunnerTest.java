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
        var r = runner.run(tmp, Duration.ofSeconds(30), List.of("sh", "-c",
                "head -c 3000000 /dev/zero | tr '\\0' x; head -c 2000000 /dev/zero | tr '\\0' y 1>&2"));
        assertEquals(0, r.exitCode());
        assertEquals(3_000_000, r.stdout().length());
        assertEquals(2_000_000, r.stderr().length());
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

    @Test void drainsRunOnNamedTracerThreadsRatherThanTheCommonPool() throws Exception {
        Thread caller = new Thread(() -> {
            try {
                runner.run(tmp, Duration.ofSeconds(5), List.of("sh", "-c", "sleep 1"));
            } catch (ProcessException ignored) {
                // only the thread names while in flight matter for this test
            }
        });
        caller.start();
        boolean sawNamedDrainThread = false;
        long pollUntil = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < pollUntil && !sawNamedDrainThread) {
            sawNamedDrainThread = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.getName().startsWith("tracer-stdout") || t.getName().startsWith("tracer-stderr"));
            if (!sawNamedDrainThread) {
                Thread.sleep(20);
            }
        }
        caller.join();
        assertTrue(sawNamedDrainThread,
                "expected a thread named tracer-stdout/tracer-stderr while a run() was draining output");
    }

    @Test void timeoutKillsTheEntireProcessTreeNotJustTheShell() throws Exception {
        String marker = "41.628374"; // unique per process so this test cannot match another test's child
        ProcessException e = assertThrows(ProcessException.class,
                () -> runner.run(tmp, Duration.ofSeconds(1), List.of("sh", "-c", "sleep " + marker + " & wait")));
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
        assertTrue(waitForNoProcessMatching(marker),
                "expected the backgrounded 'sleep " + marker + "' to be killed along with the shell");
    }

    @Test void preInterruptedThreadThrowsInterruptedAndKillsTheChild() throws Exception {
        String marker = "52.194716"; // unique per process so this test cannot match another test's child
        Thread.currentThread().interrupt();
        try {
            ProcessException e = assertThrows(ProcessException.class,
                    () -> runner.run(tmp, Duration.ofSeconds(30), List.of("sleep", marker)));
            assertTrue(e.getMessage().contains("interrupted"), e.getMessage());
        } finally {
            assertTrue(Thread.interrupted(), "expected the interrupt flag to still be set after run() propagated it");
        }
        assertTrue(waitForNoProcessMatching(marker),
                "expected 'sleep " + marker + "' to be killed after the interrupt");
    }

    /** Polls the live process table for a process whose command line contains {@code marker}, up to 10s. */
    private static boolean waitForNoProcessMatching(String marker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        boolean dead;
        do {
            dead = ProcessHandle.allProcesses()
                    .noneMatch(p -> p.info().commandLine().map(cmd -> cmd.contains(marker)).orElse(false));
            if (!dead) {
                Thread.sleep(50);
            }
        } while (!dead && System.nanoTime() < deadline);
        return dead;
    }
}
