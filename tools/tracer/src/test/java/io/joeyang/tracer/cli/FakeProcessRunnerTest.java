package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeProcessRunnerTest {
    @Test void recordedCallIsAnImmutableCopyOfTheCommand() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner().on("echo hi", 0, "", "");
        List<String> command = new ArrayList<>(List.of("echo", "hi"));

        runner.run(Path.of("."), Duration.ofSeconds(1), command);
        command.add("mutated");

        assertEquals(List.of("echo", "hi"), runner.calls.get(0));
        assertThrows(UnsupportedOperationException.class, () -> runner.calls.get(0).add("x"));
    }

    @Test void recordsTheTimeoutOfEachCall() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner().on("echo hi", 0, "", "");

        runner.run(Path.of("."), Duration.ofMinutes(3), List.of("echo", "hi"));

        assertEquals(List.of(Duration.ofMinutes(3)), runner.timeouts);
    }

    @Test void wholeTokenMatchPreventsACharacterLevelPrefixFromShadowing() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner()
                .on("git log", 0, "wrong match", "")
                .on("git logout", 0, "correct match", "");

        ProcessRunner.Result result = runner.run(Path.of("."), Duration.ofSeconds(1), List.of("git", "logout"));

        assertEquals("correct match", result.stdout());
    }
}
