package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CommitTest {
    static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test void shortShaIsSevenChars() { assertEquals("0123456", new Commit(SHA, "x").shortSha()); }
    @Test void rejectsNonHexSha() { assertThrows(IllegalArgumentException.class, () -> new Commit("nope", "x")); }
    @Test void rejectsShortSha() { assertThrows(IllegalArgumentException.class, () -> new Commit("0123456", "x")); }
    @Test void nullSubjectBecomesEmpty() { assertEquals("", new Commit(SHA, null).subject()); }
}
