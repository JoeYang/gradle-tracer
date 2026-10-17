package io.joeyang.tracer.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Pure logic: turns two adjacent keys into a verdict and a short list of culprits. */
public final class KeyComparator {
    static final int MAX_CULPRITS = 4;

    private KeyComparator() {}

    /**
     * @param previous the last successfully obtained key before this commit; empty for the base commit
     * @param current the key at this commit; empty when the build failed
     * @param error the failure reason when {@code current} is empty
     */
    public static Verdict verdict(Commit commit, Optional<TaskKey> previous, Optional<TaskKey> current,
                                  Optional<String> error, BazelStatus bazel) {
        if (current.isEmpty()) {
            return new Verdict(commit, Status.BUILD_FAILED, Optional.empty(), bazel, List.of(),
                    Optional.of(error.orElse("no cache key obtained")));
        }
        if (previous.isEmpty()) {
            return new Verdict(commit, Status.BASE, current, bazel, List.of(), Optional.empty());
        }
        if (previous.get().key().equals(current.get().key())) {
            return new Verdict(commit, Status.CLEAN, current, bazel, List.of(), Optional.empty());
        }
        return new Verdict(commit, Status.CHANGED, current, bazel, culprits(previous.get(), current.get()), Optional.empty());
    }

    static List<String> culprits(TaskKey before, TaskKey after) {
        Set<String> names = new LinkedHashSet<>();
        for (String property : union(before.fileFingerprints().keySet(), after.fileFingerprints().keySet())) {
            Map<String, String> a = before.fileFingerprints().getOrDefault(property, Map.of());
            Map<String, String> b = after.fileFingerprints().getOrDefault(property, Map.of());
            Map<String, String> aByName = byDisplayName(a);
            Map<String, String> bByName = byDisplayName(b);
            for (String name : union(aByName.keySet(), bByName.keySet())) {
                if (!Objects.equals(aByName.get(name), bByName.get(name))) {
                    names.add(name);
                }
            }
        }
        for (String property : union(before.valueFingerprints().keySet(), after.valueFingerprints().keySet())) {
            if (!Objects.equals(before.valueFingerprints().get(property), after.valueFingerprints().get(property))) {
                names.add(property);
            }
        }
        if (!before.implementation().equals(after.implementation())) {
            names.add("build logic");
        }
        if (names.isEmpty()) {
            return List.of("inputs changed (no fingerprint detail)");
        }
        List<String> all = new ArrayList<>(names);
        if (all.size() <= MAX_CULPRITS + 1) {
            return all;
        }
        List<String> capped = new ArrayList<>(all.subList(0, MAX_CULPRITS));
        capped.add("+" + (all.size() - MAX_CULPRITS) + " more");
        return capped;
    }

    /**
     * Paths differ between worktrees, so compare by a location-independent display name. Several paths can share a
     * name (every class file under {@code core/build/classes/...} is "core classes"), so the value is the sorted
     * join of all their hashes: it changes if any one of them changes.
     */
    private static Map<String, String> byDisplayName(Map<String, String> byPath) {
        Map<String, List<String>> grouped = new java.util.LinkedHashMap<>();
        byPath.forEach((path, hash) -> grouped.computeIfAbsent(displayName(path), k -> new ArrayList<>()).add(hash));
        Map<String, String> out = new java.util.LinkedHashMap<>();
        grouped.forEach((name, hashes) -> out.put(name, hashes.stream().sorted().collect(java.util.stream.Collectors.joining("+"))));
        return out;
    }

    /**
     * {@code .../core/build/libs/core.jar → core.jar}; {@code .../app/appA/src/main/java/X.java → appA/src/main/java/X.java};
     * {@code .../appA/build/classes/java/main/... → appA classes};
     * {@code ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0/<sha>/gson-2.11.0.jar → com.google.code.gson:gson}
     * (so a version bump is one culprit, not a removed plus an added jar).
     */
    static String displayName(String path) {
        String p = path.replace('\\', '/');
        int classes = p.lastIndexOf("/build/classes/");
        if (classes >= 0) {
            return lastSegmentBefore(p, classes) + " classes";
        }
        int resources = p.lastIndexOf("/build/resources/");
        if (resources >= 0) {
            return lastSegmentBefore(p, resources) + " resources";
        }
        int cache = p.indexOf("/modules-2/files-2.1/");
        if (cache >= 0) {
            String[] parts = p.substring(cache + "/modules-2/files-2.1/".length()).split("/");
            if (parts.length >= 2) {
                return parts[0] + ":" + parts[1];
            }
        }
        int src = p.lastIndexOf("/src/");
        if (src >= 0) {
            return lastSegmentBefore(p, src) + p.substring(src);
        }
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static String lastSegmentBefore(String p, int index) {
        return p.substring(p.lastIndexOf('/', index - 1) + 1, index);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new TreeSet<>(a);
        u.addAll(b);
        return u;
    }
}
