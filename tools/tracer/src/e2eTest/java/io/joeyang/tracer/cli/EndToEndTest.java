package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real git, real Gradle, no Bazel: appA's compile key must change when core gains a public method; appC's must not. */
class EndToEndTest {
    static final List<String> COPY = List.of(".gitignore", "settings.gradle.kts", "gradle.properties", "gradle",
            "gradlew", "build-logic", "utils", "core", "lib", "app");
    /**
     * Isolates every fixture git call from the developer's own global config: a global {@code commit.gpgsign=true}
     * would fail the baseline commit, a global {@code core.hooksPath} could run an unrelated hook, and a global
     * {@code core.excludesFile} listing {@code *.jar} would silently drop {@code gradle-wrapper.jar} from
     * {@code add -A} — every later row then reads BUILD-FAILED instead of a verdict.
     */
    static final List<String> SAFE_GIT_CONFIG = List.of(
            "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "-c", "core.excludesFile=/dev/null");
    @TempDir Path tmp;

    @Test
    void publicMethodInCoreChangesAppAButNotAppC() throws Exception {
        Path source = Path.of(System.getProperty("tracer.repoRoot"));
        Path fixture = tmp.resolve("fixture");
        ProcessRunner runner = new DefaultProcessRunner();
        copyTracked(runner, source, fixture);

        String settings = Files.readString(fixture.resolve("settings.gradle.kts"));
        String patched = settings.replace("include(\"tools:tracer\")", "");
        assertNotEquals(settings, patched, "settings.gradle.kts no longer contains the tools:tracer include to remove");
        Files.writeString(fixture.resolve("settings.gradle.kts"), patched);

        git(runner, fixture, "init", "-q", "-b", "main");
        git(runner, fixture, "-c", "user.email=e2e@example.com", "-c", "user.name=e2e", "add", "-A");
        git(runner, fixture, "-c", "user.email=e2e@example.com", "-c", "user.name=e2e", "commit", "-q", "-m", "baseline");
        Path greeter = fixture.resolve("core/src/main/java/io/joeyang/tracer/core/Greeter.java");
        String src = Files.readString(greeter);
        int brace = src.lastIndexOf('}');
        Files.writeString(greeter, src.substring(0, brace) + "\n    public String shout(String name) {\n        return greet(name).toUpperCase();\n    }\n}\n");
        git(runner, fixture, "-c", "user.email=e2e@example.com", "-c", "user.name=e2e", "commit", "-q", "-am", "core: add shout");

        KeyProvider keys = new GradleKeyProvider(runner, false, Duration.ofMinutes(10));
        Cli cli = new Cli(runner, fixture, tmp.resolve("scratch"), keys, null);

        TraceResult appA = trace(cli, "appA", "--task", "compileJava");
        assertEquals(Cli.EXIT_CHANGED, appA.code(), appA.text());
        assertEquals("base", appA.rows()[0][4], appA.text());
        assertEquals("CHANGED", appA.rows()[1][4], appA.text());
        assertTrue(appA.rows()[1][5].contains("core classes"),
                "compile classpath consumes project deps as class dirs: " + appA.rows()[1][5]);

        TraceResult appC = trace(cli, "appC", "--task", "compileJava");
        assertEquals(Cli.EXIT_OK, appC.code(), appC.text());
        assertEquals("base", appC.rows()[0][4], appC.text());
        assertEquals("clean", appC.rows()[1][4], appC.text());

        TraceResult appARuntime = trace(cli, "appA");
        assertEquals("CHANGED", appARuntime.rows()[1][4], appARuntime.text());
        assertTrue(appARuntime.rows()[1][5].contains("core.jar"),
                "the default task is fingerprint, whose runtime classpath consumes project deps as jars: "
                        + appARuntime.rows()[1][5]);
    }

    private record TraceResult(int code, String[][] rows, String text) {}

    private static TraceResult trace(Cli cli, String app, String... extra) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of(app, "--from", "HEAD~1", "--to", "HEAD", "--no-bazel", "--tsv"));
        args.addAll(List.of(extra));
        int code = cli.run(Cli.Options.parse(args.toArray(String[]::new)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(code == Cli.EXIT_OK || code == Cli.EXIT_CHANGED, "exit " + code + "\n" + err + "\n" + text);
        String[] lines = text.split("\n");
        assertEquals(2, lines.length, text);
        String[][] rows = {lines[0].split("\t", -1), lines[1].split("\t", -1)};
        return new TraceResult(code, rows, text);
    }

    private static void git(ProcessRunner runner, Path cwd, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(SAFE_GIT_CONFIG);
        cmd.addAll(List.of(args));
        ProcessRunner.Result r = runner.run(cwd, Duration.ofMinutes(1), cmd);
        assertTrue(r.ok(), String.join(" ", cmd) + " failed: " + r.firstErrorLine());
    }

    /**
     * Copies only the paths {@code git ls-files} tracks under {@link #COPY}, so untracked build output and
     * scratch files from the developer's own working tree never leak into the fixture.
     */
    private static void copyTracked(ProcessRunner runner, Path source, Path fixture) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "ls-files", "-z", "--"));
        cmd.addAll(COPY);
        ProcessRunner.Result r = runner.run(source, Duration.ofMinutes(1), cmd);
        assertTrue(r.ok(), "git ls-files failed: " + r.firstErrorLine());
        for (String rel : r.stdout().split("\0")) {
            if (rel.isEmpty()) {
                continue;
            }
            Path from = source.resolve(rel);
            Path to = fixture.resolve(rel);
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }
}
