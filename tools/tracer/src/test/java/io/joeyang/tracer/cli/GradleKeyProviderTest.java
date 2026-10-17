package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleKeyProviderTest {
    static final String TASK = ":app:appA:compileJava";
    @TempDir Path worktree;
    String gradlew;

    @BeforeEach
    void setUp() throws IOException {
        Path script = worktree.resolve("gradlew");
        Files.writeString(script, "#!/bin/sh\n");
        script.toFile().setExecutable(true);
        gradlew = script.toString();
    }

    @Test void runsGradlewWithDebugFlagsInTheWorktree() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner().on(gradlew, 0, CacheDebugFixtures.COMPILE_JAVA_LOG, "");

        TaskKey key = new GradleKeyProvider(runner, false, Duration.ofMinutes(1)).keyFor(worktree, TASK);

        assertEquals("35c0f7cbe157cd2e30090cc5510cec8c", key.key());
        assertEquals(List.of(gradlew, "--console=plain", "--no-parallel", "--build-cache", "-Dorg.gradle.caching.debug=true", TASK), runner.calls.get(0));
        assertEquals(worktree, runner.cwds.get(0));
    }

    @Test void offlineAppendsTheFlag() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner().on(gradlew, 0, CacheDebugFixtures.COMPILE_JAVA_LOG, "");
        new GradleKeyProvider(runner, true, Duration.ofMinutes(1)).keyFor(worktree, TASK);
        assertTrue(runner.calls.get(0).contains("--offline"));
    }

    @Test void missingGradlewIsAProviderError() throws IOException {
        Files.delete(worktree.resolve("gradlew"));
        FakeProcessRunner runner = new FakeProcessRunner();
        ProviderException e = assertThrows(ProviderException.class,
                () -> new GradleKeyProvider(runner, false, Duration.ofMinutes(1)).keyFor(worktree, TASK));
        assertTrue(e.getMessage().contains("gradlew"), e.getMessage());
        assertTrue(runner.calls.isEmpty());
    }

    @Test void buildFailureCarriesTheFirstErrorLine() {
        FakeProcessRunner runner = new FakeProcessRunner().on(gradlew, 1,
                "> Task :core:compileJava FAILED\n", "Greeter.java:12: error: ';' expected\n");
        ProviderException e = assertThrows(ProviderException.class,
                () -> new GradleKeyProvider(runner, false, Duration.ofMinutes(1)).keyFor(worktree, TASK));
        assertTrue(e.getMessage().contains("';' expected"), e.getMessage());
    }

    @Test void timeoutIsAProviderError() {
        FakeProcessRunner runner = new FakeProcessRunner()
                .failWith(gradlew, ProcessException.timeout(List.of(gradlew), Duration.ofMinutes(1)));
        ProviderException e = assertThrows(ProviderException.class,
                () -> new GradleKeyProvider(runner, false, Duration.ofMinutes(1)).keyFor(worktree, TASK));
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
    }

    @Test void successWithoutAKeyLineIsAProviderErrorNotASilentClean() {
        FakeProcessRunner runner = new FakeProcessRunner().on(gradlew, 0, "> Task :app:appA:compileJava UP-TO-DATE\nBUILD SUCCESSFUL\n", "");
        ProviderException e = assertThrows(ProviderException.class,
                () -> new GradleKeyProvider(runner, false, Duration.ofMinutes(1)).keyFor(worktree, TASK));
        assertTrue(e.getMessage().contains("no cache key"), e.getMessage());
        assertTrue(e.getMessage().contains("unrecognised cache debug output"), e.getMessage());
    }

    @Test void nonCacheableTaskIsExplainedNotMisreported() {
        FakeProcessRunner runner = new FakeProcessRunner().on(gradlew, 0, CacheDebugFixtures.COMPILE_JAVA_LOG, "");
        ProviderException e = assertThrows(ProviderException.class,
                () -> new GradleKeyProvider(runner, false, Duration.ofMinutes(1)).keyFor(worktree, ":core:jar"));
        assertTrue(e.getMessage().contains("not cacheable"), e.getMessage());
        assertTrue(e.getMessage().contains("Not worth caching"), e.getMessage());
    }
}
