package io.joeyang.tracer.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Runs the worktree's own {@code gradlew} with cache debugging on and parses the task's key out of the output. */
public final class GradleKeyProvider implements KeyProvider {
    private static final String WHAT_WENT_WRONG_HEADER = "* What went wrong:";

    private final ProcessRunner runner;
    private final boolean offline;
    private final Duration timeout;

    public GradleKeyProvider(ProcessRunner runner, boolean offline, Duration timeout) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.offline = offline;
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    @Override
    public TaskKey keyFor(Path worktree, String taskPath) throws ProviderException {
        Path gradlew = worktree.resolve("gradlew");
        if (!Files.isExecutable(gradlew)) {
            throw new ProviderException("no executable gradlew in " + worktree + " (does this commit have the wrapper?)");
        }
        List<String> command = new ArrayList<>(List.of(gradlew.toString(), "--console=plain", "--no-parallel",
                "--build-cache", "-Dorg.gradle.caching.debug=true", taskPath));
        if (offline) {
            command.add("--offline");
        }
        ProcessRunner.Result result;
        try {
            result = runner.run(worktree, timeout, command);
        } catch (ProcessException e) {
            throw new ProviderException("gradle: " + e.getMessage(), e);
        }
        if (!result.ok()) {
            throw new ProviderException("gradle failed for " + taskPath + ": " + whatWentWrong(result).orElseGet(result::firstErrorLine));
        }
        String output = result.stdout() + "\n" + result.stderr();
        try {
            return CacheDebugLog.parse(output, taskPath).orElseThrow(() -> new ProviderException(
                    CacheDebugLog.nonCacheableReason(output, taskPath)
                            .map(reason -> taskPath + " is not cacheable (" + reason + "), so it has no cache key; use compileJava or fingerprint")
                            .orElse("gradle printed no cache key for " + taskPath
                                    + " (unrecognised cache debug output, or the task did not print a key)")));
        } catch (IllegalArgumentException e) {
            throw new ProviderException("gradle debug output for " + taskPath + " could not be parsed: " + e.getMessage(), e);
        }
    }

    /** The first non-blank line after Gradle's {@code * What went wrong:} header, when it printed one. */
    private static Optional<String> whatWentWrong(ProcessRunner.Result result) {
        List<String> lines = (result.stdout() + "\n" + result.stderr()).lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(WHAT_WENT_WRONG_HEADER)) {
                for (int j = i + 1; j < lines.size(); j++) {
                    String candidate = lines.get(j).strip();
                    if (!candidate.isEmpty()) {
                        return Optional.of(candidate);
                    }
                }
            }
        }
        return Optional.empty();
    }
}
