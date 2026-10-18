package io.joeyang.tracer.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

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
                                  Optional<String> error) {
        Objects.requireNonNull(commit, "commit must not be null");
        Objects.requireNonNull(previous, "previous must not be null");
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(error, "error must not be null");
        if (current.isEmpty()) {
            return new Verdict(commit, Status.BUILD_FAILED, Optional.empty(), List.of(),
                    Optional.of(error.orElse("no cache key obtained")));
        }
        if (previous.isEmpty()) {
            return new Verdict(commit, Status.BASE, current, List.of(), Optional.empty());
        }
        if (previous.get().key().equals(current.get().key())) {
            return new Verdict(commit, Status.CLEAN, current, List.of(), Optional.empty());
        }
        return new Verdict(commit, Status.CHANGED, current, culprits(previous.get(), current.get()), Optional.empty());
    }

    static List<String> culprits(TaskKey before, TaskKey after) {
        // "build logic" goes first unconditionally, ahead of file/value names, so it is never pushed past the
        // MAX_CULPRITS cap and hidden behind "+N more": a build-logic change is usually the most actionable one.
        List<String> names = new ArrayList<>();
        if (!before.implementation().equals(after.implementation())) {
            names.add("build logic");
        }
        Set<String> rest = new LinkedHashSet<>();
        for (String property : union(before.fileFingerprints().keySet(), after.fileFingerprints().keySet())) {
            Map<String, String> a = before.fileFingerprints().getOrDefault(property, Map.of());
            Map<String, String> b = after.fileFingerprints().getOrDefault(property, Map.of());
            Map<String, String> aByName = byDisplayName(a);
            Map<String, String> bByName = byDisplayName(b);
            for (String name : union(aByName.keySet(), bByName.keySet())) {
                if (!Objects.equals(aByName.get(name), bByName.get(name))) {
                    rest.add(name);
                }
            }
        }
        for (String property : union(before.valueFingerprints().keySet(), after.valueFingerprints().keySet())) {
            if (!Objects.equals(before.valueFingerprints().get(property), after.valueFingerprints().get(property))) {
                rest.add(property);
            }
        }
        names.addAll(rest);
        if (names.isEmpty()) {
            return List.of("inputs changed (no fingerprint detail)");
        }
        if (names.size() <= MAX_CULPRITS + 1) {
            return names;
        }
        List<String> capped = new ArrayList<>(names.subList(0, MAX_CULPRITS));
        capped.add("+" + (names.size() - MAX_CULPRITS) + " more");
        return capped;
    }

    /**
     * Paths differ between worktrees, so compare by a location-independent display name. Several paths can share a
     * name (every class file under {@code core/build/classes/...} is "core classes"), so the value is the sorted
     * join of all their hashes: it changes if any one of them changes.
     */
    private static Map<String, String> byDisplayName(Map<String, String> byPath) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        byPath.forEach((path, hash) -> grouped.computeIfAbsent(displayName(path), k -> new ArrayList<>()).add(hash));
        Map<String, String> out = new LinkedHashMap<>();
        grouped.forEach((name, hashes) -> out.put(name, hashes.stream().sorted().collect(Collectors.joining("+"))));
        return out;
    }

    /**
     * {@code .../core/build/libs/core.jar → core.jar}; {@code .../app/appA/src/main/java/X.java → appA/src/main/java/X.java};
     * {@code .../appA/build/classes/java/main/... → appA classes}; {@code .../appA/build/resources/main/... → appA resources};
     * {@code ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0/<sha>/gson-2.11.0.jar → com.google.code.gson:gson}
     * (so a version bump is one culprit, not a removed plus an added jar).
     *
     * <p>The worktree root (e.g. {@code .../wt-a1b2c3d/}) is stripped first, so a root path that itself happens to
     * contain {@code /src/} (a user's checkout under {@code ~/src/...}) never leaks into the name, and two commits'
     * worktrees — which differ only by that prefix — compare equal. What remains is matched against the module's
     * own {@code /src/}, using its first occurrence so a resource that happens to be nested under another
     * {@code src} directory (e.g. {@code core/src/main/resources/src/x.txt}) is still named relative to its real
     * module ({@code core/src/...}), not the inner one.
     */
    static String displayName(String path) {
        String p = stripWorktreeRoot(path.replace('\\', '/'));
        int classes = p.lastIndexOf("/build/classes/");
        if (classes >= 0) {
            String module = lastSegmentBefore(p, classes);
            return module.isEmpty() ? "classes" : module + " classes";
        }
        int resources = p.lastIndexOf("/build/resources/");
        if (resources >= 0) {
            String module = lastSegmentBefore(p, resources);
            return module.isEmpty() ? "resources" : module + " resources";
        }
        int cache = p.indexOf("/modules-2/files-2.1/");
        if (cache >= 0) {
            String[] parts = p.substring(cache + "/modules-2/files-2.1/".length()).split("/");
            if (parts.length >= 2) {
                return parts[0] + ":" + parts[1];
            }
        }
        int src = p.indexOf("/src/");
        if (src >= 0) {
            String module = lastSegmentBefore(p, src);
            return module.isEmpty() ? p.substring(src + 1) : module + p.substring(src);
        }
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /**
     * Strips everything up to and including the last {@code wt-<sha7>} path segment, i.e. {@link WorktreePool}'s
     * worktree directory naming ({@link WorktreePool#WT_DIR_NAME}); a path with no such segment is returned as is.
     * The result keeps its leading slash so the rest of {@link #displayName} can treat it uniformly.
     */
    private static String stripWorktreeRoot(String p) {
        String[] segments = p.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (WorktreePool.WT_DIR_NAME.matcher(segments[i]).matches()) {
                return "/" + String.join("/", Arrays.asList(segments).subList(i + 1, segments.length));
            }
        }
        return p;
    }

    private static String lastSegmentBefore(String p, int index) {
        int from = p.lastIndexOf('/', index - 1);
        return from < 0 ? "" : p.substring(from + 1, index);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new TreeSet<>(a);
        u.addAll(b);
        return u;
    }
}
