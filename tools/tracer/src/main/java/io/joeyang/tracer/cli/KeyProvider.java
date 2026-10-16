package io.joeyang.tracer.cli;

import java.nio.file.Path;

/** Obtains a Gradle task's cache key from a checked-out worktree. */
public interface KeyProvider {
    TaskKey keyFor(Path worktree, String taskPath) throws ProviderException;
}
