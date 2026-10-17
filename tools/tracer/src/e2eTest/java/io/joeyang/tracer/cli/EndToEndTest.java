package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real git, real Gradle, no Bazel: appA's compile key must change when core gains a public method; appC's must not. */
class EndToEndTest {
    static final List<String> COPY = List.of("settings.gradle.kts", "gradle.properties", "gradle", "gradlew", "build-logic", "utils", "core", "lib", "app");
    @TempDir Path tmp;

    @Test
    void publicMethodInCoreChangesAppAButNotAppC() throws Exception {
        Path source = Path.of(System.getProperty("tracer.repoRoot"));
        Path fixture = tmp.resolve("fixture");
        for (String entry : COPY) {
            copyTree(source.resolve(entry), fixture.resolve(entry));
        }
        Files.writeString(fixture.resolve("settings.gradle.kts"),
                Files.readString(fixture.resolve("settings.gradle.kts")).replace("include(\"tools:tracer\")", ""));
        ProcessRunner runner = new DefaultProcessRunner();
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

        String[] appA = trace(cli, "appA");
        assertEquals("CHANGED", appA[4], String.join("|", appA));
        assertTrue(appA[5].contains("core classes"), "compile classpath consumes project deps as class dirs: " + appA[5]);

        String[] appC = trace(cli, "appC");
        assertEquals("clean", appC[4], String.join("|", appC));
    }

    private static String[] trace(Cli cli, String app) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = cli.run(Cli.Options.parse(new String[] {app, "--from", "HEAD~1", "--to", "HEAD", "--no-bazel", "--tsv"}),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(code == Cli.EXIT_OK || code == Cli.EXIT_CHANGED, "exit " + code + "\n" + err + "\n" + text);
        String[] rows = text.split("\n");
        assertEquals(2, rows.length, text);
        return rows[1].split("\t", -1);
    }

    private static void git(ProcessRunner runner, Path cwd, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessRunner.Result r = runner.run(cwd, Duration.ofMinutes(1), cmd);
        assertTrue(r.ok(), String.join(" ", cmd) + " failed: " + r.firstErrorLine());
    }

    /** True when any path segment is a Gradle output dir (`build`, `.gradle`); `build.gradle.kts` and `build-logic` are kept. */
    private static boolean isBuildOutput(Path rel) {
        for (Path segment : rel) {
            String name = segment.toString();
            if (name.equals("build") || name.equals(".gradle")) {
                return true;
            }
        }
        return false;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path rel = from.relativize(p);
                if (isBuildOutput(rel)) {
                    continue;
                }
                Path target = to.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
