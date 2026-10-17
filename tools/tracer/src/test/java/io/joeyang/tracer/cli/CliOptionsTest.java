package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliOptionsTest {
    static Cli.Options parse(String... args) throws UsageException {
        return Cli.Options.parse(args);
    }

    @Test void defaultsForARange() throws Exception {
        Cli.Options o = parse("appA", "--from", "x", "--to", "y");
        assertEquals(":app:appA:compileJava", o.taskPath());
        assertEquals("//app/appA:appA", o.bazelTarget());
        assertTrue(o.bazel());
        assertFalse(o.offline() || o.keepWorktrees() || o.tsv());
        assertNull(o.tsvOut());
        assertNull(o.at());
        assertEquals(10, o.timeout().toMinutes());
    }

    @Test void parsesEveryFlag() throws Exception {
        Cli.Options o = parse("appB", "--from", "x", "--to", "y", "--task", "fingerprint", "--no-bazel", "--offline",
                "--keep-worktrees", "--tsv-out", "rows.tsv", "--timeout-minutes", "3");
        assertEquals(":app:appB:fingerprint", o.taskPath());
        assertFalse(o.bazel());
        assertTrue(o.offline() && o.keepWorktrees());
        assertEquals(Path.of("rows.tsv"), o.tsvOut());
        assertEquals(3, o.timeout().toMinutes());
    }

    @Test void atModeNeedsNoRange() throws Exception {
        Cli.Options o = parse("appC", "--at", "HEAD", "--tsv");
        assertEquals("HEAD", o.at());
        assertNull(o.from());
        assertTrue(o.tsv());
    }

    @Test void repeatedBooleanFlagsAreHarmless() throws Exception {
        assertFalse(parse("appA", "--at", "z", "--no-bazel", "--no-bazel").bazel());
    }

    @Test void rejectsMissingApp() { assertThrows(UsageException.class, () -> parse()); }
    @Test void rejectsFlagInAppPosition() { assertThrows(UsageException.class, () -> parse("--from", "x", "--to", "y")); }
    @Test void rejectsMissingTo() { assertThrows(UsageException.class, () -> parse("appA", "--from", "x")); }
    @Test void rejectsAtTogetherWithRange() { assertThrows(UsageException.class, () -> parse("appA", "--from", "x", "--to", "y", "--at", "z")); }
    @Test void rejectsUnknownFlag() { assertThrows(UsageException.class, () -> parse("appA", "--at", "z", "--bogus")); }
    @Test void rejectsNonNumericTimeout() { assertThrows(UsageException.class, () -> parse("appA", "--at", "z", "--timeout-minutes", "soon")); }
    @Test void rejectsFlagWithoutValue() { assertThrows(UsageException.class, () -> parse("appA", "--at")); }

    // Amendment (code review): DefaultProcessRunner now throws an unchecked exception for a non-positive
    // timeout, so the CLI must reject it as a usage error before it ever reaches the runner.
    @Test void rejectsZeroTimeout() {
        UsageException e = assertThrows(UsageException.class, () -> parse("appA", "--at", "z", "--timeout-minutes", "0"));
        assertTrue(e.getMessage().contains("--timeout-minutes must be a positive integer, got 0"), e.getMessage());
    }

    @Test void rejectsNegativeTimeout() {
        UsageException e = assertThrows(UsageException.class, () -> parse("appA", "--at", "z", "--timeout-minutes", "-5"));
        assertTrue(e.getMessage().contains("--timeout-minutes must be a positive integer, got -5"), e.getMessage());
    }

    @Test void rejectsTimeoutThatOverflowsDuration() {
        assertThrows(UsageException.class, () -> parse("appA", "--at", "z", "--timeout-minutes", String.valueOf(Long.MAX_VALUE)));
    }

    @Test void usageMentionsEveryFlag() {
        for (String flag : new String[] {"--from", "--to", "--at", "--task", "--no-bazel", "--offline", "--keep-worktrees", "--tsv", "--tsv-out", "--timeout-minutes"}) {
            assertTrue(Cli.USAGE.contains(flag), flag);
        }
    }

    // Amendment (code review): on Linux the JVM ignores $TMPDIR (java.io.tmpdir is always /tmp), so the
    // default scratch root must consult the environment variable explicitly to keep the documented
    // $TMPDIR/gradle-tracer behaviour true.
    @Test void defaultScratchRootPrefersTmpdirEnvWhenSet() {
        Path root = Cli.defaultScratchRoot(name -> "TMPDIR".equals(name) ? "/custom/tmp" : null, () -> "/fallback");
        assertEquals(Path.of("/custom/tmp", "gradle-tracer"), root);
    }

    @Test void defaultScratchRootFallsBackToJavaIoTmpdirWhenTmpdirUnset() {
        Path root = Cli.defaultScratchRoot(name -> null, () -> "/fallback");
        assertEquals(Path.of("/fallback", "gradle-tracer"), root);
    }

    @Test void defaultScratchRootFallsBackToJavaIoTmpdirWhenTmpdirBlank() {
        Path root = Cli.defaultScratchRoot(name -> "", () -> "/fallback");
        assertEquals(Path.of("/fallback", "gradle-tracer"), root);
    }
}
