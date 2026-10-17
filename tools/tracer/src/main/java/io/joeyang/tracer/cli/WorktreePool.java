package io.joeyang.tracer.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Materialises commits as detached git worktrees under a scratch root and removes them again.
 * Removal happens on {@link #close()} and from a JVM shutdown hook, so Ctrl-C leaves nothing behind, unless
 * {@code keep} is set.
 */
public final class WorktreePool implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    /** A worktree directory's own name: {@code wt-} plus a 7-hex-char short sha, nothing else. */
    private static final Pattern WT_DIR_NAME = Pattern.compile("wt-[0-9a-f]{7}");

    private final ProcessRunner git;
    private final Path repo;
    private final Path root;
    private final boolean keep;
    private final PrintStream warnings;
    private final Set<Path> created = Collections.synchronizedSet(new LinkedHashSet<>());
    /** Null when {@code keep} is true: Ctrl-C must leave kept worktrees exactly where {@code --keep-worktrees} put them. */
    private final Thread shutdownHook;

    public WorktreePool(ProcessRunner git, Path repo, Path root, boolean keep) throws ProviderException {
        this(git, repo, root, keep, System.err, Runtime.getRuntime()::addShutdownHook);
    }

    /** Package-private seam: lets tests capture warnings and observe hook registration without touching real I/O or the JVM hook list. */
    WorktreePool(ProcessRunner git, Path repo, Path root, boolean keep, PrintStream warnings, Consumer<Thread> hookRegistrar)
            throws ProviderException {
        this.git = Objects.requireNonNull(git, "git must not be null");
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
        this.root = Objects.requireNonNull(root, "root must not be null");
        this.keep = keep;
        this.warnings = Objects.requireNonNull(warnings, "warnings must not be null");
        Objects.requireNonNull(hookRegistrar, "hookRegistrar must not be null");
        // Sweep before prune: 'git worktree prune' only forgets a worktree whose directory is already missing.
        // After --keep-worktrees or a killed run the directory is still there, so pruning first is a no-op; the
        // sweep then deletes the directory out from under git, leaving a dangling registration that makes the
        // next 'worktree add' of the same path fail with "is a missing but already registered worktree".
        sweepStaleDirectories();
        ProcessRunner.Result pruneResult = run("git", "worktree", "prune");
        if (!pruneResult.ok()) {
            throw new ProviderException("git worktree prune failed: " + pruneResult.firstErrorLine());
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ProviderException("cannot create worktree root " + root + ": " + e.getMessage(), e);
        }
        this.shutdownHook = keep ? null : new Thread(this::removeAll, "worktree-cleanup");
        if (shutdownHook != null) {
            hookRegistrar.accept(shutdownHook);
        }
    }

    /** A killed run (or a kept one) leaves {@code wt-*} directories behind; delete them before prune forgets them. */
    private void sweepStaleDirectories() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(root)) {
            List<Path> stale = entries.filter(Files::isDirectory)
                    .filter(d -> WT_DIR_NAME.matcher(d.getFileName().toString()).matches())
                    .toList();
            stale.forEach(WorktreePool::deleteRecursively);
            if (!stale.isEmpty()) {
                warnings.println("trace: removed " + stale.size() + " stale worktree dir(s) under " + root);
            }
        } catch (IOException ignored) {
            // best effort; checkout() deletes a same-named leftover anyway
        }
    }

    public Path checkout(Commit commit) throws ProviderException {
        Path dir = root.resolve("wt-" + commit.shortSha());
        if (Files.exists(dir)) {
            // Unregister before deleting: if this directory is still a registered worktree (left behind between
            // the constructor's one-time sweep and this call), deleting it without 'git worktree remove' first
            // would leave git thinking it still exists, and the 'add' below would fail as "missing but already
            // registered". Best effort: if it was never registered, this fails harmlessly and is ignored.
            try {
                git.run(repo, TIMEOUT, List.of("git", "worktree", "remove", "--force", dir.toString()));
            } catch (ProcessException ignored) {
                // see above
            }
            deleteRecursively(dir);
        }
        ProcessRunner.Result r = run("git", "worktree", "add", "--detach", dir.toString(), commit.sha());
        if (!r.ok()) {
            throw new ProviderException("git worktree add failed for " + commit.shortSha() + ": " + r.firstErrorLine());
        }
        created.add(dir);
        return dir;
    }

    /** Removes one worktree early to bound disk usage during long ranges. */
    public void release(Path dir) {
        if (!created.contains(dir)) {
            throw new IllegalArgumentException("not a tracked worktree: " + dir);
        }
        remove(dir);
    }

    @Override
    public void close() {
        if (!keep) {
            removeAll();
        }
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // the hook itself is running; nothing to do
            }
        }
    }

    private void removeAll() {
        List<Path> snapshot;
        synchronized (created) {
            snapshot = new ArrayList<>(created);
        }
        for (Path dir : snapshot) {
            remove(dir);
        }
    }

    private void remove(Path dir) {
        try {
            ProcessRunner.Result r = git.run(repo, TIMEOUT, List.of("git", "worktree", "remove", "--force", dir.toString()));
            if (!r.ok()) {
                warnings.println("trace: could not remove worktree " + dir + " (" + r.firstErrorLine() + ")");
            }
        } catch (ProcessException e) {
            warnings.println("trace: could not remove worktree " + dir + " (" + e.getMessage() + ")");
        }
        deleteRecursively(dir);
        created.remove(dir);
    }

    private ProcessRunner.Result run(String... command) throws ProviderException {
        try {
            return git.run(repo, TIMEOUT, List.of(command));
        } catch (ProcessException e) {
            throw new ProviderException(e.getMessage(), e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort; git worktree remove already did the real work
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
