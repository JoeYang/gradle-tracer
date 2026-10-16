package io.joeyang.tracer.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConventionPluginsFunctionalTest {
    @TempDir Path tmp;
    GradleProject project;

    @BeforeEach
    void setUp() throws IOException {
        project = new GradleProject(tmp);
        project.write("src/main/java/sample/Hello.java", """
                package sample;
                public final class Hello {
                    public static String say() { return "hi"; }
                }
                """);
        project.write("src/test/java/sample/HelloTest.java", """
                package sample;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import org.junit.jupiter.api.Test;
                class HelloTest {
                    @Test void says() { assertEquals("hi", Hello.say()); }
                }
                """);
    }

    @Test
    void javaLibraryCompilesToJava21AndRunsJUnitPlatformTests() throws IOException {
        project.write("build.gradle.kts", "plugins { id(\"tracer.java-library\") }\n");

        BuildResult result = project.run("test", "jar");

        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome());
        byte[] cls = Files.readAllBytes(tmp.resolve("build/classes/java/main/sample/Hello.class"));
        int major = ((cls[6] & 0xff) << 8) | (cls[7] & 0xff);
        assertEquals(65, major, "class file major version must be Java 21 (65)");
    }

    @Test
    void locksAllConfigurationsWhenAskedToWriteLocks() throws IOException {
        project.write("build.gradle.kts", "plugins { id(\"tracer.java-library\") }\n");

        project.run("dependencies", "--write-locks");

        String lock = Files.readString(tmp.resolve("gradle.lockfile"));
        assertTrue(lock.contains("org.junit.jupiter:junit-jupiter:6.1.3="), lock);
    }

    @Test
    void javaApplicationInstallsALauncher() throws IOException {
        project.write("build.gradle.kts", """
                plugins { id("tracer.java-application") }
                application { mainClass.set("sample.Hello") }
                """);

        BuildResult result = project.run("installDist");

        assertEquals(TaskOutcome.SUCCESS, result.task(":installDist").getOutcome());
        assertTrue(Files.isExecutable(tmp.resolve("build/install/sample/bin/sample")));
    }

    @Test
    void jarsAreReproducible() throws IOException {
        project.write("build.gradle.kts", "plugins { id(\"tracer.java-library\") }\n");

        project.run("jar");
        byte[] first = Files.readAllBytes(tmp.resolve("build/libs/sample.jar"));
        project.run("clean", "jar", "--no-build-cache");
        byte[] second = Files.readAllBytes(tmp.resolve("build/libs/sample.jar"));

        assertTrue(java.util.Arrays.equals(first, second), "same sources must produce byte-identical jars");
    }

    @Test
    void failsClearlyWithoutTheVersionCatalog() throws IOException {
        project.write("build.gradle.kts", "plugins { id(\"tracer.java-library\") }\n");
        Files.delete(tmp.resolve("gradle/libs.versions.toml"));

        BuildResult result = project.runAndFail("help");

        assertTrue(result.getOutput().contains("version catalog"), result.getOutput());
    }
}
