package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CacheDebugLogTest {
    static final String TASK = ":app:appA:compileJava";
    static final String FIXTURE = CacheDebugFixtures.COMPILE_JAVA_LOG;

    @Test void parsesOnlyTheRequestedTask() {
        TaskKey key = CacheDebugLog.parse(FIXTURE, TASK).orElseThrow();

        assertEquals(TASK, key.taskPath());
        assertEquals("35c0f7cbe157cd2e30090cc5510cec8c", key.key());
        // Fixture repeats the same class in its "additional implementation" line; both are folded in.
        assertEquals("org.gradle.api.tasks.compile.JavaCompile_Decorated@bbbb, org.gradle.api.tasks.compile.JavaCompile_Decorated@bbbb",
                key.implementation());
        assertEquals(Map.of("options.release", "91e6b2f4b53f9e7b3eadb03039173cc5", "options.compilerArgs", "4444"), key.valueFingerprints());
        assertEquals(Map.of(
                "/w/core/build/classes/java/main/io/joeyang/tracer/core/Greeter.class", "5042bbb7be5171b8d45ca1e0a8193342",
                "/w/lib/build/classes/java/main/io/joeyang/tracer/lib/Service.class", "6666"), key.fileFingerprints().get("classpath"));
        assertEquals(Map.of(), key.fileFingerprints().get("options.annotationProcessorPath"));
        assertEquals(Map.of("/w/app/appA/src/main/java/io/joeyang/tracer/app/a/AppA.java", "55e49a9f6bfdc6ea941195b682520e54"),
                key.fileFingerprints().get("stableSources"));
    }

    @Test void otherTasksDoNotLeakIntoTheResult() {
        TaskKey key = CacheDebugLog.parse(FIXTURE, TASK).orElseThrow();
        assertFalse(key.fileFingerprints().get("stableSources").containsKey("/w/core/src/main/java/io/joeyang/tracer/core/Greeter.java"));
    }

    @Test void absentTaskIsEmpty() {
        assertEquals(Optional.empty(), CacheDebugLog.parse(FIXTURE, ":app:appB:compileJava"));
    }

    @Test void nonCacheableTaskIsEmpty() {
        assertEquals(Optional.empty(), CacheDebugLog.parse(FIXTURE, ":core:jar"));
    }

    @Test void truncatedLogIsEmpty() {
        String truncated = FIXTURE.substring(0, FIXTURE.indexOf("Build cache key for task ':app:appA"));
        assertEquals(Optional.empty(), CacheDebugLog.parse(truncated, TASK));
    }

    @Test void ignoredEntriesAndJarsAreParsed() {
        String log = """
                > Task :app:appA:fingerprint
                Appending implementation to build cache key: io.joeyang.tracer.buildlogic.FingerprintTask_Decorated@0001
                Appending input file fingerprints for 'runtimeClasspath' to build cache key: 2924 - CLASSPATH{/w/app/appA/build/classes/java/main/io/joeyang/tracer/app/a/AppA.class='io/joeyang/tracer/app/a/AppA.class' / dcbb, /w/core/build/libs/core.jar=IGNORED / ba11, /home/user/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/abc/gson-2.10.1.jar=IGNORED / d632}
                Appending output property name to build cache key: marker
                Build cache key for task ':app:appA:fingerprint' is b5a1bb8d226bf944a43ec45392201d50
                """;
        TaskKey key = CacheDebugLog.parse(log, ":app:appA:fingerprint").orElseThrow();
        Map<String, String> files = key.fileFingerprints().get("runtimeClasspath");
        assertEquals(3, files.size());
        assertEquals("ba11", files.get("/w/core/build/libs/core.jar"));
        assertEquals("d632", files.get("/home/user/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/abc/gson-2.10.1.jar"));
        assertEquals(Map.of(), key.valueFingerprints());
    }

    @Test void realCompileJavaFixtureParses() throws IOException {
        TaskKey key = CacheDebugLog.parse(resource("/cache-debug/appA-compileJava.log"), TASK).orElseThrow();
        assertEquals("17035b126dce737b54e2162d950d98e0", key.key());
        assertTrue(key.valueFingerprints().containsKey("options.release"), key.valueFingerprints().keySet().toString());
        assertEquals(2, key.fileFingerprints().get("classpath").size(), "appA's compile classpath has lib and core classes");
        assertTrue(key.fileFingerprints().get("stableSources").keySet().stream().anyMatch(p -> p.endsWith("AppA.java")));
        assertTrue(key.implementation().contains("JavaCompile"), key.implementation());
    }

    @Test void realFingerprintFixtureParses() throws IOException {
        // Read from the fixture itself rather than hand-copied, so a re-capture that changes the key fails loudly.
        String fixture = resource("/cache-debug/appA-fingerprint.log");
        String expectedKey = fixture.lines()
                .filter(l -> l.startsWith("Build cache key for task ':app:appA:fingerprint'"))
                .findFirst().orElseThrow()
                .replaceFirst(".* is ", "");
        TaskKey key = CacheDebugLog.parse(fixture, ":app:appA:fingerprint").orElseThrow();
        assertEquals(expectedKey, key.key());
        assertEquals(5, key.fileFingerprints().get("runtimeClasspath").size());
        assertTrue(key.fileFingerprints().get("runtimeClasspath").keySet().stream().anyMatch(p -> p.endsWith("/core.jar")));
        assertTrue(key.fileFingerprints().get("runtimeClasspath").keySet().stream().anyMatch(p -> p.contains("commons-lang3-3.18.0.jar")));
        assertTrue(key.implementation().contains("FingerprintTask"), key.implementation());
    }

    @Test void keyOnlyBlockParsesWithEmptyInputs() {
        // An UP-TO-DATE task prints the key and no input lines; fresh worktrees never hit this, but the parser must cope.
        String log = "> Task :app:appA:compileJava UP-TO-DATE\nBuild cache key for task ':app:appA:compileJava' is 35c0f7cbe157cd2e30090cc5510cec8c\n";
        TaskKey key = CacheDebugLog.parse(log, TASK).orElseThrow();
        assertEquals("35c0f7cbe157cd2e30090cc5510cec8c", key.key());
        assertTrue(key.fileFingerprints().isEmpty() && key.valueFingerprints().isEmpty());
    }

    @Test void nonCacheableReasonIsReportedForTheRightTaskOnly() {
        assertEquals(Optional.of("Not worth caching [NOT_CACHEABLE]"), CacheDebugLog.nonCacheableReason(FIXTURE, ":core:jar"));
        assertEquals(Optional.empty(), CacheDebugLog.nonCacheableReason(FIXTURE, TASK));
    }

    @Test void dirAndMissingEntriesAreNotGarbageKeys() {
        String log = """
                > Task :app:appA:fingerprint
                Appending implementation to build cache key: io.joeyang.tracer.buildlogic.FingerprintTask_Decorated@0001
                Appending input file fingerprints for 'rootSpec$1' to build cache key: aaaa - RELATIVE_PATH{/w/build/classes/java/main/io='io' / DIR, /w/build/deleted.class='deleted.class' / MISSING, /w/build/classes/java/main/io/Real.class='io/Real.class' / 3333}
                Build cache key for task ':app:appA:fingerprint' is b5a1bb8d226bf944a43ec45392201d50
                """;
        TaskKey key = CacheDebugLog.parse(log, ":app:appA:fingerprint").orElseThrow();
        Map<String, String> entries = key.fileFingerprints().get("rootSpec$1");
        assertEquals(3, entries.size());
        assertEquals("DIR", entries.get("/w/build/classes/java/main/io"));
        assertEquals("MISSING", entries.get("/w/build/deleted.class"));
        assertEquals("3333", entries.get("/w/build/classes/java/main/io/Real.class"));
    }

    @Test void aQuoteInsideANormalizedPathDoesNotMergeTwoEntries() {
        String log = """
                > Task :app:appA:fingerprint
                Appending implementation to build cache key: io.joeyang.tracer.buildlogic.FingerprintTask_Decorated@0001
                Appending input file fingerprints for 'stableSources' to build cache key: aaaa - RELATIVE_PATH{/w/src/io/it's/A.java='io/it's/A.java' / 1111, /w/src/io/B.java='io/B.java' / 2222}
                Build cache key for task ':app:appA:fingerprint' is b5a1bb8d226bf944a43ec45392201d50
                """;
        TaskKey key = CacheDebugLog.parse(log, ":app:appA:fingerprint").orElseThrow();
        Map<String, String> entries = key.fileFingerprints().get("stableSources");
        assertEquals(2, entries.size());
        assertEquals("1111", entries.get("/w/src/io/it's/A.java"));
        assertEquals("2222", entries.get("/w/src/io/B.java"));
    }

    @Test void unknownNormalizationTokenFailsLoudlyInsteadOfDroppingTheProperty() {
        String log = """
                > Task :app:appA:fingerprint
                Appending implementation to build cache key: io.joeyang.tracer.buildlogic.FingerprintTask_Decorated@0001
                Appending input file fingerprints for 'stableSources' to build cache key: aaaa - relativePath{/w/src/A.java='A.java' / 1111}
                Build cache key for task ':app:appA:fingerprint' is b5a1bb8d226bf944a43ec45392201d50
                """;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CacheDebugLog.parse(log, ":app:appA:fingerprint"));
        assertTrue(e.getMessage().contains("unrecognised cache debug output"), e.getMessage());
        assertTrue(e.getMessage().contains("relativePath{/w/src/A.java='A.java' / 1111}"), e.getMessage());
    }

    @Test void unknownValueFingerprintShapeFailsLoudly() {
        String log = """
                > Task :app:appA:fingerprint
                Appending implementation to build cache key: io.joeyang.tracer.buildlogic.FingerprintTask_Decorated@0001
                Appending input value fingerprint for 'options.release' to build cache key: not-a-hex-hash
                Build cache key for task ':app:appA:fingerprint' is b5a1bb8d226bf944a43ec45392201d50
                """;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CacheDebugLog.parse(log, ":app:appA:fingerprint"));
        assertTrue(e.getMessage().contains("unrecognised cache debug output"), e.getMessage());
        assertTrue(e.getMessage().contains("options.release"), e.getMessage());
    }

    @Test void pathsContainingAnEqualsSignAreNotCutAtTheWrongOne() {
        String log = """
                > Task :app:appA:fingerprint
                Appending implementation to build cache key: io.joeyang.tracer.buildlogic.FingerprintTask_Decorated@0001
                Appending input file fingerprints for 'stableSources' to build cache key: aaaa - RELATIVE_PATH{/w/src/io/a=b.java='a=b.java' / 1111, /w/src/io/c=d.java=IGNORED / 2222}
                Build cache key for task ':app:appA:fingerprint' is b5a1bb8d226bf944a43ec45392201d50
                """;
        TaskKey key = CacheDebugLog.parse(log, ":app:appA:fingerprint").orElseThrow();
        Map<String, String> entries = key.fileFingerprints().get("stableSources");
        assertEquals(2, entries.size());
        assertEquals("1111", entries.get("/w/src/io/a=b.java"));
        assertEquals("2222", entries.get("/w/src/io/c=d.java"));
    }

    @Test void additionalImplementationLinesAreFoldedIntoTheImplementationFingerprint() {
        String log = """
                > Task :app:appA:fingerprint
                Appending implementation to build cache key: io.joeyang.tracer.buildlogic.FingerprintTask_Decorated@0001
                Appending additional implementation to build cache key: org.gradle.api.internal.AbstractTask$TaskActionWrapper@0002
                Build cache key for task ':app:appA:fingerprint' is b5a1bb8d226bf944a43ec45392201d50
                """;
        TaskKey key = CacheDebugLog.parse(log, ":app:appA:fingerprint").orElseThrow();
        assertTrue(key.implementation().contains("FingerprintTask_Decorated@0001"), key.implementation());
        assertTrue(key.implementation().contains("TaskActionWrapper@0002"), key.implementation());
    }

    @Test void realFixtureRootSpecHasAllThirteenEntries() throws IOException {
        // rootSpec$1 only ever appears on a Jar task's input, and Jar is non-cacheable, so this property
        // never comes back through parse(); fileFingerprintsFor exercises the same entry parser directly.
        Map<String, Map<String, String>> files = CacheDebugLog.fileFingerprintsFor(resource("/cache-debug/appA-compileJava.log"), ":build-logic:jar");
        assertEquals(13, files.get("rootSpec$1").size());
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = CacheDebugLogTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
