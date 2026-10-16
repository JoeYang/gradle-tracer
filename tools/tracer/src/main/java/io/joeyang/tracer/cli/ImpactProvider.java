package io.joeyang.tracer.cli;

import java.nio.file.Path;
import java.util.Set;

/** The Bazel side: hash a worktree, then ask which targets differ between two hash files. */
public interface ImpactProvider {
    /** Writes the hashes of every target in {@code worktree} to {@code output} and returns it. */
    Path hashes(Path worktree, Path output) throws ProviderException;

    /** Labels impacted between two hash files, e.g. {@code //app/appA:appA}. */
    Set<String> impactedTargets(Path previousHashes, Path currentHashes) throws ProviderException;
}
