package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskKeyTest {
    @Test void shortKeyShowsFourAndTwo() {
        TaskKey k = new TaskKey(":app:appA:compileJava", "3f9c0a1b2c3d4e5f6a7b8c9d0e1f2a3b", Map.of(), Map.of(), "impl");
        assertEquals("3f9c…3b", k.shortKey());
    }

    @Test void shortKeyOfTinyKeyIsTheKey() {
        TaskKey k = new TaskKey(":t", "abc", Map.of(), Map.of(), "impl");
        assertEquals("abc", k.shortKey());
    }

    @Test void shortKeyBoundaryAtSevenCharsIsUnabbreviated() {
        TaskKey k = new TaskKey(":t", "1234567", Map.of(), Map.of(), "impl");
        assertEquals("1234567", k.shortKey());
    }

    @Test void shortKeyBoundaryAtEightCharsIsAbbreviated() {
        TaskKey k = new TaskKey(":t", "12345678", Map.of(), Map.of(), "impl");
        assertEquals("1234…78", k.shortKey());
    }

    @Test void mapsAreDefensivelyCopied() {
        Map<String, Map<String, String>> files = new HashMap<>();
        files.put("classpath", new HashMap<>(Map.of("/x/core.jar", "aa")));
        TaskKey k = new TaskKey(":t", "k", files, new HashMap<>(), "impl");
        files.get("classpath").put("/x/other.jar", "bb");
        assertEquals(1, k.fileFingerprints().get("classpath").size());
        assertThrows(UnsupportedOperationException.class, () -> k.fileFingerprints().put("x", Map.of()));
    }

    @Test void rejectsBlankKey() {
        var e = assertThrows(IllegalArgumentException.class, () -> new TaskKey(":t", " ", Map.of(), Map.of(), "impl"));
        assertEquals("cache key must not be blank for :t", e.getMessage());
    }

    @Test void rejectsEmptyKey() {
        var e = assertThrows(IllegalArgumentException.class, () -> new TaskKey(":t", "", Map.of(), Map.of(), "impl"));
        assertEquals("cache key must not be blank for :t", e.getMessage());
    }
}
