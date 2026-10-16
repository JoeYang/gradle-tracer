package io.joeyang.tracer.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FingerprintTaskFunctionalTest {
    static final String DEP_SOURCE = """
            package dep;
            /** Comment line one. */
            public final class Dep {
                public static String value() { return "v1"; }
            }
            """;

    @TempDir Path tmp;
    GradleProject project;

    @BeforeEach
    void setUp() throws IOException {
        project = new GradleProject(tmp.resolve("a"), tmp.resolve("cache"));
        project.write("settings.gradle.kts", Files.readString(project.dir.resolve("settings.gradle.kts")) + "include(\"dep\")\n");
        project.write("dep/build.gradle.kts", "plugins { id(\"tracer.java-library\") }\n");
        project.write("dep/src/main/java/dep/Dep.java", DEP_SOURCE);
        project.write("build.gradle.kts", """
                plugins { id("tracer.java-application") }
                dependencies { implementation(project(":dep")) }
                application { mainClass.set("app.Main") }
                """);
        project.write("src/main/java/app/Main.java", """
                package app;
                public final class Main {
                    public static void main(String[] args) { System.out.println(dep.Dep.value()); }
                }
                """);
    }

    @Test
    void runsAndListsTheRuntimeClasspath() throws IOException {
        BuildResult result = project.run(":fingerprint");

        assertEquals(TaskOutcome.SUCCESS, result.task(":fingerprint").getOutcome());
        String marker = Files.readString(project.dir.resolve("build/fingerprint/runtime-classpath.txt"));
        assertTrue(marker.contains("dep.jar"), marker);
    }

    @Test
    void secondRunIsUpToDate() {
        project.run(":fingerprint");
        BuildResult again = project.run(":fingerprint");

        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":fingerprint").getOutcome());
    }

    @Test
    void keyDoesNotDependOnTheProjectLocation() throws IOException {
        project.run(":fingerprint");
        GradleProject moved = project.copyTo(tmp.resolve("b"));

        BuildResult result = moved.run(":fingerprint");

        assertEquals(TaskOutcome.FROM_CACHE, result.task(":fingerprint").getOutcome(),
                "same sources in a different directory must hit the cache");
    }

    @Test
    void keyChangesWhenADependencyBodyChangesButCompileKeyDoesNot() throws IOException {
        project.run(":fingerprint");
        project.write("dep/src/main/java/dep/Dep.java", DEP_SOURCE.replace("\"v1\"", "\"v2\""));

        BuildResult result = project.run(":fingerprint");

        assertEquals(TaskOutcome.SUCCESS, result.task(":fingerprint").getOutcome(), "runtime bytes changed");
        assertEquals(TaskOutcome.UP_TO_DATE, result.task(":compileJava").getOutcome(),
                "ABI unchanged, so the app's compileJava must be avoided");
    }

    @Test
    void keyUnchangedOnCommentOnlyEdit() throws IOException {
        project.run(":fingerprint");
        project.write("dep/src/main/java/dep/Dep.java", DEP_SOURCE.replace("Comment line one.", "Comment line two."));

        BuildResult result = project.run(":fingerprint");

        assertEquals(TaskOutcome.SUCCESS, result.task(":dep:compileJava").getOutcome(), "source changed, so dep recompiles");
        assertEquals(TaskOutcome.UP_TO_DATE, result.task(":fingerprint").getOutcome(), "but the bytecode is identical");
    }

    @Test
    void cacheDebugOutputNamesTheKey() {
        BuildResult result = project.run(":fingerprint", "-Dorg.gradle.caching.debug=true");

        Pattern key = Pattern.compile("Build cache key for task ':fingerprint' is [0-9a-f]{32}");
        assertTrue(key.matcher(result.getOutput()).find(), result.getOutput());
    }

    @Test
    void keyChangesWhenATransitiveDependencyVersionChangesButCompileKeyDoesNot() throws IOException {
        String depWithGson = """
                plugins { id("tracer.java-library") }
                dependencies { implementation("com.google.code.gson:gson:%s") }
                """;
        project.write("dep/build.gradle.kts", depWithGson.formatted("2.10.1"));
        project.run(":fingerprint");
        project.write("dep/build.gradle.kts", depWithGson.formatted("2.11.0"));

        BuildResult result = project.run(":fingerprint");

        assertEquals(TaskOutcome.SUCCESS, result.task(":fingerprint").getOutcome(), "a different gson jar is a different runtime input");
        assertEquals(TaskOutcome.UP_TO_DATE, result.task(":compileJava").getOutcome(),
                "gson is dep's implementation detail, not part of dep's api, so the app's compileJava must be avoided");
    }

    @Test
    void keyChangesWhenAPublicMethodIsAddedToADependency() throws IOException {
        project.run(":fingerprint");
        project.write("dep/src/main/java/dep/Dep.java", DEP_SOURCE.replace(
                "public static String value() { return \"v1\"; }",
                "public static String value() { return \"v1\"; }\n    public static String extra() { return \"x\"; }"));

        BuildResult result = project.run(":fingerprint");

        assertEquals(TaskOutcome.SUCCESS, result.task(":fingerprint").getOutcome(), "runtime bytes changed");
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome(),
                "dep's ABI changed, so the app's compileJava must rerun");
    }

    @Test
    void failsClearlyWhenTheClasspathCannotResolve() throws IOException {
        project.write("build.gradle.kts", """
                plugins { id("tracer.java-application") }
                dependencies {
                    implementation(project(":dep"))
                    implementation("com.example:missing:1.0")
                }
                application { mainClass.set("app.Main") }
                """);

        BuildResult result = project.runAndFail(":fingerprint");

        assertTrue(result.getOutput().contains("com.example:missing:1.0"), result.getOutput());
    }
}
