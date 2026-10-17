package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
