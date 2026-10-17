package io.joeyang.tracer.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The Bazel oracle: {@code bazel-diff generate-hashes} per worktree and {@code get-impacted-targets} per adjacent pair.
 * External Maven artifacts are hashed individually ({@code --fineGrainedHashExternalRepos=@maven}) so one library bump
 * impacts only its dependants. Every worktree gets its own Bazel output base, so the provider runs
 * {@code bazel clean --expunge} there afterwards to stop the server and free the disk.
 */
public final class BazelDiffProvider implements ImpactProvider {
    static final String FINE_GRAINED_REPOS = "@maven";
    private static final String HOW_TO_GET = " (run tools/fetch-bazel-diff.sh or set BAZEL_DIFF=/path/to/binary)";
    /** A Bazel label such as {@code //app/appA:appA}, {@code //lib}, or {@code @maven//:guava}. */
    private static final Pattern BAZEL_LABEL = Pattern.compile("^(@[^/]*)?//[^:]*(:[^:]+)?$");

    private final ProcessRunner runner;
    private final Path repo;
    private final boolean offline;
    private final Duration timeout;
    private final Path binary;
    private final PrintStream warnings;

    public BazelDiffProvider(ProcessRunner runner, Path repo, boolean offline, Duration timeout) {
        this(runner, repo, offline, timeout, defaultBinary(repo));
    }

    BazelDiffProvider(ProcessRunner runner, Path repo, boolean offline, Duration timeout, Path binary) {
        this(runner, repo, offline, timeout, binary, System.err);
    }

    BazelDiffProvider(ProcessRunner runner, Path repo, boolean offline, Duration timeout, Path binary, PrintStream warnings) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.repo = Objects.requireNonNull(repo, "repo must not be null");
        this.offline = offline;
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.binary = Objects.requireNonNull(binary, "binary must not be null");
        this.warnings = Objects.requireNonNull(warnings, "warnings must not be null");
    }

    /** {@code $BAZEL_DIFF} if set, else the fetched copy under tools/bin, else whatever {@code bazel-diff} is on the PATH. */
    static Path defaultBinary(Path repo) {
        return defaultBinary(repo, System::getenv);
    }

    /** Same resolution, with the environment lookup injectable so tests do not depend on the real {@code $BAZEL_DIFF}. */
    static Path defaultBinary(Path repo, Function<String, String> getenv) {
        String env = getenv.apply("BAZEL_DIFF");
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
        // A leftover file from a previous run must not be mistaken for this run's output if bazel-diff fails to write one.
        try {
            Files.deleteIfExists(output);
        } catch (IOException e) {
            throw new ProviderException("cannot remove the stale hashes file " + output + ": " + e.getMessage(), e);
        }
        boolean skipCleanup = false;
        try {
            ProcessRunner.Result result;
            try {
                result = run(repo, command);
            } catch (ProviderException e) {
                // Nothing to clean up only when bazel-diff never launched (e.g. the binary is missing): no Bazel server ran
                // in the worktree. A timeout or interruption may well have started one, so cleanup must still run then.
                if (e.getCause() instanceof ProcessException pe && pe.startFailed()) {
                    skipCleanup = true;
                }
                throw e;
            }
            if (!result.ok()) {
                throw new ProviderException("bazel-diff generate-hashes failed for " + worktree + ": " + bazelError(result));
            }
            if (!Files.isRegularFile(output)) {
                throw new ProviderException("bazel-diff produced no hashes file at " + output);
            }
            return output;
        } finally {
            if (!skipCleanup) {
                cleanUpServer(worktree);
            }
        }
    }

    @Override
    public Set<String> impactedTargets(Path previousHashes, Path currentHashes) throws ProviderException {
        Path impacted = currentHashes.resolveSibling(currentHashes.getFileName() + ".impacted.txt");
        List<String> command = List.of(binary.toString(), "get-impacted-targets", "-w", repo.toString(), "-b", "bazel",
                "--startingHashes", previousHashes.toString(), "--finalHashes", currentHashes.toString(), "-o", impacted.toString());
        ProcessRunner.Result result = run(repo, command);
        if (!result.ok()) {
            throw new ProviderException("bazel-diff get-impacted-targets failed for " + repo + ": " + bazelError(result));
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(impacted);
        } catch (IOException e) {
            String reason = e.getMessage() == null || e.getMessage().equals(impacted.toString())
                    ? e.getClass().getSimpleName() : e.getMessage();
            throw new ProviderException("cannot read " + impacted + ": " + reason, e);
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!BAZEL_LABEL.matcher(line).matches()) {
                throw new ProviderException("bazel-diff wrote a line that is not a Bazel label ('" + line + "') in " + impacted);
            }
            targets.add(line);
        }
        return Collections.unmodifiableSet(targets);
    }

    private ProcessRunner.Result run(Path cwd, List<String> command) throws ProviderException {
        try {
            return runner.run(cwd, timeout, command);
        } catch (ProcessException e) {
            throw new ProviderException("bazel-diff: " + e.getMessage() + HOW_TO_GET, e);
        }
    }

    /**
     * The real Bazel error, not bazel-diff's own wrapper line ({@code "[Error] Bazel query failed, exit code N: ..."}),
     * which never names the actual cause. Prefers a line mentioning "download is disabled" (a cold, offline output base),
     * else the first line Bazel itself prefixes with {@code ERROR:}, else the generic {@link ProcessRunner.Result#firstErrorLine()}.
     */
    private static String bazelError(ProcessRunner.Result result) {
        List<String> lines = (result.stdout() + "\n" + result.stderr()).lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        return lines.stream().filter(l -> l.contains("download is disabled")).findFirst()
                .or(() -> lines.stream().filter(l -> l.startsWith("ERROR:")).findFirst())
                .orElseGet(result::firstErrorLine);
    }

    private void cleanUpServer(Path worktree) {
        try {
            ProcessRunner.Result result = runner.run(worktree, timeout, List.of("bazel", "clean", "--expunge"));
            if (!result.ok()) {
                warnings.println("trace: bazel clean --expunge failed in " + worktree + ": " + result.firstErrorLine());
            }
        } catch (ProcessException e) {
            warnings.println("trace: could not clean the Bazel output base of " + worktree + " (" + e.getMessage() + ")");
        }
    }
}
