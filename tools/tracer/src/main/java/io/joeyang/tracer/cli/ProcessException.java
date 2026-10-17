package io.joeyang.tracer.cli;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

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
        return new ProcessException("'" + String.join(" ", command) + "' did not finish within " + format(timeout), null, false);
    }

    public static ProcessException interrupted(List<String> command) {
        return new ProcessException("interrupted while waiting for '" + command.get(0) + "'", null, false);
    }

    /** The process ran, but reading its stdout or stderr failed (as opposed to a start failure or a timeout). */
    public static ProcessException ioFailed(List<String> command, IOException cause) {
        return new ProcessException("reading output of '" + command.get(0) + "' failed: " + cause.getMessage(), cause, false);
    }

    /** True when the process never launched (e.g. the binary is missing) — false when it ran but timed out or was interrupted. */
    public boolean startFailed() {
        return startFailed;
    }

    /** Sub-second durations in ms (e.g. "300ms"); otherwise seconds, with one decimal place only when needed. */
    private static String format(Duration d) {
        long millis = d.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        if (millis % 1000 == 0) {
            return (millis / 1000) + "s";
        }
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }
}
