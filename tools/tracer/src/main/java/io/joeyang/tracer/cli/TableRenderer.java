package io.joeyang.tracer.cli;

import java.util.List;
import java.util.stream.Collectors;

/** Fixed-width table for humans and TSV for scripts. */
public final class TableRenderer {
    static final int SUBJECT_WIDTH = 36;

    private TableRenderer() {}

    public static String render(String taskPath, List<Verdict> rows) {
        StringBuilder sb = new StringBuilder();
        int commits = Math.max(0, rows.size() - 1);
        sb.append("Tracing inputs of ").append(taskPath).append(" (gradle)");
        sb.append(" over ").append(commits).append(commits == 1 ? " commit" : " commits").append("\n\n");
        sb.append(pad("commit", 9)).append(pad("subject", SUBJECT_WIDTH + 1)).append(pad("gradle key", 12))
                .append(pad("verdict", 14)).append("culprit\n");
        for (Verdict v : rows) {
            String row = pad(v.commit().shortSha(), 9)
                    + pad(truncate(v.commit().subject(), SUBJECT_WIDTH), SUBJECT_WIDTH + 1)
                    + pad(v.key().map(TaskKey::shortKey).orElse("-"), 12)
                    + pad(v.status().label(), 14)
                    + culpritColumn(v);
            sb.append(row.stripTrailing()).append("\n");
        }
        sb.append("\n").append(summary(taskPath, rows)).append("\n");
        return sb.toString();
    }

    public static String renderTsv(List<Verdict> rows) {
        return rows.stream().map(v -> String.join("\t",
                v.commit().sha(), tsvSafe(v.commit().subject()), v.key().map(TaskKey::key).orElse(""),
                v.status().label(),
                tsvSafe(v.status() == Status.BUILD_FAILED ? v.error().orElse("") : String.join(", ", v.culprits()))))
                .collect(Collectors.joining("\n", "", "\n"));
    }

    /** A tab, CR or LF inside a field would shift the TSV's columns, so each is collapsed to a single space. */
    private static String tsvSafe(String s) {
        return s.replaceAll("[\\t\\r\\n]", " ");
    }

    private static String culpritColumn(Verdict v) {
        if (v.status() == Status.BUILD_FAILED) {
            return v.error().orElse("");
        }
        return String.join(", ", v.culprits());
    }

    private static String summary(String taskPath, List<Verdict> rows) {
        String app = appName(taskPath);
        String kind = taskPath.endsWith(":fingerprint") ? "runtime inputs" : taskPath.endsWith(":compileJava") ? "compile inputs" : "inputs";
        long changed = rows.stream().filter(v -> v.status() == Status.CHANGED).count();
        long failed = rows.stream().filter(v -> v.status() == Status.BUILD_FAILED).count();
        int commits = Math.max(0, rows.size() - 1);
        StringBuilder sb = new StringBuilder();
        if (changed == 0) {
            sb.append("No commit changed ").append(kind).append(" of ").append(app).append(".");
        } else {
            String first = rows.stream().filter(v -> v.status() == Status.CHANGED).findFirst().orElseThrow().commit().shortSha();
            sb.append(changed).append(" of ").append(commits).append(commits == 1 ? " commit" : " commits")
                    .append(" changed ").append(kind).append(" of ").append(app).append(". First: ").append(first).append(".");
        }
        if (failed > 0) {
            sb.append(" ").append(failed).append(failed == 1 ? " commit" : " commits").append(" could not be built.");
        }
        return sb.toString();
    }

    /** {@code :app:appA:compileJava → appA}; falls back to the whole path when there is no app segment to name. */
    static String appName(String taskPath) {
        String[] parts = taskPath.split(":");
        if (parts.length >= 2 && !parts[parts.length - 2].isBlank()) {
            return parts[parts.length - 2];
        }
        return taskPath;
    }

    /**
     * Truncates by code point, not {@code char}, so a surrogate pair (an emoji, mostly) is never split into a lone
     * surrogate followed by the ellipsis.
     */
    private static String truncate(String s, int width) {
        if (s.codePointCount(0, s.length()) <= width) {
            return s;
        }
        int cut = s.offsetByCodePoints(0, width - 1);
        return s.substring(0, cut) + "…";
    }

    /** Pads by code point count, matching {@link #truncate}, so a surrogate pair is counted as one column. */
    private static String pad(String s, int width) {
        int codePoints = s.codePointCount(0, s.length());
        return codePoints >= width ? s + " " : s + " ".repeat(width - codePoints);
    }
}
