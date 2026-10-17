package io.joeyang.tracer.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Turns {@code --from/--to} into an ordered commit list: the base commit first, then every first-parent commit in from..to. */
public final class RevisionRange {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    // %P is every parent sha, space-separated; for a root commit it is empty. Only the first token (the first
    // parent) matters here, since the walk follows --first-parent.
    private static final String FORMAT = "--format=%H%x1f%P%x1f%s";

    private final ProcessRunner git;
    private final Path repo;

    public RevisionRange(ProcessRunner git, Path repo) {
        this.git = git;
        this.repo = repo;
    }

    public List<Commit> resolve(String from, String to) throws UsageException, ProviderException {
        String fromSha = revParse(from);
        String toSha = revParse(to);
        List<Commit> commits = new ArrayList<>();
        commits.add(parse(run("git", "log", "-1", FORMAT, fromSha).stdout().strip()));
        ProcessRunner.Result log = run("git", "log", "--reverse", "--first-parent", FORMAT, fromSha + ".." + toSha);
        if (!log.ok()) {
            throw new ProviderException("git log failed: " + log.firstErrorLine());
        }
        // 'from' must be an ancestor of 'to' on its first-parent line: a side-branch commit merged into 'to' (or
        // any other commit not actually on that line) would otherwise pair up with an unrelated predecessor and
        // silently compare the wrong build inputs.
        String previousSha = fromSha;
        for (String line : log.stdout().lines().filter(l -> !l.isBlank()).toList()) {
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

    private String revParse(String rev) throws UsageException, ProviderException {
        ProcessRunner.Result r = run("git", "rev-parse", "--verify", "--quiet", rev + "^{commit}");
        if (!r.ok()) {
            throw new UsageException("unknown revision '" + rev + "'");
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
