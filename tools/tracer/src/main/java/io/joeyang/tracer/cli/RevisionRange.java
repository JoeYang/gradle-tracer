package io.joeyang.tracer.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Turns {@code --from/--to} into an ordered commit list: the base commit first, then every first-parent commit in from..to. */
public final class RevisionRange {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    // %P is every parent sha, space-separated; for a root commit it is empty. Only the first token (the first
    // parent) matters here, since the walk follows --first-parent.
    private static final String FORMAT = "--format=%H%x1f%P%x1f%s";

    private final ProcessRunner git;
    private final Path repo;

    public RevisionRange(ProcessRunner git, Path repo) {
        this.git = Objects.requireNonNull(git, "git must not be null");
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
    }

    public List<Commit> resolve(String from, String to) throws UsageException, ProviderException {
        String fromSha = revParse(from);
        String toSha = revParse(to);
        List<Commit> commits = new ArrayList<>();
        ProcessRunner.Result base = run("git", "log", "-1", FORMAT, fromSha);
        if (!base.ok()) {
            throw new ProviderException("git log failed for '" + fromSha + "': " + base.firstErrorLine());
        }
        commits.add(parse(base.stdout().strip()));
        ProcessRunner.Result log = run("git", "log", "--reverse", "--first-parent", FORMAT, fromSha + ".." + toSha);
        if (!log.ok()) {
            throw new ProviderException("git log failed: " + log.firstErrorLine());
        }
        // 'from' must be an ancestor of 'to' on its first-parent line: a side-branch commit merged into 'to' (or
        // any other commit not actually on that line) would otherwise pair up with an unrelated predecessor and
        // silently compare the wrong build inputs.
        String previousSha = fromSha;
        // split("\n") only: String.lines() also treats a bare \r as a line terminator, and %s keeps whatever
        // characters the subject has, including one, so a bare \r would otherwise split one row into two.
        for (String line : log.stdout().split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            Row row = parseRow(line);
            if (!row.firstParent().equals(previousSha)) {
                throw new UsageException("commit '" + row.commit().sha() + "' is not a first-parent descendant of '"
                        + previousSha + "'; 'from' must be an ancestor of 'to' on its first-parent history");
            }
            commits.add(row.commit());
            previousSha = row.commit().sha();
        }
        if (commits.size() == 1) {
            throw new UsageException("no commits between '" + from + "' and '" + to + "'");
        }
        return commits;
    }

    /** A single revision as a Commit (for {@code --at}). */
    public Commit resolveOne(String rev) throws UsageException, ProviderException {
        String sha = revParse(rev);
        ProcessRunner.Result result = run("git", "log", "-1", FORMAT, sha);
        if (!result.ok()) {
            throw new ProviderException("git log failed for '" + sha + "': " + result.firstErrorLine());
        }
        return parse(result.stdout().strip());
    }

    private String revParse(String rev) throws UsageException, ProviderException {
        ProcessRunner.Result r = run("git", "rev-parse", "--verify", "--quiet", rev + "^{commit}");
        // Under --quiet, real git exits 1 (with no stderr) for a revision it simply cannot resolve. Any other
        // non-zero exit (128 for "fatal: not a git repository", a detached/broken worktree, ...) is a tool
        // failure unrelated to whether the revision itself is valid.
        if (r.exitCode() == 1) {
            throw new UsageException("unknown revision '" + rev + "'");
        }
        if (!r.ok()) {
            throw new ProviderException("git rev-parse failed for '" + rev + "': " + r.firstErrorLine());
        }
        return r.stdout().strip();
    }

    private ProcessRunner.Result run(String... command) throws ProviderException {
        try {
            return git.run(repo, TIMEOUT, List.of(command));
        } catch (ProcessException e) {
            throw new ProviderException(e.getMessage(), e);
        }
    }

    /** One parsed {@code git log} row: the commit plus its first parent, used only to validate the first-parent chain. */
    private record Row(Commit commit, String firstParent) {}

    // Amendment (code review): Commit's compact constructor throws an unchecked IllegalArgumentException for a sha
    // that isn't 40 lowercase hex chars. The CLI's walk (Task 13) catches only UsageException and ProviderException,
    // so a SHA-256 repo or a stray `git log` output line would otherwise escape as a stack trace with exit code 1
    // (the usage-error code) instead of the ProviderException exit code. Translate it here, naming the bad line.
    private static Commit parse(String line) throws ProviderException {
        return parseRow(line).commit();
    }

    private static Row parseRow(String line) throws ProviderException {
        String[] parts = line.split("\u001f", 3);
        if (parts.length < 3) {
            throw new ProviderException("unexpected git log line: '" + line + "': expected sha, parents, subject");
        }
        String firstParent = parts[1].isBlank() ? "" : parts[1].split(" ", 2)[0];
        try {
            return new Row(new Commit(parts[0], parts[2]), firstParent);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("unexpected git log line: '" + line + "': " + e.getMessage(), e);
        }
    }
}
