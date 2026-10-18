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
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
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

    int run(KeyProvider k, String... args) throws UsageException {
        return run(git, k, args);
    }

    /** For tests that need a worktree-level failure the shared {@code git} fixture's catch-all can't script. */
    int run(ProcessRunner runner, KeyProvider k, String... args) throws UsageException {
        return new Cli(runner, repo, scratch, k).run(Cli.Options.parse(args),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    /** For tests that need to simulate an ownership mismatch without needing root to chown a file. */
    int run(LongSupplier currentUid, KeyProvider k, String... args) throws UsageException {
        return new Cli(git, repo, scratch, k, currentUid).run(Cli.Options.parse(args),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    /** Pre-creates {@code scratch} the same way Cli itself would, so a fixture testing something else doesn't trip the privacy check. */
    void createPrivateScratch() throws IOException {
        Files.createDirectory(scratch, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    String out() { return out.toString(StandardCharsets.UTF_8); }
    String err() { return err.toString(StandardCharsets.UTF_8); }

    @Test void rangeRendersTableAndExits3WhenSomethingChanged() throws Exception {
        int code = run(keys, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_CHANGED, code, err());
        assertTrue(out().contains("bbbbbbb  core: add shout") && out().contains("CHANGED") && out().contains("core.jar"), out());
        assertTrue(out().contains("ccccccc  docs only") && out().contains("clean"), out());
        assertFalse(out().contains("bazel-diff"), out());
        assertTrue(git.ran("git worktree remove --force"), "worktrees must be removed");
    }

    @Test void tsvGoesToStdoutWithoutTable() throws Exception {
        int code = run(keys, "appA", "--from", "base", "--to", "tip", "--tsv");
        assertEquals(Cli.EXIT_CHANGED, code, err());
        assertTrue(out().startsWith(A + "\tbaseline\tk1\tbase\t"), out());
        assertEquals(3, out().split("\n").length);
    }

    @Test void tsvOutWritesFileAndKeepsTheTable() throws Exception {
        Path file = tmp.resolve("rows.tsv");
        run(keys, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());
        assertTrue(out().contains("Tracing inputs of"), out());
        assertTrue(Files.readString(file).contains(B + "\tcore: add shout\tk2\tCHANGED\tcore.jar"));
    }

    @Test void tsvOutKeepsAnExistingTargetsMode() throws Exception {
        Path file = tmp.resolve("rows.tsv");
        Files.writeString(file, "previous content\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        run(keys, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());

        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(file));
    }

    @Test void tsvOutThroughASymlinkWritesTheRealTargetAndKeepsTheSymlink() throws Exception {
        Path real = tmp.resolve("real-rows.tsv");
        Files.writeString(real, "previous content\n");
        Path link = tmp.resolve("rows.tsv");
        Files.createSymbolicLink(link, real);

        run(keys, "appA", "--from", "base", "--to", "tip", "--tsv-out", link.toString());

        assertTrue(Files.isSymbolicLink(link), "the symlink itself must still be a symlink, not replaced by a regular file");
        assertTrue(Files.readString(real).contains(B + "\tcore: add shout\tk2\tCHANGED\tcore.jar"),
                "the real target behind the symlink must hold the new content");
    }

    @Test void keyFailureForOneCommitGivesBuildFailedRowAndExit2() throws Exception {
        KeyProvider flaky = (wt, task) -> {
            if (wt.getFileName().toString().equals("wt-bbbbbbb")) {
                throw new ProviderException("Could not resolve com.example:missing:1.0");
            }
            return keys.keyFor(wt, task);
        };
        int code = run(flaky, "appA", "--from", "base", "--to", "tip", "--tsv");

        assertEquals(Cli.EXIT_TOOL_FAILURE, code);
        String[] rows = out().split("\n");
        assertTrue(rows[1].contains("\tBUILD-FAILED\tCould not resolve com.example:missing:1.0"), rows[1]);
        assertTrue(rows[2].contains("\tCHANGED\t"), "third commit is compared with the last good key: " + rows[2]);
    }

    @Test void unknownAppIsAUsageError() throws Exception {
        int code = run(keys, "appZ", "--from", "base", "--to", "tip");
        assertEquals(Cli.EXIT_USAGE, code);
        assertTrue(err().contains("appZ"), err());
    }

    @Test void unknownFromRevisionIsAUsageError() throws Exception {
        git.on("git rev-parse --verify --quiet nope^{commit}", 1, "", "");
        int code = run(keys, "appA", "--from", "nope", "--to", "tip");
        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains("nope"), err());
    }

    @Test void atPrintsTheKeyComposition() throws Exception {
        int code = run(keys, "appA", "--at", "tip", "--task", "fingerprint");
        assertEquals(Cli.EXIT_OK, code, err());
        assertTrue(out().contains(":app:appA:fingerprint"), out());
        assertTrue(out().contains("k2") && out().contains("core.jar") && out().contains("h2"), out());
    }

    @Test void atWithAKeyFailureIsAToolFailure() throws Exception {
        KeyProvider broken = (wt, task) -> { throw new ProviderException("Could not resolve com.example:missing:1.0"); };

        int code = run(broken, "appA", "--at", "tip");

        assertEquals(Cli.EXIT_TOOL_FAILURE, code);
        assertTrue(err().contains("Could not resolve com.example:missing:1.0"), err());
    }

    @Test void rangeWithNoChangeExitsZero() throws Exception {
        KeyProvider sameKeyEverywhere = (wt, task) -> key(task, "k1", "h1");

        int code = run(sameKeyEverywhere, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_OK, code, err());
        assertTrue(out().contains("No commit changed"), out());
    }

    @Test void keepWorktreesKeepsTheWorktrees() throws Exception {
        int code = run(keys, "appA", "--from", "base", "--to", "tip", "--keep-worktrees");

        assertEquals(Cli.EXIT_CHANGED, code, err());
        assertFalse(git.ran("git worktree remove"), "worktrees must stay with --keep-worktrees, so none are removed");
    }

    @Test void tsvOutWithMissingParentIsAUsageError() throws Exception {
        Path file = tmp.resolve("missing-dir/rows.tsv");
        int code = run(keys, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());
        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains(file.toString()), err());
        assertFalse(git.ran("git worktree"), "no worktree work before the tsv-out pre-check fails");
    }

    @Test void tsvOutThatIsADirectoryIsAUsageError() throws Exception {
        int code = run(keys, "appA", "--from", "base", "--to", "tip", "--tsv-out", tmp.toString());
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

            int code = run(keys, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());

            assertEquals(Cli.EXIT_USAGE, code, err());
            assertTrue(err().contains("--tsv-out '" + file + "': parent directory " + dir + " is not writable"), err());
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

            int code = run(keys, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());

            assertEquals(Cli.EXIT_USAGE, code, err());
            assertTrue(err().contains(file.toString()), err());
            assertFalse(git.ran("git worktree"), "no worktree work before the tsv-out pre-check fails");
        } finally {
            Files.setPosixFilePermissions(file, original);
        }
    }

    @Test void tsvOutThatIsADanglingSymlinkIsAUsageError() throws Exception {
        Path link = tmp.resolve("rows.tsv");
        Files.createSymbolicLink(link, tmp.resolve("missing-target"));

        int code = run(keys, "appA", "--from", "base", "--to", "tip", "--tsv-out", link.toString());

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains(link.toString()) && err().contains("symlink") && err().contains("missing"), err());
        assertFalse(git.ran("git worktree"), "no worktree work before the tsv-out pre-check fails");
    }

    @Test void tsvOutThatIsASymlinkWithAReadOnlyRealParentIsAUsageError() throws Exception {
        Path realDir = tmp.resolve("real-dir");
        Files.createDirectory(realDir);
        Path real = realDir.resolve("real-rows.tsv");
        Files.writeString(real, "previous content\n");
        Path link = tmp.resolve("rows.tsv");
        Files.createSymbolicLink(link, real);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(realDir);
        try {
            Files.setPosixFilePermissions(realDir, PosixFilePermissions.fromString("r-xr-xr-x"));

            int code = run(keys, "appA", "--from", "base", "--to", "tip", "--tsv-out", link.toString());

            assertEquals(Cli.EXIT_USAGE, code, err());
            assertTrue(err().contains(realDir.toString()), err());
            assertFalse(git.ran("git worktree"), "no worktree work before the tsv-out pre-check fails");
        } finally {
            Files.setPosixFilePermissions(realDir, original);
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
            int code = run(breakWriteAfterLastCheckout, "appA", "--from", "base", "--to", "tip", "--tsv-out", file.toString());

            assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
            assertEquals("previous content\n", Files.readString(file), "a failed atomic write must not touch the previous file");
        } finally {
            Files.setPosixFilePermissions(tmp, original);
        }
    }

    @Test void scratchRootLockedByAnotherRunIsAUsageError() throws Exception {
        createPrivateScratch();
        try (FileChannel locked = FileChannel.open(scratch.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock heldElsewhere = locked.lock()) {
            int code = run(keys, "appA", "--from", "base", "--to", "tip");

            assertEquals(Cli.EXIT_USAGE, code, err());
            assertTrue(err().contains("another trace is using") && err().contains(scratch.toString()), err());
            // The range is resolved (read-only git) before the lock is taken, so rev-parse/log calls do happen;
            // what must never happen while another run holds the lock is worktree work or a scratch sweep.
            assertFalse(git.ran("git worktree"), "no worktree work while the scratch root is locked");
                assertFalse(err().contains("usage: trace"), "lock contention is not a command-line mistake; no usage text: " + err());
        }
    }

    @Test void unknownFromRevisionLeavesNoScratchRoot() throws Exception {
        assertFalse(Files.exists(scratch), "fixture precondition: scratch must not exist before the run");
        git.on("git rev-parse --verify --quiet nope^{commit}", 1, "", "");

        int code = run(keys, "appA", "--from", "nope", "--to", "tip");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertFalse(Files.exists(scratch), "the scratch root must not be created for a revision that was never resolved");
        assertFalse(Files.exists(scratch.resolve(".lock")), ".lock must not be created for a revision that was never resolved");
    }

    @Test void unknownToRevisionLeavesNoScratchRoot() throws Exception {
        assertFalse(Files.exists(scratch), "fixture precondition: scratch must not exist before the run");
        git.on("git rev-parse --verify --quiet nope^{commit}", 1, "", "");

        int code = run(keys, "appA", "--from", "base", "--to", "nope");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertFalse(Files.exists(scratch), "the scratch root must not be created for a revision that was never resolved");
        assertFalse(Files.exists(scratch.resolve(".lock")), ".lock must not be created for a revision that was never resolved");
    }

    @Test void unknownAtRevisionLeavesNoScratchRoot() throws Exception {
        assertFalse(Files.exists(scratch), "fixture precondition: scratch must not exist before the run");
        git.on("git rev-parse --verify --quiet nope^{commit}", 1, "", "");

        int code = run(keys, "appA", "--at", "nope");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertFalse(Files.exists(scratch), "the scratch root must not be created for a revision that was never resolved");
        assertFalse(Files.exists(scratch.resolve(".lock")), ".lock must not be created for a revision that was never resolved");
    }

    @Test void emptyRangeLeavesNoScratchRoot() throws Exception {
        assertFalse(Files.exists(scratch), "fixture precondition: scratch must not exist before the run");
        git.on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + C + ".." + C, 0, "", "");

        int code = run(keys, "appA", "--from", "tip", "--to", "tip");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains("no commits"), err());
        assertFalse(Files.exists(scratch), "the scratch root must not be created for an empty range");
        assertFalse(Files.exists(scratch.resolve(".lock")), ".lock must not be created for an empty range");
    }

    @Test void lockIsReleasedAfterARunCompletes() throws Exception {
        int first = run(keys, "appA", "--from", "base", "--to", "tip");
        assertEquals(Cli.EXIT_CHANGED, first, err());

        int second = run(keys, "appA", "--from", "base", "--to", "tip");
        assertEquals(Cli.EXIT_CHANGED, second, err());
    }

    // Ownership by another user can't be faked without root (chown requires privilege this test doesn't have and
    // shouldn't need), so only the permission-bits branch of the privacy check is exercised here.
    @Test void scratchRootWithGroupOrOtherWriteIsAUsageError() throws Exception {
        // setPosixFilePermissions (a separate chmod), not createDirectory's FileAttribute: the attribute's mode
        // is still subject to the process umask on at least some platforms/sandboxes (e.g. a sandboxed test
        // runs default to a stricter umask than an interactive shell), so asking for group write at creation time
        // isn't reliable; an explicit chmod afterward is.
        Files.createDirectory(scratch);
        Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("rwxrwxr-x"));

        int code = run(keys, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains(scratch.toString()) && err().contains("not private"), err());
        assertFalse(git.ran("git worktree"), "no worktree work when the scratch root isn't private");
        assertFalse(err().contains("usage: trace"), "not private is not a command-line mistake; no usage text: " + err());
    }

    @Test void freshlyCreatedScratchRootHasMode700() throws Exception {
        run(keys, "appA", "--from", "base", "--to", "tip");

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(scratch));
    }

    @Test void scratchRootOwnedByAnotherUidIsAUsageError() throws Exception {
        createPrivateScratch(); // owned by this process's real uid

        int code = run(() -> -1L, keys, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains(scratch.toString()) && err().contains("not private"), err());
        assertFalse(git.ran("git worktree"), "no worktree work when the scratch root isn't private");
    }

    @Test void symlinkedScratchRootIsAUsageError() throws Exception {
        Path real = tmp.resolve("real-scratch");
        Files.createDirectory(real, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createSymbolicLink(scratch, real);

        int code = run(keys, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_USAGE, code, err());
        assertTrue(err().contains(scratch.toString()) && err().contains("not private"), err());
        assertFalse(git.ran("git worktree"), "no worktree work when the scratch root is a symlink");
    }

    @Test void scratchRootThatIsAnExistingFileIsAToolFailure() throws Exception {
        Files.writeString(scratch, "not a directory");

        int code = run(keys, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
        assertTrue(err().contains(scratch.toString()), err());
    }

    @Test void lockPathThatIsADirectoryIsAToolFailure() throws Exception {
        createPrivateScratch();
        Files.createDirectory(scratch.resolve(".lock"));

        int code = run(keys, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
        // FileSystemException.getReason() ("Is a directory"), not its getMessage() (which already repeats the
        // path), so the path isn't doubled: "...lock: .../.lock: Is a directory".
        assertTrue(err().contains(scratch.resolve(".lock").toString()) && err().contains("Is a directory"), err());
        assertEquals(1, err().split(Pattern.quote(scratch.resolve(".lock").toString()), -1).length - 1,
                "the .lock path must appear exactly once, not duplicated by the exception's own message: " + err());
    }

    @Test void lockFileSurvivesARun() throws Exception {
        createPrivateScratch();
        Path lock = Files.createFile(scratch.resolve(".lock"));

        run(keys, "appA", "--from", "base", "--to", "tip");

        assertTrue(Files.exists(lock), ".lock must never be removed by a run");
    }

    @Test void unexpectedRuntimeExceptionIsReportedAsExit2() throws Exception {
        KeyProvider broken = (wt, task) -> { throw new java.io.UncheckedIOException(new IOException("disk gone")); };

        int code = run(broken, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_TOOL_FAILURE, code);
        assertTrue(err().contains("internal error") && err().contains("UncheckedIOException") && err().contains("disk gone"), err());
        assertFalse(err().contains("\tat "), "no stack trace printed: " + err());
    }

    @Test void lockIsReleasedWhenCheckoutFailsMidWalk() throws Exception {
        FakeProcessRunner failingThirdCheckout = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet base^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet tip^{commit}", 0, C + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 0, A + "\u001f\u001fbaseline\n", "")
                .on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + A + ".." + C, 0,
                        B + "\u001f" + A + "\u001fcore: add shout\n" + C + "\u001f" + B + "\u001fdocs only\n", "")
                .on("git worktree add --detach " + scratch.resolve("wt-ccccccc"), 1, "", "fatal: boom")
                .on("git worktree", 0, "", "");

        int code = run(failingThirdCheckout, keys, "appA", "--from", "base", "--to", "tip");

        assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
        assertTrue(err().contains("boom"), err());
        // The lock must have been released despite the failure above, so a plain second run succeeds.
        int second = run(keys, "appA", "--from", "base", "--to", "tip");
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
            int code = run(interrupting, "appA", "--from", "base", "--to", "tip");

            assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
            assertTrue(err().contains("interrupted"), err());
            assertEquals(1, calls.get(), "the walk must stop at the first interrupted provider call");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag must still be set");
        } finally {
            Thread.interrupted(); // clear it, so it doesn't leak into later tests on this thread
        }
    }

    @Test void interruptAfterAKeyLookupAbortsTheWalkAtTheNextCommit() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        KeyProvider interrupting = (wt, task) -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            return key(task, "k1", "h1");
        };
        try {
            int code = run(interrupting, "appA", "--from", "base", "--to", "tip");

            assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
            assertTrue(err().contains("interrupted"), err());
            // The lookup itself succeeds with the flag set; the walk only stops at the top of the *next*
            // iteration, before that commit's checkout or any of its provider calls.
            assertEquals(1, calls.get(), "no further lookups once the flag is seen");
        } finally {
            Thread.interrupted();
        }
    }

    @Test void interruptAfterTheLastKeyLookupAbortsAfterTheLoop() throws Exception {
        KeyProvider interruptOnLastCommit = (wt, task) -> {
            if (wt.getFileName().toString().equals("wt-ccccccc")) {
                Thread.currentThread().interrupt();
            }
            return key(task, "k2", "h2");
        };
        try {
            int code = run(interruptOnLastCommit, "appA", "--from", "base", "--to", "tip");

            // With no post-loop check, this would render the table and exit 3 (a CHANGED row) instead.
            assertEquals(Cli.EXIT_TOOL_FAILURE, code, err());
            assertTrue(err().contains("interrupted"), err());
        } finally {
            Thread.interrupted();
        }
    }
}
