package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreePoolTest {
    static final Commit C1 = new Commit("1".repeat(40), "one");
    @TempDir Path tmp;
    final Path repo = Path.of("/repo");

    FakeProcessRunner happyGit() {
        return new FakeProcessRunner()
                .on("git worktree prune", 0, "", "")
                .on("git worktree add --detach", 0, "", "")
                .on("git worktree remove --force", 0, "", "");
    }

    @Test void prunesOnStartAndAddsDetachedWorktreeUnderRoot() throws Exception {
        FakeProcessRunner git = happyGit();
        try (WorktreePool pool = new WorktreePool(git, repo, tmp.resolve("wts"), false)) {
            Path dir = pool.checkout(C1);
            assertEquals(tmp.resolve("wts").resolve("wt-1111111"), dir);
            assertEquals(List.of("git", "worktree", "prune"), git.calls.get(0));
            assertEquals(List.of("git", "worktree", "add", "--detach", dir.toString(), C1.sha()), git.calls.get(1));
        }
    }

    @Test void closeRemovesEveryWorktree() throws Exception {
        FakeProcessRunner git = happyGit();
        Path dir;
        try (WorktreePool pool = new WorktreePool(git, repo, tmp.resolve("wts"), false)) {
            dir = pool.checkout(C1);
        }
        assertTrue(git.ran("git worktree remove --force " + dir));
    }

    @Test void keepWorktreesSkipsRemoval() throws Exception {
        FakeProcessRunner git = happyGit();
        try (WorktreePool pool = new WorktreePool(git, repo, tmp.resolve("wts"), true)) {
            pool.checkout(C1);
        }
        assertFalse(git.ran("git worktree remove"));
    }

    @Test void staleDirectoryIsDeletedBeforeAdd() throws Exception {
        Path stale = tmp.resolve("wts").resolve("wt-1111111");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("leftover.txt"), "x");
        FakeProcessRunner git = happyGit();
        try (WorktreePool pool = new WorktreePool(git, repo, tmp.resolve("wts"), false)) {
            pool.checkout(C1);
            assertFalse(Files.exists(stale.resolve("leftover.txt")));
        }
    }

    @Test void staleWorktreeDirsFromKilledRunsAreSweptAtStart() throws Exception {
        Path stale = tmp.resolve("wts").resolve("wt-0000000");
        Files.createDirectories(stale);
        Files.writeString(stale.resolve("leftover.txt"), "x");
        try (WorktreePool pool = new WorktreePool(happyGit(), repo, tmp.resolve("wts"), false)) {
            assertFalse(Files.exists(stale));
        }
    }

    @Test void addFailureIsAProviderErrorNamingTheCommit() throws Exception {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git worktree prune", 0, "", "")
                .on("git worktree add --detach", 128, "", "fatal: invalid reference\n");
        try (WorktreePool pool = new WorktreePool(git, repo, tmp.resolve("wts"), false)) {
            ProviderException e = assertThrows(ProviderException.class, () -> pool.checkout(C1));
            assertTrue(e.getMessage().contains("1111111"), e.getMessage());
            assertTrue(e.getMessage().contains("invalid reference"), e.getMessage());
        }
    }

    @Test void removalFailuresDoNotEscapeClose() throws Exception {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git worktree prune", 0, "", "")
                .on("git worktree add --detach", 0, "", "")
                .failWith("git worktree remove", ProcessException.timeout(List.of("git"), java.time.Duration.ofSeconds(1)));
        WorktreePool pool = new WorktreePool(git, repo, tmp.resolve("wts"), false);
        pool.checkout(C1);
        pool.close(); // must not throw
    }

    // A directory left behind mid-run (not caught by the constructor's one-time sweep) is still registered with
    // git; deleting it without 'git worktree remove' first would leave a dangling registration that makes the
    // next 'git worktree add' of the same path fail with "is a missing but already registered worktree".
    @Test void checkoutUnregistersBeforeDeletingAPreexistingDirectory() throws Exception {
        FakeProcessRunner git = happyGit();
        try (WorktreePool pool = new WorktreePool(git, repo, tmp.resolve("wts"), false)) {
            Path dir = tmp.resolve("wts").resolve("wt-1111111");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("leftover.txt"), "x");

            pool.checkout(C1);

            int removeIndex = git.calls.indexOf(List.of("git", "worktree", "remove", "--force", dir.toString()));
            int addIndex = git.calls.indexOf(List.of("git", "worktree", "add", "--detach", dir.toString(), C1.sha()));
            assertTrue(removeIndex >= 0 && removeIndex < addIndex,
                    "must unregister the stale worktree before re-adding it: " + git.calls);
            assertFalse(Files.exists(dir.resolve("leftover.txt")));
        }
    }

    // The fake cannot model git's own worktree registration bookkeeping, so this exercises the real binary: with
    // the old prune-before-sweep order, a dir left behind by a --keep-worktrees run is still "registered" when the
    // next pool's sweep deletes it (prune, run first, saw nothing missing yet), and the following 'worktree add'
    // of the same sha then fails with "is a missing but already registered worktree" (exit 128).
    @Test void reopeningThePoolCanReuseACommitKeptByThePreviousRun() throws Exception {
        Assumptions.assumeTrue(gitIsOnPath(), "git is not on PATH; skipping the real-git regression test");
        ProcessRunner git = realGit();
        Path gitRepo = tmp.resolve("repo");
        Files.createDirectories(gitRepo);
        run(git, gitRepo, "git", "init", "-q");
        run(git, gitRepo, "git", "-c", "user.name=t", "-c", "user.email=t@example.com",
                "commit", "--allow-empty", "-q", "-m", "first");
        String sha = run(git, gitRepo, "git", "rev-parse", "HEAD").stdout().strip();
        Commit commit = new Commit(sha, "first");
        Path root = tmp.resolve("wts");

        try (WorktreePool pool1 = new WorktreePool(git, gitRepo, root, true)) {
            pool1.checkout(commit);
        } // keep=true: the worktree is left registered and on disk for the next run to find.

        try (WorktreePool pool2 = new WorktreePool(git, gitRepo, root, true)) {
            Path dir = pool2.checkout(commit);
            assertTrue(Files.isDirectory(dir));
        }
    }

    private static boolean gitIsOnPath() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ProcessRunner realGit() {
        return (cwd, timeout, command) -> {
            Process process;
            try {
                process = new ProcessBuilder(command).directory(cwd.toFile()).start();
            } catch (IOException e) {
                throw ProcessException.cannotStart(command, e);
            }
            String out, err;
            try {
                out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw ProcessException.timeout(command, timeout);
                }
            } catch (IOException e) {
                throw ProcessException.cannotStart(command, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ProcessException.interrupted(command);
            }
            return new ProcessRunner.Result(process.exitValue(), out, err);
        };
    }

    private static ProcessRunner.Result run(ProcessRunner git, Path cwd, String... command) throws ProcessException {
        ProcessRunner.Result r = git.run(cwd, Duration.ofSeconds(30), List.of(command));
        if (!r.ok()) {
            throw new AssertionError("command failed: " + String.join(" ", command) + ": " + r.stderr());
        }
        return r;
    }
}
