package io.joeyang.tracer.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Materialises commits as detached git worktrees under a scratch root and removes them again.
 * Removal happens on {@link #close()} and from a JVM shutdown hook, so Ctrl-C leaves nothing behind.
 */
public final class WorktreePool implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final ProcessRunner git;
    private final Path repo;
    private final Path root;
    private final boolean keep;
    private final List<Path> created = new ArrayList<>();
    /** Null when {@code keep} is true: Ctrl-C must leave kept worktrees exactly where {@code --keep-worktrees} put them. */
    private final Thread shutdownHook;

    public WorktreePool(ProcessRunner git, Path repo, Path root, boolean keep) throws ProviderException {
        this(git, repo, root, keep, Runtime.getRuntime()::addShutdownHook);
    }

    /** Package-private seam: lets tests observe whether a shutdown hook was registered, without touching the real JVM hook list. */
    WorktreePool(ProcessRunner git, Path repo, Path root, boolean keep, Consumer<Thread> hookRegistrar) throws ProviderException {
        this.git = git;
        this.repo = repo;
        this.root = root;
        this.keep = keep;
        // Sweep before prune: 'git worktree prune' only forgets a worktree whose directory is already missing.
        // After --keep-worktrees or a killed run the directory is still there, so pruning first is a no-op; the
        // sweep then deletes the directory out from under git, leaving a dangling registration that makes the
        // next 'worktree add' of the same path fail with "is a missing but already registered worktree".
        sweepStaleDirectories();
        run("git", "worktree", "prune");
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

    /** A killed run leaves {@code wt-*} directories behind; git has already forgotten them (prune), so delete them. */
    private void sweepStaleDirectories() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> entries = Files.list(root)) {
            List<Path> stale = entries.filter(d -> d.getFileName().toString().startsWith("wt-")).toList();
            stale.forEach(WorktreePool::deleteRecursively);
            if (!stale.isEmpty()) {
                System.err.println("trace: removed " + stale.size() + " stale worktree dir(s) under " + root);
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
        for (Path dir : new ArrayList<>(created)) {
            remove(dir);
        }
    }

    private void remove(Path dir) {
        try {
            git.run(repo, TIMEOUT, List.of("git", "worktree", "remove", "--force", dir.toString()));
        } catch (ProcessException e) {
            System.err.println("trace: could not remove worktree " + dir + " (" + e.getMessage() + ")");
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
