package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {
    static final String A = "a".repeat(40), B = "b".repeat(40), C = "c".repeat(40);
    @TempDir Path tmp;
    Path repo, scratch;
    FakeProcessRunner git;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        repo = tmp.resolve("repo");
        scratch = tmp.resolve("scratch");
        Files.createDirectories(repo.resolve("app/appA"));
        git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet base^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet tip^{commit}", 0, C + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 0, A + "\u001f\u001fbaseline\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + C, 0, C + "\u001f" + B + "\u001fdocs only\n", "")
                .on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + A + ".." + C, 0,
                        B + "\u001f" + A + "\u001fcore: add shout\n" + C + "\u001f" + B + "\u001fdocs only\n", "")
                .on("git worktree", 0, "", "");
    }

    static TaskKey key(String task, String hash, String jarHash) {
        return new TaskKey(task, hash, Map.of("classpath", Map.of("/wt/core/build/libs/core.jar", jarHash)), Map.of(), "impl");
    }

    final KeyProvider keys = (wt, task) -> switch (wt.getFileName().toString()) {
        case "wt-aaaaaaa" -> key(task, "k1", "h1");
        case "wt-bbbbbbb", "wt-ccccccc" -> key(task, "k2", "h2");
        default -> throw new ProviderException("unexpected worktree " + wt);
    };

    /** Writes a hashes file like the real tool does, and reports appA impacted for commit B only. */
    final ImpactProvider impacts = new ImpactProvider() {
        @Override public Path hashes(Path worktree, Path output) throws ProviderException {
            try {
                return Files.writeString(output, "{}");
            } catch (IOException e) {
                throw new ProviderException(e.getMessage(), e);
            }
        }
        @Override public Set<String> impactedTargets(Path previous, Path current) {
            return current.getFileName().toString().contains("bbbbbbb") ? Set.of("//app/appA:appA") : Set.of();
        }
    };

    int run(KeyProvider k, ImpactProvider i, String... args) throws UsageException {
        return run(git, k, i, args);
    }

    /** For tests that need a worktree-level failure the shared {@code git} fixture's catch-all can't script. */
    int run(ProcessRunner runner, KeyProvider k, ImpactProvider i, String... args) throws UsageException {
        return new Cli(runner, repo, scratch, k, i).run(Cli.Options.parse(args),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    String out() { return out.toString(StandardCharsets.UTF_8); }
    String err() { return err.toString(StandardCharsets.UTF_8); }

    @Test void rangeRendersTableAndExits3WhenSomethingChanged() throws Exception {
        int code = run(keys, impacts, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_CHANGED, code, err());
        assertTrue(out().contains("bbbbbbb  core: add shout") && out().contains("CHANGED") && out().contains("core.jar"), out());
        assertTrue(out().contains("ccccccc  docs only") && out().contains("clean"), out());
        assertTrue(out().contains("//app/appA:appA (bazel-diff)"), out());
        assertTrue(git.ran("git worktree remove --force"), "worktrees must be removed");
    }

    @Test void tsvGoesToStdoutWithoutTable() throws Exception {
        run(keys, impacts, "appA", "--from", "base", "--to", "tip", "--tsv");
        assertTrue(out().startsWith(A + "\tbaseline\tk1\t-\tbase\t"), out());
        assertEquals(3, out().split("\n").length);
    }

    @Test void tsvOutWritesFileAndKeepsTheTable() throws Exception {
        Path file = tmp.resolve("rows.tsv");
        run(keys, impacts, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());
        assertTrue(out().contains("Tracing inputs of"), out());
        assertTrue(Files.readString(file).contains(B + "\tcore: add shout\tk2\timpacted\tCHANGED\tcore.jar"));
    }

    @Test void keyFailureForOneCommitGivesBuildFailedRowAndExit2() throws Exception {
        KeyProvider flaky = (wt, task) -> {
            if (wt.getFileName().toString().equals("wt-bbbbbbb")) {
                throw new ProviderException("Could not resolve com.example:missing:1.0");
            }
            return keys.keyFor(wt, task);
        };
        int code = run(flaky, impacts, "appA", "--from", "base", "--to", "tip", "--tsv");

        assertEquals(Cli.EXIT_TOOL_FAILURE, code);
        String[] rows = out().split("\n");
        assertTrue(rows[1].contains("\tBUILD-FAILED\tCould not resolve com.example:missing:1.0"), rows[1]);
        assertTrue(rows[2].contains("\tCHANGED\t"), "third commit is compared with the last good key: " + rows[2]);
    }

    @Test void bazelFailureDegradesToNotAvailable() throws Exception {
        ImpactProvider broken = new ImpactProvider() {
            @Override public Path hashes(Path worktree, Path output) throws ProviderException { throw new ProviderException("bazel: command not found"); }
            @Override public Set<String> impactedTargets(Path p, Path c) { return Set.of(); }
        };
        int code = run(keys, broken, "appA", "--from", "base", "--to", "tip", "--tsv");

        assertEquals(Cli.EXIT_CHANGED, code);
        assertTrue(err().contains("bazel-diff unavailable: bazel: command not found"), err());
        assertTrue(out().split("\n")[1].contains("\tn/a\t"), out());
    }

    @Test void noBazelOmitsTheColumnSource() throws Exception {
        run(keys, null, "appA", "--from", "base", "--to", "tip", "--no-bazel");
        assertFalse(out().contains("bazel-diff)"), out());
        assertTrue(out().contains("Tracing inputs of :app:appA:compileJava (gradle) over 2 commits"), out());
    }

    @Test void unknownAppIsAUsageError() throws Exception {
        int code = run(keys, impacts, "appZ", "--from", "base", "--to", "tip");
        assertEquals(Cli.EXIT_USAGE, code);
        assertTrue(err().contains("appZ"), err());
    }

    @Test void unknownFromRevisionIsAUsageError() throws Exception {
        git.on("git rev-parse --verify --quiet nope^{commit}", 1, "", "");
        int code = run(keys, impacts, "appA", "--from", "nope", "--to", "tip");
        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains("nope"), err());
    }

    @Test void atPrintsTheKeyComposition() throws Exception {
        int code = run(keys, impacts, "appA", "--at", "tip", "--task", "fingerprint");
        assertEquals(Cli.EXIT_OK, code, err());
        assertTrue(out().contains(":app:appA:fingerprint"), out());
        assertTrue(out().contains("k2") && out().contains("core.jar") && out().contains("h2"), out());
    }

    @Test void tsvOutWithMissingParentIsAUsageError() throws Exception {
        Path file = tmp.resolve("missing-dir/rows.tsv");
        int code = run(keys, impacts, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());
        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains(file.toString()), err());
        assertFalse(git.ran("git worktree"), "no worktree work before the tsv-out pre-check fails");
    }

    @Test void tsvOutThatIsADirectoryIsAUsageError() throws Exception {
        int code = run(keys, impacts, "appA", "--from", "base", "--to", "tip", "--tsv-out", tmp.toString());
        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains(tmp.toString()), err());
        assertFalse(git.ran("git worktree"), "no worktree work before the tsv-out pre-check fails");
    }

    @Test void tsvOutWithUnwritableParentIsAUsageError() throws Exception {
        Path dir = tmp.resolve("readonly-dir");
        Files.createDirectories(dir);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
            Path file = dir.resolve("rows.tsv");

            int code = run(keys, impacts, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());

            assertEquals(Cli.EXIT_USAGE, code, err());
            assertTrue(err().contains(file.toString()) || err().contains(dir.toString()), err());
            assertFalse(git.ran("git worktree"), "no worktree work before the tsv-out pre-check fails");
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }
    }

    @Test void tsvOutThatIsAnExistingReadOnlyFileIsAUsageError() throws Exception {
        Path file = tmp.resolve("rows.tsv");
        Files.writeString(file, "old content\n");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));

            int code = run(keys, impacts, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());

            assertEquals(Cli.EXIT_USAGE, code, err());
            assertTrue(err().contains(file.toString()), err());
            assertFalse(git.ran("git worktree"), "no worktree work before the tsv-out pre-check fails");
        } finally {
            Files.setPosixFilePermissions(file, original);
        }
    }

    @Test void tsvOutWriteFailureMidWalkLeavesThePreviousFileIntact() throws Exception {
        Path file = tmp.resolve("rows.tsv");
        Files.writeString(file, "previous content\n");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(tmp);
        // The temp file for the atomic write lands next to the target, i.e. directly under tmp; making tmp
        // read-only (but still searchable) blocks creating it without touching scratch, which is a separate
        // subdirectory with its own permissions.
        KeyProvider breakWriteAfterLastCheckout = (wt, task) -> {
            TaskKey key = keys.keyFor(wt, task);
            if (wt.getFileName().toString().equals("wt-ccccccc")) {
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("r-xr-xr-x"));
                } catch (IOException e) {
                    throw new ProviderException(e.getMessage(), e);
                }
            }
            return key;
        };
        try {
            int code = run(breakWriteAfterLastCheckout, impacts, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());

            assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
            assertEquals("previous content\n", Files.readString(file), "a failed atomic write must not touch the previous file");
        } finally {
            Files.setPosixFilePermissions(tmp, original);
        }
    }

    @Test void scratchRootLockedByAnotherRunIsAUsageError() throws Exception {
        Files.createDirectories(scratch);
        try (FileChannel locked = FileChannel.open(scratch.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock heldElsewhere = locked.lock()) {
            int code = run(keys, impacts, "appA", "--from", "base", "--to", "tip");

            assertEquals(Cli.EXIT_USAGE, code, err());
            assertTrue(err().contains("another trace is using") && err().contains(scratch.toString()), err());
            // The range is resolved (read-only git) before the lock is taken, so rev-parse/log calls do happen;
            // what must never happen while another run holds the lock is worktree work or a scratch sweep.
            assertFalse(git.ran("git worktree"), "no worktree work while the scratch root is locked");
            assertFalse(err().contains("stale scratch file"), "no sweep while the scratch root is locked");
        }
    }

    @Test void unknownFromRevisionLeavesNoScratchRoot() throws Exception {
        assertFalse(Files.exists(scratch), "fixture precondition: scratch must not exist before the run");
        git.on("git rev-parse --verify --quiet nope^{commit}", 1, "", "");

        int code = run(keys, impacts, "appA", "--from", "nope", "--to", "tip");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertFalse(Files.exists(scratch), "the scratch root must not be created for a revision that was never resolved");
        assertFalse(Files.exists(scratch.resolve(".lock")), ".lock must not be created for a revision that was never resolved");
    }

    @Test void unknownToRevisionLeavesNoScratchRoot() throws Exception {
        assertFalse(Files.exists(scratch), "fixture precondition: scratch must not exist before the run");
        git.on("git rev-parse --verify --quiet nope^{commit}", 1, "", "");

        int code = run(keys, impacts, "appA", "--from", "base", "--to", "nope");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertFalse(Files.exists(scratch), "the scratch root must not be created for a revision that was never resolved");
        assertFalse(Files.exists(scratch.resolve(".lock")), ".lock must not be created for a revision that was never resolved");
    }

    @Test void unknownAtRevisionLeavesNoScratchRoot() throws Exception {
        assertFalse(Files.exists(scratch), "fixture precondition: scratch must not exist before the run");
        git.on("git rev-parse --verify --quiet nope^{commit}", 1, "", "");

        int code = run(keys, impacts, "appA", "--at", "nope");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertFalse(Files.exists(scratch), "the scratch root must not be created for a revision that was never resolved");
        assertFalse(Files.exists(scratch.resolve(".lock")), ".lock must not be created for a revision that was never resolved");
    }

    @Test void emptyRangeLeavesNoScratchRoot() throws Exception {
        assertFalse(Files.exists(scratch), "fixture precondition: scratch must not exist before the run");
        git.on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + C + ".." + C, 0, "", "");

        int code = run(keys, impacts, "appA", "--from", "tip", "--to", "tip");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains("no commits"), err());
        assertFalse(Files.exists(scratch), "the scratch root must not be created for an empty range");
        assertFalse(Files.exists(scratch.resolve(".lock")), ".lock must not be created for an empty range");
    }

    @Test void lockIsReleasedAfterARunCompletes() throws Exception {
        int first = run(keys, impacts, "appA", "--from", "base", "--to", "tip");
        assertEquals(Cli.EXIT_CHANGED, first, err());

        int second = run(keys, impacts, "appA", "--from", "base", "--to", "tip");
        assertEquals(Cli.EXIT_CHANGED, second, err());
    }

    @Test void lockFileSurvivesScratchSweep() throws Exception {
        Files.createDirectories(scratch);
        Path lock = Files.createFile(scratch.resolve(".lock"));
        Files.writeString(scratch.resolve("hashes-stale.json"), "{}");

        run(keys, impacts, "appA", "--from", "base", "--to", "tip");

        assertTrue(Files.exists(lock), ".lock must never be swept, by WorktreePool or Cli.deleteScratchFiles");
    }

    @Test void unexpectedRuntimeExceptionIsReportedAsExit2() throws Exception {
        KeyProvider broken = (wt, task) -> { throw new java.io.UncheckedIOException(new IOException("disk gone")); };

        int code = run(broken, impacts, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_TOOL_FAILURE, code);
        assertTrue(err().contains("internal error") && err().contains("UncheckedIOException") && err().contains("disk gone"), err());
        assertFalse(err().contains("\tat "), "no stack trace printed: " + err());
    }

    @Test void hashFilesAreRemovedWhenCheckoutFailsMidWalk() throws Exception {
        FakeProcessRunner failingThirdCheckout = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet base^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet tip^{commit}", 0, C + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 0, A + "\u001f\u001fbaseline\n", "")
                .on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + A + ".." + C, 0,
                        B + "\u001f" + A + "\u001fcore: add shout\n" + C + "\u001f" + B + "\u001fdocs only\n", "")
                .on("git worktree add --detach " + scratch.resolve("wt-ccccccc"), 1, "", "fatal: boom")
                .on("git worktree", 0, "", "");

        int code = run(failingThirdCheckout, keys, impacts, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
        assertTrue(err().contains("boom"), err());
        try (Stream<Path> left = Files.list(scratch)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().startsWith("hashes-")),
                    "hash files for the commits processed before the failure must still be cleaned up");
        }

        // The lock must have been released despite the failure above, so a plain second run succeeds.
        int second = run(keys, impacts, "appA", "--from", "base", "--to", "tip");
        assertEquals(Cli.EXIT_CHANGED, second, err());
    }

    @Test void interruptedKeyLookupAbortsTheWalk() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        KeyProvider interrupting = (wt, task) -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new ProviderException("boom");
        };
        try {
            int code = run(interrupting, impacts, "appA", "--from", "base", "--to", "tip");

            assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
            assertTrue(err().contains("interrupted"), err());
            assertEquals(1, calls.get(), "the walk must stop at the first interrupted provider call");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must still be set");
            try (Stream<Path> left = Files.list(scratch)) {
                assertTrue(left.noneMatch(p -> p.getFileName().toString().startsWith("hashes-")),
                        "cleanup must still run when the walk is aborted by an interrupt");
            }
        } finally {
            Thread.interrupted(); // clear it, so it doesn't leak into later tests on this thread
        }
    }

    @Test void staleScratchFilesAreSweptAndNewOnesRemoved() throws Exception {
        Files.createDirectories(scratch);
        Path stale = Files.writeString(scratch.resolve("hashes-stale.json"), "{}");

        run(keys, impacts, "appA", "--from", "base", "--to", "tip", "--tsv");

        assertFalse(Files.exists(stale), "a killed run's hash files are removed at start");
        assertTrue(err().contains("removed 1 stale scratch file"), err());
        try (Stream<Path> left = Files.list(scratch)) {
            assertTrue(left.noneMatch(p -> p.getFileName().toString().startsWith("hashes-")), "hash files created by this run are removed at the end");
        }
    }
}
