# Bazel build

The same `src/main/java` and `src/test/java` trees as Gradle. Outputs go to `bazel-*` symlinks (gitignored).

## Files
- `MODULE.bazel` — rules_java 9.9.0, rules_jvm_external 7.2, Maven artifacts pinned in `maven_install.json`
  (`REPIN=1 bazel run @maven//:pin` after any version change — only the very first pin runs without `REPIN`; the scenario script does this for S3/S4).
- `.bazelrc` — remote JDK 21 for targets *and* tools; see the comment in the file for why the tool flags are required.
- `.bazelversion` — pins the Bazel release itself; without it a Bazelisk install would run "latest", which can rewrite `MODULE.bazel.lock` and shift the bazel-diff hashes later tasks rely on.
- `tools/defs.bzl` — `tracer_java_library`/`tracer_java_binary` apply `JAVACOPTS`; `junit5_test_suite` creates one
  `java_test` per `*Test.java` running `org.junit.platform.console.ConsoleLauncher execute --select-class <class>`,
  with helper sources and resources in a `<name>_support` library.

## Edge mapping
| Gradle | Bazel |
|---|---|
| `implementation(project(":x"))` | `deps = ["//x"]` |
| `api(project(":x"))` | `deps = ["//x"], exports = ["//x"]` |
| `implementation(libs.foo)` | `deps = ["@maven//:group_artifact"]` |
| `application { mainClass }` | `java_binary(main_class, runtime_deps = [":app_lib"])` |

## Not under Bazel
`build-logic` (Gradle plugins, TestKit tests) and the tracer's `e2eTest` suite are Gradle-only by nature.

## Commands
    bazel build //...   bazel test //...   bazel run //app/appA -- Ada
