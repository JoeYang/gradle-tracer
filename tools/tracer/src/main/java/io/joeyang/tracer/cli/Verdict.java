package io.joeyang.tracer.cli;

import java.util.List;
import java.util.Optional;

/**
 * One table row.
 *
 * @param key present unless the build failed
 * @param culprits human-readable names of inputs that changed (jar names, source paths, property names, "build logic")
 * @param error first error line when status is BUILD_FAILED
 */
public record Verdict(Commit commit, Status status, Optional<TaskKey> key, BazelStatus bazel,
                      List<String> culprits, Optional<String> error) {
    public Verdict {
        culprits = List.copyOf(culprits);
    }

    public boolean bazelDisagrees() {
        return (status == Status.CHANGED && bazel == BazelStatus.CLEAN)
                || (status == Status.CLEAN && bazel == BazelStatus.IMPACTED);
    }
}
