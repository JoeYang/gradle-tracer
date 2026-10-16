package io.joeyang.tracer.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Runs an external command. The single seam that lets tests inject failures without real processes. */
public interface ProcessRunner {

    /** Outcome of a process that started and finished. A non-zero exit is a Result, not an exception. */
    record Result(int exitCode, String stdout, String stderr) {
        public Result {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }

        public boolean ok() {
            return exitCode == 0;
        }

        /** Best single line to show a human: first non-blank stderr line, else a stdout line mentioning failure, else the exit code. */
        public String firstErrorLine() {
            return stderr.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst()
                    .or(() -> stdout.lines().map(String::strip)
                            .filter(l -> l.contains("FAILURE") || l.contains("error") || l.contains("ERROR"))
                            .findFirst())
                    .orElse("exit code " + exitCode);
        }
    }

    /**
     * @throws ProcessException when the process could not be started, timed out, or the thread was interrupted
     */
    Result run(Path cwd, Duration timeout, List<String> command) throws ProcessException;
}
