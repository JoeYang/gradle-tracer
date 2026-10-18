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

    static UsageException fails(String... args) {
        return assertThrows(UsageException.class, () -> parse(args));
    }

    @Test void defaultsForARange() throws Exception {
        Cli.Options o = parse("appA", "--from", "x", "--to", "y");
        assertEquals(":app:appA:fingerprint", o.taskPath());
        assertFalse(o.offline() || o.keepWorktrees() || o.tsv());
        assertNull(o.tsvOut());
        assertNull(o.at());
        assertEquals(10, o.timeout().toMinutes());
    }

    @Test void parsesEveryFlag() throws Exception {
        Cli.Options o = parse("appB", "--from", "x", "--to", "y", "--task", "compileJava", "--offline",
                "--keep-worktrees", "--tsv-out", "rows.tsv", "--timeout-minutes", "3");
        assertEquals(":app:appB:compileJava", o.taskPath());
        assertTrue(o.offline() && o.keepWorktrees());
        assertEquals(Path.of("rows.tsv"), o.tsvOut());
        assertEquals(3, o.timeout().toMinutes());
    }

    @Test void atModeNeedsNoRange() throws Exception {
        Cli.Options o = parse("appC", "--at", "HEAD");
        assertEquals("HEAD", o.at());
        assertNull(o.from());
        assertEquals(":app:appC:fingerprint", o.taskPath());
    }

    @Test void atModeStillTakesCompileJava() throws Exception {
        assertEquals(":app:appC:compileJava", parse("appC", "--at", "HEAD", "--task", "compileJava").taskPath());
    }

    @Test void repeatedBooleanFlagsAreHarmless() throws Exception {
        assertTrue(parse("appA", "--from", "x", "--to", "y", "--offline", "--offline").offline());
    }

    @Test void rejectsMissingApp() {
        UsageException e = fails();
        assertTrue(e.getMessage().contains("missing <app>"), e.getMessage());
    }

    @Test void rejectsFlagInAppPosition() {
        UsageException e = fails("--from", "x", "--to", "y");
        assertTrue(e.getMessage().contains("--from"), e.getMessage());
    }

    @Test void rejectsMissingTo() {
        UsageException e = fails("appA", "--from", "x");
        assertTrue(e.getMessage().contains("--to"), e.getMessage());
    }

    @Test void rejectsAtTogetherWithRange() {
        UsageException e = fails("appA", "--from", "x", "--to", "y", "--at", "z");
        assertTrue(e.getMessage().contains("--at"), e.getMessage());
    }

    @Test void rejectsUnknownFlag() {
        UsageException e = fails("appA", "--at", "z", "--bogus");
        assertTrue(e.getMessage().contains("--bogus"), e.getMessage());
    }

    @Test void rejectsNonNumericTimeout() {
        UsageException e = fails("appA", "--at", "z", "--timeout-minutes", "soon");
        assertTrue(e.getMessage().contains("--timeout-minutes needs a whole number, got 'soon'"), e.getMessage());
    }

    @Test void rejectsFlagWithoutValue() {
        UsageException e = fails("appA", "--at");
        assertTrue(e.getMessage().contains("--at needs a value"), e.getMessage());
    }

    // A value-taking option missing its value at the end of the arguments is a usage error naming that
    // option, same as --at above.
    @Test void rejectsFromWithoutValue() {
        UsageException e = fails("appA", "--from");
        assertTrue(e.getMessage().contains("--from needs a value"), e.getMessage());
    }

    @Test void rejectsToWithoutValue() {
        UsageException e = fails("appA", "--to");
        assertTrue(e.getMessage().contains("--to needs a value"), e.getMessage());
    }

    @Test void rejectsTaskWithoutValue() {
        UsageException e = fails("appA", "--task");
        assertTrue(e.getMessage().contains("--task needs a value"), e.getMessage());
    }

    @Test void rejectsTsvOutWithoutValue() {
        UsageException e = fails("appA", "--tsv-out");
        assertTrue(e.getMessage().contains("--tsv-out needs a value"), e.getMessage());
    }

    @Test void rejectsTimeoutMinutesWithoutValue() {
        UsageException e = fails("appA", "--timeout-minutes");
        assertTrue(e.getMessage().contains("--timeout-minutes needs a value"), e.getMessage());
    }

    // DefaultProcessRunner throws an unchecked exception for a non-positive timeout, so the CLI must
    // reject it as a usage error before it ever reaches the runner.
    @Test void rejectsZeroTimeout() {
        UsageException e = fails("appA", "--at", "z", "--timeout-minutes", "0");
        assertTrue(e.getMessage().contains("--timeout-minutes must be a positive integer, got 0"), e.getMessage());
    }

    @Test void rejectsNegativeTimeout() {
        UsageException e = fails("appA", "--at", "z", "--timeout-minutes", "-5");
        assertTrue(e.getMessage().contains("--timeout-minutes must be a positive integer, got -5"), e.getMessage());
    }

    @Test void rejectsTimeoutThatOverflowsDuration() {
        UsageException e = fails("appA", "--at", "z", "--timeout-minutes", String.valueOf(Long.MAX_VALUE));
        assertTrue(e.getMessage().contains("--timeout-minutes is too large, got '" + Long.MAX_VALUE + "'"), e.getMessage());
    }

    @Test void rejectsTimeoutWithTooManyDigitsToFitALong() {
        String huge = "9".repeat(20);
        UsageException e = fails("appA", "--at", "z", "--timeout-minutes", huge);
        assertTrue(e.getMessage().contains("--timeout-minutes is too large, got '" + huge + "'"), e.getMessage());
    }

    @Test void rejectsTimeoutWithNonAsciiDigit() {
        UsageException e = fails("appA", "--at", "z", "--timeout-minutes", "٥");
        assertTrue(e.getMessage().contains("--timeout-minutes needs a whole number, got '٥'"), e.getMessage());
    }

    // --timeout-minutes is exempt from the generic "looks like a flag" check (unlike --from, --to, --at,
    // --task and --tsv-out above), so its value always reaches parseMinutes, which reports a value that
    // merely fails to parse as "needs a whole number", naming the option, rather than "needs a value".
    @Test void rejectsTimeoutValueThatLooksLikeAFlag() {
        UsageException e = fails("appA", "--at", "z", "--timeout-minutes", "--offline");
        assertTrue(e.getMessage().contains("--timeout-minutes needs a whole number, got '--offline'"), e.getMessage());
    }

    @Test void rejectsBlankTimeoutValue() {
        UsageException e = fails("appA", "--at", "z", "--timeout-minutes", "");
        assertTrue(e.getMessage().contains("--timeout-minutes needs a whole number, got ''"), e.getMessage());
    }

    // A negative value with too many digits to fit a long overflows Long.parseLong before the sign is
    // ever inspected, so it is reported as "too large" rather than "must be a positive integer".
    @Test void rejectsNegativeTimeoutWithTooManyDigitsToFitALong() {
        String huge = "-" + "9".repeat(20);
        UsageException e = fails("appA", "--at", "z", "--timeout-minutes", huge);
        assertTrue(e.getMessage().contains("--timeout-minutes is too large, got '" + huge + "'"), e.getMessage());
    }

    @Test void usageMentionsEveryFlag() {
        for (String flag : new String[] {"--from", "--to", "--at", "--task", "--offline", "--keep-worktrees", "--tsv", "--tsv-out", "--timeout-minutes"}) {
            assertTrue(Cli.USAGE.contains(flag), flag);
        }
        assertFalse(Cli.USAGE.toLowerCase().contains("bazel"), Cli.USAGE);
    }

    @Test void noBazelIsAnUnknownOption() {
        UsageException e = fails("appA", "--from", "x", "--to", "y", "--no-bazel");
        assertTrue(e.getMessage().contains("unknown option '--no-bazel'"), e.getMessage());
    }

    // The JVM ignores $TMPDIR on Linux (java.io.tmpdir is always /tmp), so the default scratch root
    // reads it directly to keep the documented $TMPDIR/gradle-tracer behaviour true.
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

    // Child processes such as <worktree>/gradlew run inside the worktree, so a relative root would
    // resolve differently there; resolve it once against the JVM's working directory (the repo root
    // after ./trace's cd).
    @Test void defaultScratchRootIsAbsoluteEvenForARelativeTmpdir() {
        Path root = Cli.defaultScratchRoot(name -> "TMPDIR".equals(name) ? "rel/dir" : null, () -> "/fallback");
        assertTrue(root.isAbsolute(), root.toString());
        assertEquals(Path.of("rel/dir", "gradle-tracer").toAbsolutePath().normalize(), root);
    }

    // Every value-taking option rejects a value that is blank or looks like another flag, instead of
    // silently swallowing the next option (e.g. "--from --to y" would otherwise parse "--to" as the
    // value of --from and then fail on "y" as an unknown option).
    @Test void rejectsFromValueThatLooksLikeAFlag() {
        UsageException e = fails("appA", "--from", "--to", "y");
        assertTrue(e.getMessage().contains("--from needs a value, got '--to'"), e.getMessage());
    }

    @Test void rejectsBlankFromValue() {
        UsageException e = fails("appA", "--from", "", "--to", "y");
        assertTrue(e.getMessage().contains("--from needs a value, got ''"), e.getMessage());
    }

    @Test void rejectsToValueThatLooksLikeAFlag() {
        UsageException e = fails("appA", "--from", "x", "--to", "--task");
        assertTrue(e.getMessage().contains("--to needs a value, got '--task'"), e.getMessage());
    }

    @Test void rejectsBlankToValue() {
        UsageException e = fails("appA", "--from", "x", "--to", "");
        assertTrue(e.getMessage().contains("--to needs a value, got ''"), e.getMessage());
    }

    @Test void rejectsAtValueThatLooksLikeAFlag() {
        UsageException e = fails("appA", "--at", "--offline");
        assertTrue(e.getMessage().contains("--at needs a value, got '--offline'"), e.getMessage());
    }

    @Test void rejectsBlankAtValue() {
        UsageException e = fails("appA", "--at", "");
        assertTrue(e.getMessage().contains("--at needs a value, got ''"), e.getMessage());
    }

    @Test void rejectsTaskValueThatLooksLikeAFlag() {
        UsageException e = fails("appA", "--at", "z", "--task", "--offline");
        assertTrue(e.getMessage().contains("--task needs a value, got '--offline'"), e.getMessage());
    }

    @Test void rejectsBlankTaskValue() {
        UsageException e = fails("appA", "--at", "z", "--task", "");
        assertTrue(e.getMessage().contains("--task needs a value, got ''"), e.getMessage());
    }

    @Test void rejectsTsvOutValueThatLooksLikeAFlag() {
        UsageException e = fails("appA", "--from", "x", "--to", "y", "--tsv-out", "--timeout-minutes");
        assertTrue(e.getMessage().contains("--tsv-out needs a value, got '--timeout-minutes'"), e.getMessage());
    }

    @Test void rejectsBlankTsvOutValue() {
        UsageException e = fails("appA", "--from", "x", "--to", "y", "--tsv-out", "");
        assertTrue(e.getMessage().contains("--tsv-out needs a value, got ''"), e.getMessage());
    }

    // <app> must match [A-Za-z0-9][A-Za-z0-9_-]*; path-like or option-like names are rejected outright
    // instead of reaching the filesystem as a directory lookup.
    @Test void rejectsBlankAppName() {
        UsageException e = fails("");
        assertTrue(e.getMessage().contains("app name"), e.getMessage());
    }

    @Test void rejectsDashHAsAppName() {
        UsageException e = fails("-h");
        assertTrue(e.getMessage().contains("-h"), e.getMessage());
    }

    @Test void rejectsDotAsAppName() {
        UsageException e = fails(".");
        assertTrue(e.getMessage().contains("."), e.getMessage());
        assertTrue(e.getMessage().contains("is not a valid app name"), e.getMessage());
    }

    @Test void rejectsDotDotAsAppName() {
        UsageException e = fails("..");
        assertTrue(e.getMessage().contains(".."), e.getMessage());
        assertTrue(e.getMessage().contains("is not a valid app name"), e.getMessage());
    }

    @Test void rejectsPathTraversalAppName() {
        UsageException e = fails("../..");
        assertTrue(e.getMessage().contains("../.."), e.getMessage());
    }

    // --at (spec: "print the key and its composition, no comparison") makes --tsv and --tsv-out
    // meaningless; each is rejected rather than silently ignored.
    @Test void atRejectsTsv() {
        UsageException e = fails("appA", "--at", "z", "--tsv");
        assertTrue(e.getMessage().contains("--at") && e.getMessage().contains("--tsv"), e.getMessage());
    }

    @Test void atRejectsTsvOut() {
        UsageException e = fails("appA", "--at", "z", "--tsv-out", "rows.tsv");
        assertTrue(e.getMessage().contains("--at") && e.getMessage().contains("--tsv-out"), e.getMessage());
    }
}
