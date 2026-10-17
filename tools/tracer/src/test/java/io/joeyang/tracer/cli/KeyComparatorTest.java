package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KeyComparatorTest {
    static final Commit C = new Commit("c".repeat(40), "some change");
    static final String TASK = ":app:appA:compileJava";

    static TaskKey key(String hash, Map<String, Map<String, String>> files, Map<String, String> values, String impl) {
        return new TaskKey(TASK, hash, files, values, impl);
    }

    @Test void firstCommitIsBase() {
        TaskKey k = key("k1", Map.of(), Map.of(), "i");
        Verdict v = KeyComparator.verdict(C, Optional.empty(), Optional.of(k), Optional.empty(), BazelStatus.NONE);
        assertEquals(Status.BASE, v.status());
        assertEquals(List.of(), v.culprits());
    }

    @Test void sameKeyIsClean() {
        TaskKey a = key("k1", Map.of("classpath", Map.of("/x/core.jar", "aa")), Map.of(), "i");
        TaskKey b = key("k1", Map.of("classpath", Map.of("/y/core.jar", "aa")), Map.of(), "i");
        Verdict v = KeyComparator.verdict(C, Optional.of(a), Optional.of(b), Optional.empty(), BazelStatus.CLEAN);
        assertEquals(Status.CLEAN, v.status());
    }

    @Test void changedJarIsNamedByFileName() {
        TaskKey a = key("k1", Map.of("classpath", Map.of("/w1/core/build/libs/core.jar", "aa", "/w1/lib/build/libs/lib.jar", "bb")), Map.of(), "i");
        TaskKey b = key("k2", Map.of("classpath", Map.of("/w2/core/build/libs/core.jar", "a2", "/w2/lib/build/libs/lib.jar", "bb")), Map.of(), "i");
        Verdict v = KeyComparator.verdict(C, Optional.of(a), Optional.of(b), Optional.empty(), BazelStatus.IMPACTED);
        assertEquals(Status.CHANGED, v.status());
        assertEquals(List.of("core.jar"), v.culprits());
    }

    @Test void changedSourceIsNamedRelativeToItsModule() {
        TaskKey a = key("k1", Map.of("stableSources", Map.of("/w1/app/appA/src/main/java/io/joeyang/tracer/app/a/AppA.java", "aa")), Map.of(), "i");
        TaskKey b = key("k2", Map.of("stableSources", Map.of("/w2/app/appA/src/main/java/io/joeyang/tracer/app/a/AppA.java", "a2")), Map.of(), "i");
        Verdict v = KeyComparator.verdict(C, Optional.of(a), Optional.of(b), Optional.empty(), BazelStatus.IMPACTED);
        assertEquals(List.of("appA/src/main/java/io/joeyang/tracer/app/a/AppA.java"), v.culprits());
    }

    @Test void classFilesUnderOneOutputDirCollapseToModuleClasses() {
        TaskKey a = key("k1", Map.of("classpath", Map.of(
                "/w1/core/build/classes/java/main/io/joeyang/tracer/core/Greeter.class", "aa",
                "/w1/core/build/classes/java/main/io/joeyang/tracer/core/Other.class", "bb")), Map.of(), "i");
        TaskKey b = key("k2", Map.of("classpath", Map.of(
                "/w2/core/build/classes/java/main/io/joeyang/tracer/core/Greeter.class", "aa",
                "/w2/core/build/classes/java/main/io/joeyang/tracer/core/Other.class", "b2")), Map.of(), "i");
        Verdict v = KeyComparator.verdict(C, Optional.of(a), Optional.of(b), Optional.empty(), BazelStatus.IMPACTED);
        assertEquals(List.of("core classes"), v.culprits());
    }

    @Test void addedAndRemovedFilesCount() {
        TaskKey a = key("k1", Map.of("classpath", Map.of("/w1/libs/gson-2.10.1.jar", "aa")), Map.of(), "i");
        TaskKey b = key("k2", Map.of("classpath", Map.of("/w2/libs/gson-2.11.0.jar", "bb")), Map.of(), "i");
        Verdict v = KeyComparator.verdict(C, Optional.of(a), Optional.of(b), Optional.empty(), BazelStatus.IMPACTED);
        assertEquals(List.of("gson-2.10.1.jar", "gson-2.11.0.jar"), v.culprits());
    }

    @Test void moduleCacheJarsAreNamedByCoordinatesSoABumpIsOneCulprit() {
        TaskKey a = key("k1", Map.of("runtimeClasspath", Map.of(
                "/home/u/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/abc/gson-2.10.1.jar", "aa")), Map.of(), "i");
        TaskKey b = key("k2", Map.of("runtimeClasspath", Map.of(
                "/home/u/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0/def/gson-2.11.0.jar", "bb")), Map.of(), "i");
        Verdict v = KeyComparator.verdict(C, Optional.of(a), Optional.of(b), Optional.empty(), BazelStatus.IMPACTED);
        assertEquals(List.of("com.google.code.gson:gson"), v.culprits());
    }

    @Test void changedValueAndImplementationAreNamed() {
        TaskKey a = key("k1", Map.of(), Map.of("options.compilerArgs", "v1"), "impl1");
        TaskKey b = key("k2", Map.of(), Map.of("options.compilerArgs", "v2"), "impl2");
        Verdict v = KeyComparator.verdict(C, Optional.of(a), Optional.of(b), Optional.empty(), BazelStatus.IMPACTED);
        assertEquals(List.of("options.compilerArgs", "build logic"), v.culprits());
    }

    @Test void culpritsAreCappedAtFour() {
        Map<String, String> many1 = Map.of("/a.jar", "1", "/b.jar", "1", "/c.jar", "1", "/d.jar", "1", "/e.jar", "1", "/f.jar", "1");
        Map<String, String> many2 = Map.of("/a.jar", "2", "/b.jar", "2", "/c.jar", "2", "/d.jar", "2", "/e.jar", "2", "/f.jar", "2");
        Verdict v = KeyComparator.verdict(C, Optional.of(key("k1", Map.of("classpath", many1), Map.of(), "i")),
                Optional.of(key("k2", Map.of("classpath", many2), Map.of(), "i")), Optional.empty(), BazelStatus.IMPACTED);
        assertEquals(5, v.culprits().size());
        assertEquals("+2 more", v.culprits().get(4));
    }

    @Test void keyChangedButNoFingerprintDifferenceStillNamesSomething() {
        TaskKey a = key("k1", Map.of(), Map.of(), "i");
        TaskKey b = key("k2", Map.of(), Map.of(), "i");
        Verdict v = KeyComparator.verdict(C, Optional.of(a), Optional.of(b), Optional.empty(), BazelStatus.IMPACTED);
        assertEquals(List.of("inputs changed (no fingerprint detail)"), v.culprits());
    }

    @Test void missingKeyIsBuildFailedWithTheError() {
        Verdict v = KeyComparator.verdict(C, Optional.of(key("k1", Map.of(), Map.of(), "i")), Optional.empty(),
                Optional.of("Could not resolve com.example:missing:1.0"), BazelStatus.NOT_AVAILABLE);
        assertEquals(Status.BUILD_FAILED, v.status());
        assertTrue(v.error().orElseThrow().contains("missing:1.0"));
    }
}
