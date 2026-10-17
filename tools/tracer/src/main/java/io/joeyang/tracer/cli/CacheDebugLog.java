package io.joeyang.tracer.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the lines Gradle prints with {@code -Dorg.gradle.caching.debug=true}. Lines belong to the task
 * announced by the preceding {@code > Task <path>} header (hence {@code --no-parallel}); a
 * {@code Build cache key for task '<path>' is <hash>} line, or a {@code Non-cacheable because ...} line,
 * closes the block. Any {@code Appending ...} line inside the target task's block that does not match one
 * of the shapes below is treated as unrecognised rather than silently dropped or mis-split (spec §9): a
 * Gradle upgrade that changes the format must fail loudly instead of producing a wrong, "clean" verdict.
 */
final class CacheDebugLog {
    private static final Pattern TASK_HEADER = Pattern.compile("^> Task (\\S+)");
    private static final Pattern IMPLEMENTATION = Pattern.compile("^Appending implementation to build cache key: (.+)$");
    private static final Pattern ADDITIONAL_IMPLEMENTATION =
            Pattern.compile("^Appending additional implementation to build cache key: (.+)$");
    private static final Pattern VALUE = Pattern.compile("^Appending input value fingerprint for '([^']+)' to build cache key: ([0-9a-f]+)$");
    private static final Pattern FILES = Pattern.compile("^Appending input file fingerprints for '([^']+)' to build cache key: [0-9a-f]+ - [A-Z_]+\\{(.*)\\}$");
    private static final Pattern OUTPUT_PROPERTY = Pattern.compile("^Appending output property name to build cache key: .+$");
    /**
     * A single already-isolated {@code <path>=<'normalized'|IGNORED> / <hash|DIR|MISSING>} entry, with the
     * path captured greedily so a path containing its own {@code '='} does not get cut at the wrong one:
     * the engine backtracks to the rightmost {@code '='} for which the rest of the pattern still matches
     * through end-of-string, which is always the real delimiter.
     */
    private static final Pattern ENTRY = Pattern.compile("^(/.+)=(?:'.*'|IGNORED) / (?:([0-9a-f]+)|DIR|MISSING)$");
    private static final Pattern KEY = Pattern.compile("^Build cache key for task '([^']+)' is ([0-9a-f]+)$");
    private static final Pattern NON_CACHEABLE = Pattern.compile("^Non-cacheable because (.+)$");

    private CacheDebugLog() {}

    static Optional<TaskKey> parse(String output, String taskPath) {
        Block block = blockFor(output, taskPath);
        if (block.key() == null) {
            return Optional.empty();
        }
        return Optional.of(new TaskKey(taskPath, block.key(), block.files(), block.values(), block.implementation()));
    }

    /** The reason Gradle printed instead of a key for {@code taskPath} (e.g. "Not worth caching [NOT_CACHEABLE]" for jar tasks). */
    static Optional<String> nonCacheableReason(String output, String taskPath) {
        return Optional.ofNullable(blockFor(output, taskPath).nonCacheableReason());
    }

    /**
     * Per-file fingerprints for {@code taskPath}, including a task that never prints a key (e.g. a jar
     * task). Exposed so tests can exercise entry parsing directly against properties, such as a {@code Jar}
     * task's {@code rootSpec$1}, that only ever appear on non-cacheable tasks.
     */
    static Map<String, Map<String, String>> fileFingerprintsFor(String output, String taskPath) {
        return blockFor(output, taskPath).files();
    }

    private record Block(String implementation, Map<String, Map<String, String>> files,
                          Map<String, String> values, String key, String nonCacheableReason) {}

    private static Block blockFor(String output, String taskPath) {
        List<String> implementations = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        boolean inTarget = false;
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            Matcher header = TASK_HEADER.matcher(line);
            if (header.find()) {
                if (inTarget) {
                    return new Block(implementation(implementations), files, values, null, null);
                }
                inTarget = header.group(1).equals(taskPath);
                continue;
            }
            if (!inTarget) {
                continue;
            }
            Matcher m;
            if ((m = KEY.matcher(line)).matches()) {
                return new Block(implementation(implementations), files, values, m.group(2), null);
            }
            if ((m = NON_CACHEABLE.matcher(line)).matches()) {
                return new Block(implementation(implementations), files, values, null, m.group(1));
            }
            if ((m = IMPLEMENTATION.matcher(line)).matches()) {
                implementations.add(m.group(1));
            } else if ((m = ADDITIONAL_IMPLEMENTATION.matcher(line)).matches()) {
                // A task action / doLast block's own implementation hash; folded in alongside the task
                // class's so a build-logic change made only through a task action is still visible here.
                implementations.add(m.group(1));
            } else if ((m = VALUE.matcher(line)).matches()) {
                values.put(m.group(1), m.group(2));
            } else if ((m = FILES.matcher(line)).matches()) {
                files.put(m.group(1), entries(m.group(2), line));
            } else if (OUTPUT_PROPERTY.matcher(line).matches()) {
                // Does not affect the key material we track.
            } else if (line.startsWith("Appending ")) {
                throw unrecognised(line);
            }
        }
        return new Block(implementation(implementations), files, values, null, null);
    }

    private static String implementation(List<String> implementations) {
        return String.join(", ", implementations);
    }

    private static Map<String, String> entries(String body, String originalLine) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body.isEmpty() || body.equals("EMPTY")) {
            return out;
        }
        // Split before parsing each entry so a "'" inside a normalized path can never merge two entries:
        // every entry boundary is ", " followed by the next entry's absolute path, which always starts with "/".
        for (String entry : body.split(", (?=/)")) {
            Matcher m = ENTRY.matcher(entry);
            if (!m.matches()) {
                throw unrecognised(originalLine);
            }
            String value = m.group(2) != null ? m.group(2) : entry.endsWith("DIR") ? "DIR" : "MISSING";
            out.put(m.group(1), value);
        }
        return out;
    }

    private static IllegalArgumentException unrecognised(String line) {
        return new IllegalArgumentException("unrecognised cache debug output: " + line);
    }
}
