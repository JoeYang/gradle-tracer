package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CommitTest {
    static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test void shortShaIsSevenChars() { assertEquals("0123456", new Commit(SHA, "x").shortSha()); }

    @Test void rejectsNonHexSha() {
        var e = assertThrows(IllegalArgumentException.class, () -> new Commit("nope", "x"));
        assertEquals("expected a 40-char hex sha, got 'nope'", e.getMessage());
    }

    @Test void rejectsShortSha() {
        var e = assertThrows(IllegalArgumentException.class, () -> new Commit("0123456", "x"));
        assertEquals("expected a 40-char hex sha, got '0123456'", e.getMessage());
    }

    @Test void rejectsNullSha() {
        var e = assertThrows(IllegalArgumentException.class, () -> new Commit(null, "x"));
        assertEquals("expected a 40-char hex sha, got 'null'", e.getMessage());
    }

    @Test void rejectsUppercaseSha() {
        String upper = SHA.toUpperCase(java.util.Locale.ROOT);
        var e = assertThrows(IllegalArgumentException.class, () -> new Commit(upper, "x"));
        assertEquals("expected a 40-char hex sha, got '" + upper + "'", e.getMessage());
    }

    @Test void nullSubjectBecomesEmpty() { assertEquals("", new Commit(SHA, null).subject()); }
}
