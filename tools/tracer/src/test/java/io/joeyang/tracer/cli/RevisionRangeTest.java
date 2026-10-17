package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RevisionRangeTest {
    static final String A = "a".repeat(40), B = "b".repeat(40), C = "c".repeat(40), D = "d".repeat(40);
    final Path repo = Path.of("/repo");

    @Test void returnsBaseThenCommitsOldestFirst() throws Exception {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet v1^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet HEAD^{commit}", 0, C + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 0, A + "\u001f\u001fbaseline\n", "")
                .on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + A + ".." + C, 0,
                        B + "\u001f" + A + "\u001fcore: add shout\n" + C + "\u001f" + B + "\u001fdeps: bump gson\n", "");

        List<Commit> commits = new RevisionRange(git, repo).resolve("v1", "HEAD");

        assertEquals(List.of(new Commit(A, "baseline"), new Commit(B, "core: add shout"), new Commit(C, "deps: bump gson")), commits);
        assertEquals(repo, git.cwds.get(0));
    }

    // Real git, under --quiet, exits 1 with no stderr for an unresolvable revision (verified against a real repo).
    @Test void unknownRevisionIsAUsageError() {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet nope^{commit}", 1, "", "");

        UsageException e = assertThrows(UsageException.class, () -> new RevisionRange(git, repo).resolve("nope", "HEAD"));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
    }

    // Exit 128 ("fatal: not a git repository", a broken worktree, ...) is a tool failure, not a usage error: the
    // revision itself may well be fine, so this must not be reported (or exit-coded) as if it were unknown.
    @Test void revParseFailureOtherThanExitOneIsAProviderError() {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet HEAD^{commit}", 128, "", "fatal: not a git repository\n");

        ProviderException e = assertThrows(ProviderException.class, () -> new RevisionRange(git, repo).resolve("HEAD", "v2"));
        assertTrue(e.getMessage().contains("not a git repository"), e.getMessage());
    }

    @Test void baseLogFailureIsAProviderError() {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet v1^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet HEAD^{commit}", 0, C + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 128, "", "fatal: bad object\n");

        ProviderException e = assertThrows(ProviderException.class, () -> new RevisionRange(git, repo).resolve("v1", "HEAD"));
        assertTrue(e.getMessage().contains("bad object"), e.getMessage());
    }

    // String.lines() treats a bare \r as a line terminator too; a commit subject that happens to contain one must
    // not be split into a spurious extra "row" that then fails to parse as sha\x1fparents\x1fsubject.
    @Test void subjectContainingCarriageReturnIsNotSplitIntoAnExtraRow() throws Exception {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet v1^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet HEAD^{commit}", 0, C + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 0, A + "\u001f\u001fbaseline\n", "")
                .on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + A + ".." + C, 0,
                        B + "\u001f" + A + "\u001fsome\rtitle\n" + C + "\u001f" + B + "\u001ffinal\n", "");

        List<Commit> commits = new RevisionRange(git, repo).resolve("v1", "HEAD");

        assertEquals(List.of(new Commit(A, "baseline"), new Commit(B, "some\rtitle"), new Commit(C, "final")), commits);
    }

    @Test void emptyRangeIsAUsageError() {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet A^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet B^{commit}", 0, A + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 0, A + "\u001f\u001fbaseline\n", "")
                .on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + A + ".." + A, 0, "", "");

        UsageException e = assertThrows(UsageException.class, () -> new RevisionRange(git, repo).resolve("A", "B"));
        assertTrue(e.getMessage().contains("no commits"), e.getMessage());
    }

    @Test void gitNotRunnableIsAProviderError() {
        FakeProcessRunner git = new FakeProcessRunner()
                .failWith("git rev-parse", ProcessException.cannotStart(List.of("git"), new java.io.IOException("No such file")));

        assertThrows(ProviderException.class, () -> new RevisionRange(git, repo).resolve("A", "B"));
    }

    @Test void malformedLogLineIsAProviderErrorNamingTheLine() {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet v1^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet HEAD^{commit}", 0, C + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 0, A + "\u001f\u001fbaseline\n", "")
                .on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + A + ".." + C, 0,
                        "not-a-valid-sha\u001f" + A + "\u001fwhatever\n", "");

        ProviderException e = assertThrows(ProviderException.class, () -> new RevisionRange(git, repo).resolve("v1", "HEAD"));
        assertTrue(e.getMessage().contains("not-a-valid-sha"), e.getMessage());
    }

    // A side-branch commit merged into 'to' is not an ancestor of 'from' on the first-parent line: the first row's
    // own first parent must equal the resolved 'from', not just appear somewhere in 'to's history.
    @Test void sideBranchStartIsRejected() {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet v1^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet HEAD^{commit}", 0, C + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 0, A + "\u001f\u001fbaseline\n", "")
                .on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + A + ".." + C, 0,
                        // B's first parent is D, not A: 'from' (A) is not on 'to's first-parent line.
                        B + "\u001f" + D + "\u001fside branch work\n" + C + "\u001f" + B + "\u001fmerge\n", "");

        UsageException e = assertThrows(UsageException.class, () -> new RevisionRange(git, repo).resolve("v1", "HEAD"));
        assertTrue(e.getMessage().contains(B) && e.getMessage().contains(A), e.getMessage());
    }

    // A root commit's %P is the empty string; parsing it must not crash, and it correctly fails the chain check
    // (an empty parent can never equal the 40-hex sha it is compared against) rather than being silently accepted.
    @Test void rootCommitWithEmptyParentFieldIsRejectedNotCrashed() {
        FakeProcessRunner git = new FakeProcessRunner()
                .on("git rev-parse --verify --quiet v1^{commit}", 0, A + "\n", "")
                .on("git rev-parse --verify --quiet HEAD^{commit}", 0, C + "\n", "")
                .on("git log -1 --format=%H%x1f%P%x1f%s " + A, 0, A + "\u001f\u001fbaseline\n", "")
                .on("git log --reverse --first-parent --format=%H%x1f%P%x1f%s " + A + ".." + C, 0,
                        C + "\u001f\u001froot commit\n", "");

        UsageException e = assertThrows(UsageException.class, () -> new RevisionRange(git, repo).resolve("v1", "HEAD"));
        assertTrue(e.getMessage().contains(C), e.getMessage());
    }
}
