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
            new Verdict(commit('a', "baseline"), Status.BASE, Optional.of(key("3f9c0a1b2c3d4e5f6a7b8c9d0e1f2a3be1")), BazelStatus.NONE, List.of(), Optional.empty()),
            new Verdict(commit('b', "utils: body-only edit"), Status.CLEAN, Optional.of(key("3f9c0a1b2c3d4e5f6a7b8c9d0e1f2a3be1")), BazelStatus.IMPACTED, List.of(), Optional.empty()),
            new Verdict(commit('c', "core: add Greeter.shout() with a very long subject line that keeps going"), Status.CHANGED,
                    Optional.of(key("2bb40a1b2c3d4e5f6a7b8c9d0e1f2a3b77")), BazelStatus.IMPACTED, List.of("core.jar"), Optional.empty()),
            new Verdict(commit('d', "broken"), Status.BUILD_FAILED, Optional.empty(), BazelStatus.NOT_AVAILABLE, List.of(),
                    Optional.of("Could not resolve com.example:missing:1.0")));

    @Test void rendersHeaderRowsAndSummary() {
        String out = TableRenderer.render(":app:appA:compileJava", "//app/appA:appA", rows);
        String[] lines = out.split("\n");
        assertEquals("Tracing inputs of :app:appA:compileJava (gradle) and //app/appA:appA (bazel-diff) over 3 commits", lines[0]);
        assertTrue(lines[2].startsWith("commit   subject"), lines[2]);
        assertTrue(lines[3].startsWith("aaaaaaa  baseline"), lines[3]);
        assertTrue(lines[3].contains("3f9c…e1") && lines[3].contains("  -  ") && lines[3].contains("base"), lines[3]);
        assertTrue(lines[4].contains("impacted") && lines[4].contains("clean") && lines[4].contains("bazel≠gradle"), lines[4]);
        assertTrue(lines[5].contains("…") && lines[5].contains("CHANGED") && lines[5].contains("core.jar"), lines[5]);
        assertTrue(lines[6].contains("BUILD-FAILED") && lines[6].contains("missing:1.0"), lines[6]);
        assertEquals("1 of 3 commits changed compile inputs of appA. First: ccccccc. 1 commit could not be built.", lines[lines.length - 1]);
    }

    @Test void subjectIsTruncatedToThirtySixColumns() {
        String out = TableRenderer.render(":app:appA:compileJava", "//app/appA:appA", rows);
        String row = out.split("\n")[5];
        String subject = row.substring(9, 9 + 36);
        assertTrue(subject.endsWith("…"), "'" + subject + "'");
    }

    @Test void tsvHasOneRowPerCommitAndNoHeader() {
        String tsv = TableRenderer.renderTsv(rows);
        String[] lines = tsv.split("\n");
        assertEquals(4, lines.length);
        assertEquals("c".repeat(40) + "\tcore: add Greeter.shout() with a very long subject line that keeps going\t2bb40a1b2c3d4e5f6a7b8c9d0e1f2a3b77\timpacted\tCHANGED\tcore.jar", lines[2]);
        assertEquals("d".repeat(40) + "\tbroken\t\tn/a\tBUILD-FAILED\tCould not resolve com.example:missing:1.0", lines[3]);
    }

    @Test void summaryWhenNothingChanged() {
        String out = TableRenderer.render(":app:appB:fingerprint", null, rows.subList(0, 2));
        assertTrue(out.startsWith("Tracing inputs of :app:appB:fingerprint (gradle) over 1 commit\n"), out);
        assertTrue(out.endsWith("No commit changed runtime inputs of appB.\n"), out);
    }

    @Test void tsvEscapesTabsAndNewlinesSoColumnsCannotShift() {
        List<Verdict> withControlChars = List.of(
                new Verdict(commit('a', "baseline"), Status.BASE, Optional.of(key("k1")), BazelStatus.NONE, List.of(), Optional.empty()),
                new Verdict(commit('e', "fix: a\tbroken\r\nsubject"), Status.CHANGED, Optional.of(key("k2")), BazelStatus.IMPACTED,
                        List.of("weird\tcrash\r\nreport"), Optional.empty()));
        String tsv = TableRenderer.renderTsv(withControlChars);
        String[] lines = tsv.split("\n", -1);
        assertEquals(3, lines.length); // 2 rows + trailing empty string after the final newline
        // every TSV line must have exactly 5 tabs (6 columns); a stray tab/CR/LF inside a field would shift this.
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            assertEquals(5, line.chars().filter(c -> c == '\t').count(), line);
        }
        assertTrue(tsv.contains("fix: a broken  subject"), tsv);
        assertTrue(tsv.contains("weird crash  report"), tsv);
    }

    @Test void truncateAndPadAreSafeForSurrogatePairs() {
        // U+1F600 (grinning face) is one code point but two UTF-16 chars; truncating by char index would split it
        // and print a lone low surrogate as '?'.
        String emoji = "😀";
        List<Verdict> withEmoji = List.of(
                new Verdict(commit('a', "baseline"), Status.BASE, Optional.of(key("k1")), BazelStatus.NONE, List.of(), Optional.empty()),
                new Verdict(commit('e', emoji.repeat(40)), Status.CLEAN, Optional.of(key("k2")), BazelStatus.NONE, List.of(), Optional.empty()));
        String out = TableRenderer.render(":app:appA:compileJava", null, withEmoji);
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
        String out = TableRenderer.render(":app:appA:compileJava", null, rows.subList(0, 1));
        String row = out.split("\n")[3];
        assertEquals(row, row.stripTrailing());
    }

    @Test void summaryFallsBackToTheFullTaskPathWhenThereIsNoAppSegment() {
        String out = TableRenderer.render(":compileJava", null, rows.subList(0, 2));
        assertTrue(out.contains("No commit changed compile inputs of :compileJava."), out);
    }
}
