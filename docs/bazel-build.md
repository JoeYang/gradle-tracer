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

## Conventions
- Only files named `*Test.java` under `src/test/java` become test targets (one `java_test` each, run through
  `junit5_test_suite`). Everything else there — fakes, helpers, resources — compiles into the `<name>_support`
  library and never runs on its own, unlike Gradle, which runs anything JUnit discovers on the test classpath.
- `MODULE.bazel`'s `known_contributing_modules = ["protobuf"]` lets protobuf's own Maven artifacts (guava,
  checker-qual, error_prone_annotations) into `maven_install.json` and version resolution, even though nothing
  in this repo depends on them — it is rules_jvm_external quieting a multi-module contribution warning, not a
  new dependency edge.

## Not under Bazel
`build-logic` (Gradle plugins, TestKit tests) and the tracer's `e2eTest` suite are Gradle-only by nature.

## Commands
    bazel build //...   bazel test //...   bazel run //app/appA -- Ada

## bazel-diff
`tools/fetch-bazel-diff.sh` downloads the pinned release binary (v60.0.0, sha256-checked) to `tools/bin/` (gitignored).
`--offline` maps to `--repository_disable_download` for the Bazel queries: it works on a fresh output base once the repository
cache is warm, and fails with a clear "download is disabled" error when it is cold.
Override with `BAZEL_DIFF=/path/to/binary`. The tracer runs `generate-hashes -w <worktree> -b bazel --fineGrainedHashExternalRepos=@maven`
per commit and `get-impacted-targets` per adjacent pair, then `bazel clean --expunge` in the worktree so each throwaway
output base is removed. Building bazel-diff from the Bazel Central Registry module was rejected: it compiles Rust via rules_rust.

Both `generate-hashes` and `get-impacted-targets` run `bazel mod graph` first in the repo checkout, which evaluates
two module extensions (`rules_android`'s `apksig_extension` and `com_android_dex_extension`) that no target in this
repo otherwise reaches, adding ~36 lines to `MODULE.bazel.lock`; `bazel build`/`test`/`query` alone never touch them.
Once that fuller lockfile is committed it is stable — repeated `generate-hashes`, `get-impacted-targets`, and
`bazel test //...` runs add nothing further. The measured hashes themselves are identical with or without those
extra entries; the actual effect of an uncommitted lockfile is a dirty `git status`, not drifting hashes. Re-run
`bazel mod graph` (and re-commit `MODULE.bazel.lock`) after any `MODULE.bazel` change so the lockfile stays clean.
