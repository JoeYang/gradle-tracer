package io.joeyang.tracer.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The Bazel oracle: {@code bazel-diff generate-hashes} per worktree and {@code get-impacted-targets} per adjacent pair.
 * External Maven artifacts are hashed individually ({@code --fineGrainedHashExternalRepos=@maven}) so one library bump
 * impacts only its dependants. Every worktree gets its own Bazel output base, so the provider runs
 * {@code bazel clean --expunge} there afterwards to stop the server and free the disk.
 */
public final class BazelDiffProvider implements ImpactProvider {
    static final String FINE_GRAINED_REPOS = "@maven";
    private static final String HOW_TO_GET = " (run tools/fetch-bazel-diff.sh or set BAZEL_DIFF=/path/to/binary)";

    private final ProcessRunner runner;
    private final Path repo;
    private final boolean offline;
    private final Duration timeout;
    private final Path binary;

    public BazelDiffProvider(ProcessRunner runner, Path repo, boolean offline, Duration timeout) {
        this(runner, repo, offline, timeout, defaultBinary(repo));
    }

    BazelDiffProvider(ProcessRunner runner, Path repo, boolean offline, Duration timeout, Path binary) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
        this.offline = offline;
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.binary = Objects.requireNonNull(binary, "binary must not be null");
    }

    /** {@code $BAZEL_DIFF} if set, else the fetched copy under tools/bin, else whatever {@code bazel-diff} is on the PATH. */
    static Path defaultBinary(Path repo) {
        String env = System.getenv("BAZEL_DIFF");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path fetched = repo.resolve("tools/bin/bazel-diff");
        return Files.isExecutable(fetched) ? fetched : Path.of("bazel-diff");
    }

    @Override
    public Path hashes(Path worktree, Path output) throws ProviderException {
        List<String> command = new ArrayList<>(List.of(binary.toString(), "generate-hashes", "-w", worktree.toString(),
                "-b", "bazel", "--fineGrainedHashExternalRepos=" + FINE_GRAINED_REPOS));
        if (offline) {
            command.add("--bazelCommandOptions=--repository_disable_download");
        }
        command.add(output.toString());
        try {
            ProcessRunner.Result result = run(repo, command);
            if (!result.ok()) {
                throw new ProviderException("bazel-diff generate-hashes failed: " + result.firstErrorLine());
            }
            if (!Files.isRegularFile(output)) {
                throw new ProviderException("bazel-diff produced no hashes file at " + output);
            }
            return output;
        } finally {
            cleanUpServer(worktree);
        }
    }

    @Override
    public Set<String> impactedTargets(Path previousHashes, Path currentHashes) throws ProviderException {
        Path impacted = currentHashes.resolveSibling(currentHashes.getFileName() + ".impacted.txt");
        List<String> command = List.of(binary.toString(), "get-impacted-targets", "-w", repo.toString(), "-b", "bazel",
                "--startingHashes", previousHashes.toString(), "--finalHashes", currentHashes.toString(), "-o", impacted.toString());
        ProcessRunner.Result result = run(repo, command);
        if (!result.ok()) {
            throw new ProviderException("bazel-diff get-impacted-targets failed: " + result.firstErrorLine());
        }
        try {
            Set<String> targets = new LinkedHashSet<>();
            Files.readAllLines(impacted).stream().map(String::strip).filter(l -> !l.isEmpty()).forEach(targets::add);
            return targets;
        } catch (IOException e) {
            throw new ProviderException("cannot read " + impacted + ": " + e.getMessage(), e);
        }
    }

    private ProcessRunner.Result run(Path cwd, List<String> command) throws ProviderException {
        try {
            return runner.run(cwd, timeout, command);
        } catch (ProcessException e) {
            throw new ProviderException("bazel-diff: " + e.getMessage() + HOW_TO_GET, e);
        }
    }

    private void cleanUpServer(Path worktree) {
        try {
            runner.run(worktree, timeout, List.of("bazel", "clean", "--expunge"));
        } catch (ProcessException e) {
            System.err.println("trace: could not clean the Bazel output base of " + worktree + " (" + e.getMessage() + ")");
        }
    }
}
