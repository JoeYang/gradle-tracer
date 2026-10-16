package io.joeyang.tracer.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scripted ProcessRunner: match commands by prefix of their space-joined form, in registration order. */
final class FakeProcessRunner implements ProcessRunner {
    private final Map<String, Object> scripted = new LinkedHashMap<>(); // prefix → Result | ProcessException
    final List<List<String>> calls = new ArrayList<>();
    final List<Path> cwds = new ArrayList<>();

    FakeProcessRunner on(String commandPrefix, int exit, String stdout, String stderr) {
        scripted.put(commandPrefix, new Result(exit, stdout, stderr));
        return this;
    }

    FakeProcessRunner failWith(String commandPrefix, ProcessException e) {
        scripted.put(commandPrefix, e);
        return this;
    }

    @Override
    public Result run(Path cwd, Duration timeout, List<String> command) throws ProcessException {
        calls.add(command);
        cwds.add(cwd);
        String joined = String.join(" ", command);
        for (Map.Entry<String, Object> e : scripted.entrySet()) {
            if (joined.startsWith(e.getKey())) {
                if (e.getValue() instanceof ProcessException pe) {
                    throw pe;
                }
                return (Result) e.getValue();
            }
        }
        throw new AssertionError("unexpected command: " + joined);
    }

    boolean ran(String commandPrefix) {
        return calls.stream().anyMatch(c -> String.join(" ", c).startsWith(commandPrefix));
    }
}
