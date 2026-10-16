package io.joeyang.tracer.cli;

import java.util.regex.Pattern;

/** One git commit: full sha and first subject line. */
public record Commit(String sha, String subject) {
    private static final Pattern FULL_SHA = Pattern.compile("[0-9a-f]{40}");

    public Commit {
        if (sha == null || !FULL_SHA.matcher(sha).matches()) {
            throw new IllegalArgumentException("expected a 40-char hex sha, got '" + sha + "'");
        }
        subject = subject == null ? "" : subject.strip();
    }

    public String shortSha() {
        return sha.substring(0, 7);
    }
}
