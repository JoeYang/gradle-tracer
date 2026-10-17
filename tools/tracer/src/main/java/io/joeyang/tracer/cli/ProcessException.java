package io.joeyang.tracer.cli;

import java.time.Duration;
import java.util.List;

/** The process could not run to completion (as opposed to running and failing). */
public final class ProcessException extends Exception {
    private final boolean startFailed;

    private ProcessException(String message, Throwable cause, boolean startFailed) {
        super(message, cause);
        this.startFailed = startFailed;
    }

    public static ProcessException cannotStart(List<String> command, Throwable cause) {
        return new ProcessException("cannot start '" + command.get(0) + "': " + cause.getMessage(), cause, true);
    }

    public static ProcessException timeout(List<String> command, Duration timeout) {
        return new ProcessException("'" + String.join(" ", command) + "' did not finish within " + timeout.toSeconds() + "s", null, false);
    }

    public static ProcessException interrupted(List<String> command) {
        return new ProcessException("interrupted while waiting for '" + command.get(0) + "'", null, false);
    }

    /** True when the process never launched (e.g. the binary is missing) — false when it ran but timed out or was interrupted. */
    public boolean startFailed() {
        return startFailed;
    }
}
