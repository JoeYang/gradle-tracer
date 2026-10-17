package io.joeyang.tracer.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scripted ProcessRunner: match commands by whole-token prefix, in registration order. */
final class FakeProcessRunner implements ProcessRunner {
    private final Map<String, Object> scripted = new LinkedHashMap<>(); // prefix → Result | ProcessException
    final List<List<String>> calls = new ArrayList<>();
    final List<Path> cwds = new ArrayList<>();
    final List<Duration> timeouts = new ArrayList<>();

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
        List<String> commandCopy = List.copyOf(command);
        calls.add(commandCopy);
        cwds.add(cwd);
        timeouts.add(timeout);
        for (Map.Entry<String, Object> e : scripted.entrySet()) {
            if (matchesPrefix(commandCopy, e.getKey())) {
                if (e.getValue() instanceof ProcessException pe) {
                    throw pe;
                }
                return (Result) e.getValue();
            }
        }
        throw new AssertionError("unexpected command: " + String.join(" ", commandCopy));
    }

    boolean ran(String commandPrefix) {
        return calls.stream().anyMatch(c -> matchesPrefix(c, commandPrefix));
    }

    /** True when {@code command} starts with every token of {@code commandPrefix}, split on whitespace. */
    private static boolean matchesPrefix(List<String> command, String commandPrefix) {
        List<String> prefix = List.of(commandPrefix.trim().split("\\s+"));
        return command.size() >= prefix.size() && command.subList(0, prefix.size()).equals(prefix);
    }
}
