package io.joeyang.tracer.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Runs the worktree's own {@code gradlew} with cache debugging on and parses the task's key out of the output. */
public final class GradleKeyProvider implements KeyProvider {
    private final ProcessRunner runner;
    private final boolean offline;
    private final Duration timeout;

    public GradleKeyProvider(ProcessRunner runner, boolean offline, Duration timeout) {
        this.runner = runner;
        this.offline = offline;
        this.timeout = timeout;
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
            throw new ProviderException("gradle failed: " + result.firstErrorLine());
        }
        String output = result.stdout() + "\n" + result.stderr();
        return CacheDebugLog.parse(output, taskPath).orElseThrow(() -> new ProviderException(
                CacheDebugLog.nonCacheableReason(output, taskPath)
                        .map(reason -> taskPath + " is not cacheable (" + reason + "), so it has no cache key; use compileJava or fingerprint")
                        .orElse("gradle printed no cache key for " + taskPath + " (is org.gradle.caching=true at this commit?)")));
    }
}
