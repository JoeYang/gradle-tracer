package io.joeyang.tracer.cli;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the lines Gradle prints with {@code -Dorg.gradle.caching.debug=true}. Lines belong to the task
 * announced by the preceding {@code > Task <path>} header (hence {@code --no-parallel}); a
 * {@code Build cache key for task '<path>' is <hash>} line closes the block.
 */
final class CacheDebugLog {
    private static final Pattern TASK_HEADER = Pattern.compile("^> Task (\\S+)");
    private static final Pattern IMPLEMENTATION = Pattern.compile("^Appending implementation to build cache key: (.+)$");
    private static final Pattern VALUE = Pattern.compile("^Appending input value fingerprint for '([^']+)' to build cache key: ([0-9a-f]+)$");
    private static final Pattern FILES = Pattern.compile("^Appending input file fingerprints for '([^']+)' to build cache key: [0-9a-f]+ - [A-Z_]+\\{(.*)\\}$");
    private static final Pattern ENTRY = Pattern.compile("(.+?)=(?:'[^']*'|IGNORED) / ([0-9a-f]+)(?:, |$)");
    private static final Pattern KEY = Pattern.compile("^Build cache key for task '([^']+)' is ([0-9a-f]+)$");
    private static final Pattern NON_CACHEABLE = Pattern.compile("^Non-cacheable because (.+)$");

    private CacheDebugLog() {}

    static Optional<TaskKey> parse(String output, String taskPath) {
        String implementation = "";
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            Matcher m;
            if (TASK_HEADER.matcher(line).find()) {
                implementation = "";
                values = new LinkedHashMap<>();
                files = new LinkedHashMap<>();
            } else if ((m = IMPLEMENTATION.matcher(line)).matches()) {
                implementation = m.group(1);
            } else if ((m = VALUE.matcher(line)).matches()) {
                values.put(m.group(1), m.group(2));
            } else if ((m = FILES.matcher(line)).matches()) {
                files.put(m.group(1), entries(m.group(2)));
            } else if ((m = KEY.matcher(line)).matches()) {
                if (m.group(1).equals(taskPath)) {
                    return Optional.of(new TaskKey(taskPath, m.group(2), files, values, implementation));
                }
                implementation = "";
                values = new LinkedHashMap<>();
                files = new LinkedHashMap<>();
            }
        }
        return Optional.empty();
    }

    /** The reason Gradle printed instead of a key for {@code taskPath} (e.g. "Not worth caching [NOT_CACHEABLE]" for jar tasks). */
    static Optional<String> nonCacheableReason(String output, String taskPath) {
        String current = null;
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            Matcher header = TASK_HEADER.matcher(line);
            if (header.find()) {
                current = header.group(1);
                continue;
            }
            Matcher m = NON_CACHEABLE.matcher(line);
            if (m.matches() && taskPath.equals(current)) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> entries(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body.isEmpty() || body.equals("EMPTY")) {
            return out;
        }
        Matcher m = ENTRY.matcher(body);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }
}
