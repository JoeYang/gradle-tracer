package io.joeyang.tracer.buildlogic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;

/** Writes a throwaway Gradle project that can apply the convention plugins under test. */
final class GradleProject {
    static final String CATALOG = """
            [versions]
            junit = "6.1.3"
            [libraries]
            junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
            junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
            """;

    final Path dir;
    final Path cacheDir;

    /** @param cacheDir shared local build cache; pass the same dir to two projects to test FROM-CACHE across locations */
    GradleProject(Path dir, Path cacheDir) throws IOException {
        this(dir, cacheDir, true);
    }

    /** @param writeScaffold false wraps an existing directory (see {@link #copyTo}) without overwriting its files */
    private GradleProject(Path dir, Path cacheDir, boolean writeScaffold) throws IOException {
        this.dir = dir;
        this.cacheDir = cacheDir;
        Files.createDirectories(dir);
        if (!writeScaffold) {
            return;
        }
        write("settings.gradle.kts", """
                dependencyResolutionManagement {
                    repositories { mavenCentral() }
                }
                buildCache {
                    local { directory = file("%s") }
                }
                rootProject.name = "sample"
                """.formatted(cacheDir.toString().replace("\\", "/")));
        write("gradle/libs.versions.toml", CATALOG);
        write("gradle.properties", "org.gradle.caching=true\n");
    }

    GradleProject(Path dir) throws IOException {
        this(dir, dir.resolve(".build-cache"));
    }

    GradleProject write(String relative, String content) throws IOException {
        Path target = dir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return this;
    }

    /** Copies sources and build files (not build outputs) to another directory sharing the same cache. */
    GradleProject copyTo(Path other) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.toList()) {
                Path rel = dir.relativize(p);
                String first = rel.getNameCount() > 0 ? rel.getName(0).toString() : "";
                if (first.equals("build") || first.equals(".gradle") || first.equals(".build-cache")) {
                    continue;
                }
                Path target = other.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
        return new GradleProject(other, cacheDir, false);
    }

    BuildResult run(String... args) {
        return runner(args).build();
    }

    BuildResult runAndFail(String... args) {
        return runner(args).buildAndFail();
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput();
    }
}
