package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TableRendererTest {
    static TaskKey key(String k) { return new TaskKey(":app:appA:compileJava", k, Map.of(), Map.of(), "i"); }
    static Commit commit(char c, String subject) { return new Commit(String.valueOf(c).repeat(40), subject); }

    final List<Verdict> rows = List.of(
            new Verdict(commit('a', "baseline"), Status.BASE, Optional.of(key("3f9c0a1b2c3d4e5f6a7b8c9d0e1f2a3be1")), List.of(), Optional.empty()),
            new Verdict(commit('b', "utils: body-only edit"), Status.CLEAN, Optional.of(key("3f9c0a1b2c3d4e5f6a7b8c9d0e1f2a3be1")), List.of(), Optional.empty()),
            new Verdict(commit('c', "core: add Greeter.shout() with a very long subject line that keeps going"), Status.CHANGED,
                    Optional.of(key("2bb40a1b2c3d4e5f6a7b8c9d0e1f2a3b77")), List.of("core.jar"), Optional.empty()),
            new Verdict(commit('d', "broken"), Status.BUILD_FAILED, Optional.empty(), List.of(),
                    Optional.of("Could not resolve com.example:missing:1.0")));

    @Test void rendersHeaderRowsAndSummary() {
        String out = TableRenderer.render(":app:appA:compileJava", rows);
        String[] lines = out.split("\n");
        assertEquals(9, lines.length);
        // Exact lines, not startsWith/contains: these pin the fixed column widths (9/37/12/14) byte for byte.
        assertEquals("Tracing inputs of :app:appA:compileJava (gradle) over 3 commits", lines[0]);
        assertEquals("", lines[1]);
        assertEquals("commit   subject                              gradle key  verdict       culprit", lines[2]);
        assertEquals("aaaaaaa  baseline                             3f9c…e1     base", lines[3]);
        assertEquals("bbbbbbb  utils: body-only edit                3f9c…e1     clean", lines[4]);
        assertEquals("ccccccc  core: add Greeter.shout() with a ve… 2bb4…77     CHANGED       core.jar", lines[5]);
        assertEquals("ddddddd  broken                               -           BUILD-FAILED  Could not resolve com.example:missing:1.0", lines[6]);
        assertEquals("", lines[7]);
        assertEquals("1 of 3 commits changed compile inputs of appA. First: ccccccc. 1 commit could not be built.", lines[8]);
    }

    @Test void subjectIsTruncatedToThirtySixColumns() {
        String out = TableRenderer.render(":app:appA:compileJava", rows);
        String row = out.split("\n")[5];
        String subject = row.substring(9, 9 + 36);
        assertTrue(subject.endsWith("…"), "'" + subject + "'");
    }

    @Test void tsvHasOneRowPerCommitAndNoHeader() {
        String tsv = TableRenderer.renderTsv(rows);
        String[] lines = tsv.split("\n");
        assertEquals(4, lines.length);
        assertEquals("c".repeat(40) + "\tcore: add Greeter.shout() with a very long subject line that keeps going\t2bb40a1b2c3d4e5f6a7b8c9d0e1f2a3b77\tCHANGED\tcore.jar", lines[2]);
        assertEquals("d".repeat(40) + "\tbroken\t\tBUILD-FAILED\tCould not resolve com.example:missing:1.0", lines[3]);
    }

    @Test void summaryWhenNothingChanged() {
        String out = TableRenderer.render(":app:appB:fingerprint", rows.subList(0, 2));
        assertTrue(out.startsWith("Tracing inputs of :app:appB:fingerprint (gradle) over 1 commit\n"), out);
        assertTrue(out.endsWith("No commit changed runtime inputs of appB.\n"), out);
    }

    @Test void tsvEscapesTabsAndNewlinesSoColumnsCannotShift() {
        List<Verdict> withControlChars = List.of(
                new Verdict(commit('a', "baseline"), Status.BASE, Optional.of(key("k1")), List.of(), Optional.empty()),
                new Verdict(commit('e', "fix: a\tbroken\r\nsubject"), Status.CHANGED, Optional.of(key("k2")),
                        List.of("weird\tcrash\r\nreport"), Optional.empty()));
        String tsv = TableRenderer.renderTsv(withControlChars);
        String[] lines = tsv.split("\n", -1);
        assertEquals(3, lines.length); // 2 rows + trailing empty string after the final newline
        // every TSV line must have exactly 4 tabs (5 columns); a stray tab/CR/LF inside a field would shift this.
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            assertEquals(4, line.chars().filter(c -> c == '\t').count(), line);
        }
        assertTrue(tsv.contains("fix: a broken  subject"), tsv);
        assertTrue(tsv.contains("weird crash  report"), tsv);
    }

    @Test void truncateAndPadAreSafeForSurrogatePairs() {
        // U+1F600 (grinning face) is one code point but two UTF-16 chars; truncating by char index would split it
        // and print a lone low surrogate as '?'.
        String emoji = "😀";
        List<Verdict> withEmoji = List.of(
                new Verdict(commit('a', "baseline"), Status.BASE, Optional.of(key("k1")), List.of(), Optional.empty()),
                new Verdict(commit('e', emoji.repeat(40)), Status.CLEAN, Optional.of(key("k2")), List.of(), Optional.empty()));
        String out = TableRenderer.render(":app:appA:compileJava", withEmoji);
        String row = out.split("\n")[4];
        assertNoLoneSurrogates(row);
        String subjectColumn = row.substring(9, row.offsetByCodePoints(9, 37));
        assertEquals(37, subjectColumn.codePointCount(0, subjectColumn.length()), subjectColumn);
    }

    private static void assertNoLoneSurrogates(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)), "lone high surrogate in: " + s);
                i++;
            } else {
                assertTrue(!Character.isLowSurrogate(c), "lone low surrogate in: " + s);
            }
        }
    }

    @Test void rowsWithNoCulpritHaveNoTrailingSpaces() {
        String out = TableRenderer.render(":app:appA:compileJava", rows.subList(0, 1));
        String row = out.split("\n")[3];
        assertEquals(row, row.stripTrailing());
    }

    @Test void summaryFallsBackToTheFullTaskPathWhenThereIsNoAppSegment() {
        String out = TableRenderer.render(":compileJava", rows.subList(0, 2));
        assertTrue(out.contains("No commit changed compile inputs of :compileJava."), out);
    }
}
