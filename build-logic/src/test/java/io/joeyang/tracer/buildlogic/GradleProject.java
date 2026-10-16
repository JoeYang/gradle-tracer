package io.joeyang.tracer.buildlogic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Each project gets its own local build cache, so test methods never see each other's FROM-CACHE hits. */
    GradleProject(Path dir) throws IOException {
        this(dir, dir.resolve(".build-cache"));
    }

    /** @param cacheDir local build cache for this project; two projects sharing it can be used to test FROM-CACHE across locations */
    GradleProject(Path dir, Path cacheDir) throws IOException {
        this.dir = dir;
        this.cacheDir = cacheDir;
        Files.createDirectories(dir);
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

    GradleProject write(String relative, String content) throws IOException {
        Path target = dir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return this;
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
