package io.joeyang.tracer.cli;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A Gradle task's build-cache key and the fingerprints it was built from.
 *
 * @param taskPath e.g. {@code :app:appA:compileJava}
 * @param key the cache key hash as printed by Gradle
 * @param fileFingerprints input property name → (file path → content hash); e.g. {@code classpath → {/x/core.jar → ab12}}
 * @param valueFingerprints input property name → hash of a scalar input, e.g. {@code options.compilerArgs}
 * @param implementation the task implementation hash (changes when build logic changes)
 */
public record TaskKey(String taskPath, String key, Map<String, Map<String, String>> fileFingerprints,
                      Map<String, String> valueFingerprints, String implementation) {

    public TaskKey {
        Objects.requireNonNull(taskPath, "taskPath must not be null");
        Objects.requireNonNull(fileFingerprints, "fileFingerprints must not be null");
        Objects.requireNonNull(valueFingerprints, "valueFingerprints must not be null");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("cache key must not be blank for " + taskPath);
        }
        Map<String, Map<String, String>> files = new LinkedHashMap<>();
        fileFingerprints.forEach((property, byFile) -> files.put(property, Collections.unmodifiableMap(new LinkedHashMap<>(byFile))));
        fileFingerprints = Collections.unmodifiableMap(files);
        valueFingerprints = Collections.unmodifiableMap(new LinkedHashMap<>(valueFingerprints));
        implementation = implementation == null ? "" : implementation;
    }

    /** {@code 3f9c…3b} style abbreviation for tables. */
    public String shortKey() {
        return key.length() <= 7 ? key : key.substring(0, 4) + "…" + key.substring(key.length() - 2);
    }
}
