# Build-input tracer for a Gradle + Bazel multi-module project

Status: approved 2026-10-16 (all decisions D1–D8 "Go"), amended after the independent review the same day (see §14). Branch: `feature/level1-input-tracer`.

## 1. Goal

Given an application module and a git revision range, report **which commits changed
the application's build inputs**, with the input that changed named. Two independent
oracles answer the question so they can be compared:

- **Gradle**: the build-cache key of a task of the app. Default task `compileJava`
  (compile inputs: own sources + ABI of the compile classpath). Alternative task
  `fingerprint` (runtime inputs: full content of the runtime classpath, including
  third-party jar versions).
- **Bazel**: `bazel-diff` impacted-target analysis with per-library external hashing (informational column).

This is "level 1" impact: *did the inputs change*, not *does the app reach the change*
(level 2) nor *which tests must run* (level 3).

Learning objectives (the reason this repo exists): Gradle multi-project layout, included
builds and convention plugins, task inputs/outputs and normalization (`@Classpath` vs
`@CompileClasspath`), build-cache keys, `api` vs `implementation`, dependency locking,
and how all of that compares with Bazel targets, `rdeps` and `bazel-diff`.

## 2. Non-goals

- Level 2 reachability analysis (call graphs, jdeps, japicmp) and level 3 test selection.
- CI integration, Nx, remote caches, Kotlin, Android.
- A stable library API. The tracer is a learning tool and a demo.

## 3. Repository layout

```
gradle-tracer/
  settings.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew*  gradle/wrapper/
  MODULE.bazel  maven_install.json  .bazelrc  BUILD.bazel  tools/defs.bzl
  build-logic/                 Gradle included build: convention plugins (Java) + FingerprintTask
  utils/   core/   lib/        library modules   (src/main/java, src/test/java, build.gradle.kts, BUILD.bazel)
  app/appA  app/appB  app/appC application modules (same shape)
  tools/tracer/                the tracer CLI (Java), built by both Gradle and Bazel
  tools/scenarios.sh           replayable demo
  tools/fetch-bazel-diff.sh    downloads the pinned bazel-diff binary to tools/bin/ (gitignored)
  trace                        thin wrapper: builds and runs tools/tracer
  docs/                        one page per component; README mirrors this design
```

Both build systems read the same `src/main/java` trees. Outputs are disjoint
(`build/` vs `bazel-*`), both ignored by git.

## 4. Module graph

```
                 commons-lang3 (impl)        gson (impl)
                        ▲                        ▲
 appA ──▶ lib ──api───▶ core ──impl──▶ utils ◀──impl── appC
                        ▲
 appB ──────────────────┘
```

- `lib` exposes `core` types in its public API, so it declares `api(project(":core"))`.
  Consequence: `core` is on `appA`'s compile classpath. An ABI change in `core` changes
  `appA`'s `compileJava` key.
- `core` uses `utils` internally only: `implementation`. `utils` is not on `appA`'s or
  `appB`'s compile classpath; a `utils` change can only reach them through the runtime
  fingerprint.
- `lib` uses `commons-lang3` internally (`implementation`). A version bump is invisible to
  `appA`'s `compileJava` and visible to its `fingerprint`.
- `appC` uses `gson` directly (`implementation`). A version bump with an ABI difference
  changes `appC`'s `compileJava` key.

Bazel mirrors this with `deps` for `implementation` and `exports` for `api`.

Java package root: `io.joeyang.tracer.<module>`. Language level 21 on both build systems
(see D6); JDK 25 on the machine runs the tools.

## 5. User-facing CLI

```
./trace <app> --from <rev> --to <rev> [--task compileJava|fingerprint|<any task>]
                                      [--no-bazel] [--offline] [--keep-worktrees]
                                      [--tsv | --tsv-out <file>] [--timeout-minutes <n>]
./trace <app> --at <rev> [--task ...]      # print the key and its composition, no comparison
```

`<app>` is `appA`, `appB` or `appC`. It maps to `:app:appA:<task>` and `//app/appA:appA`.

Example output:

```
Tracing inputs of :app:appA:compileJava (gradle) and //app/appA (bazel-diff) over 6 commits

commit   subject                              gradle key  bazel-diff  verdict   culprit
a1b2c3d  baseline                             3f9c…e1     -           base
b2c3d4e  utils: body-only edit                3f9c…e1     impacted    clean     bazel≠gradle
c3d4e5f  core: add Greeter.shout()            2bb4…77     impacted    CHANGED   core classes
d4e5f6a  deps: commons-lang3 3.14.0 -> 3.17.0 2bb4…77     impacted    clean     bazel≠gradle
e5f6a7b  deps: gson 2.10.1 -> 2.11.0          2bb4…77     clean       clean
f6a7b8c  core: comment-only edit              2bb4…77     impacted    clean     bazel≠gradle

1 of 6 commits changed compile inputs of appA. First: c3d4e5f.
```

Semantics:

- The **verdict** is the Gradle answer for the selected task: `CHANGED` when the key
  differs from the previous commit, `clean` otherwise, `base` for the first commit,
  `BUILD-FAILED` when the key could not be obtained.
- The **bazel-diff** column is informational: `impacted`, `clean` or `n/a`. It always
  measures *all transitive inputs*, so under `--task compileJava` it legitimately
  disagrees for runtime-only and source-only changes. Disagreements are flagged
  `bazel≠gradle`; the scenario script explains each one.
- The **culprit** is derived from Gradle's per-input fingerprints: changed classpath
  entries (jar names, or `<module> classes` for class directories — Gradle feeds project
  dependencies to `compileJava` as class directories and to the runtime classpath as jars),
  changed source files (`<module>/src/...`), changed input values (property names such as
  `options.compilerArgs`), or `build logic` when the task implementation hash changed.
- `--tsv` prints `sha\tsubject\tkey\tbazel\tverdict\tculprits` instead of the table; `--tsv-out <file>`
  prints the table and writes the TSV to a file (used by the scenario script).
- Exit codes: `0` no change in range, `3` at least one `CHANGED`, `1` usage error,
  `2` a tool failed so at least one verdict is `BUILD-FAILED`.

`--bisect` (first changing commit in log N builds) is a documented follow-up, not in scope.

## 6. Architecture

```
./trace appA --from A --to B --task compileJava
   │
   ▼
 Cli ─▶ RevisionRange (git rev-list A..B) ─▶ [c0, c1, … cn]
   │
   ▼  for each commit (WorktreePool: git worktree add <scratch>/wt-<sha>, removed on exit)
   ├─▶ GradleKeyProvider:  ./gradlew :app:appA:compileJava -Dorg.gradle.caching.debug=true --console=plain
   │        └─▶ CacheDebugLog parser ─▶ TaskKey{ key, inputs: {property → {file → hash}}, implementation }
   └─▶ BazelDiffProvider: bazel-diff generate-hashes -w <wt> --fineGrainedHashExternalRepos=maven ─▶ hashes-<sha>.json
   │
   ▼
 Comparator: adjacent pairs ─▶ Verdict{ changed?, culprit set }   +   bazel-diff get-impacted-targets(prev, cur) ∋ //app/appA ?
   │
   ▼
 TableRenderer ─▶ stdout, exit code
```

Components, each with one job and a seam for tests:

| Component | Responsibility | Depends on |
|---|---|---|
| `build-logic` convention plugins | apply `java-library`/`application`, release 21, JUnit 5, reproducible jars, build cache on | Gradle API |
| `FingerprintTask` (`build-logic`) | `@CacheableTask`; single `@Classpath` input = `sourceSets.main.runtimeClasspath`; writes a marker file | Gradle API |
| `ProcessRunner` (interface) | run a command with cwd, env, timeout; returns exit code + stdout + stderr | JDK `ProcessBuilder` |
| `RevisionRange` | resolve `--from/--to` to an ordered commit list; reject unknown revs | `ProcessRunner` (git) |
| `WorktreePool` | create one worktree per commit under the scratch dir; prune stale ones at start; remove on exit incl. Ctrl-C | `ProcessRunner` (git) |
| `GradleKeyProvider` + `CacheDebugLog` | run the task with cache debug; parse key, per-file fingerprints, implementation hash | `ProcessRunner` |
| `BazelDiffProvider` | `generate-hashes` per worktree, `get-impacted-targets` per adjacent pair | `ProcessRunner` (bazel) |
| `Comparator` | adjacent-commit diff → verdict + culprits; merges bazel column | pure |
| `TableRenderer` | fixed-width table, truncation, totals line | pure |
| `Cli` | args, wiring, exit codes | all of the above |

## 7. Technical decisions

### D1. Gradle oracle: task build-cache key, read via cache debug output
- **Why.** The cache key is already a content hash of every input Gradle knows about, with
  the right normalization per task: `compileJava` uses `@CompileClasspath` (ABI only), so a
  same-ABI library bump is invisible, exactly matching "would appA recompile". `installDist`
  is a `Sync` task and is never cached, so it cannot serve as the runtime oracle; hence the
  separate `FingerprintTask` with a `@Classpath` input.
- **What.** Run `./gradlew :app:appA:<task> -Dorg.gradle.caching.debug=true --console=plain --no-parallel --build-cache`,
  parse `Build cache key for task ':app:appA:<task>' is <hash>` and the preceding
  `Appending implementation / input value fingerprint / input file fingerprints` lines of that task's block
  (`--no-parallel` keeps blocks from interleaving). Formats verified on Gradle 9.1, see §14.
- **Pros.** No extra tooling. Per-file hashes name the culprit. Teaches cache keys and
  input normalization directly.
- **Cons.** Debug output is not a stable API; the parser is isolated and tested against
  captured fixtures (real logs checked into test resources), so a Gradle upgrade that changes the
  format fails a test instead of yielding silent `clean` verdicts.
- **Alternatives.** Shared cache dir and look for `FROM-CACHE`: works, kept as fallback if
  the debug format proves unusable. Reproducible-archive hash of `installDist`: needs every
  Jar task reproducible and gives no culprit. Nx `affected`: project granularity, treats a
  version-catalog edit as global.

### D2. Bazel oracle: bazel-diff with fine-grained Maven hashing
- **Why.** Purpose-built for "impacted targets between two revisions", actively released, and
  `--fineGrainedHashExternalRepos=maven` hashes each pinned third-party jar separately (verified: 51
  `@maven//` targets hashed individually in the spike).
- **What.** A pinned, sha256-checked release binary (`tools/fetch-bazel-diff.sh`, v60.0.0, a single static
  Rust executable); run `generate-hashes -w <worktree> -b bazel --fineGrainedHashExternalRepos=maven` per commit
  and `get-impacted-targets` for adjacent pairs; check whether `//app/appA:appA` is listed; `bazel clean --expunge`
  in the worktree afterwards so throwaway output bases do not accumulate.
- **Pros.** Independent reference answer. Per-library granularity through `maven_install.json`.
- **Cons.** Hashes sources and rule attributes, not compiled output, so a comment-only edit
  is "impacted". One Bazel analysis per worktree costs time and disk; `--no-bazel` skips it.
- **Alternatives.** The Bazel Central Registry module (`bazel_dep(name = "bazel-diff")`, `@bazel-diff//:bazel-diff`):
  compiles the Rust binary through rules_rust on first use — minutes and a toolchain download, rejected for a
  learning repo. `bazel query rdeps` plus manual lockfile diff: re-implements bazel-diff badly. Action-cache hit
  check: exact but needs a full build per revision.

### D3. Revisions materialised as throwaway git worktrees
- **Why.** Checking out in place would clobber the user's working tree.
- **What.** `git worktree add <scratch>/wt-<sha> <sha>` per commit; `git worktree prune` at
  start; removal in a shutdown hook so Ctrl-C and failures leave nothing behind;
  `--keep-worktrees` for debugging.
- **Cons.** N checkouts and N cold Gradle/Bazel runs for N commits. Acceptable for a demo;
  `--bisect` is the follow-up for long ranges.
- **Alternatives.** In-place checkout: rejected. Shallow clone per revision: slower, no gain.

### D4. Tracer written in Java, built by both build systems
- **Why.** Single-language repo, JUnit tests with a fake `ProcessRunner` make the failure
  paths (timeouts, non-zero exits, garbage output, missing binaries) cheap to test, and the
  tracer becomes one more module in the graph that both build systems must build. The same
  reasoning puts the convention plugins in Java (`Plugin<Project>` classes under `build-logic`) rather
  than Kotlin DSL precompiled scripts: no Kotlin compiler on JDK 25, and one language to learn.
- **What.** `tools/tracer` with the `application` plugin and a `java_binary`; `./trace` is a
  few-line wrapper that runs `./gradlew -q :tools:tracer:installDist` and execs the launcher.
- **Cons.** More code than a shell script; nested Gradle invocations (tracer built by Gradle,
  then spawning Gradle in worktrees). Different project dirs, so no daemon conflict.
- **Alternatives.** Bash + jq: shortest, but parsing and fault-injection tests are painful.
  Python: adds a second toolchain to a Java repo.

### D5. Replayable scenario script instead of permanent fixture commits
- **Why.** Fixture SHAs break on rebase and the demo should be re-runnable as the code
  evolves.
- **What.** `tools/scenarios.sh` creates a temporary branch from `HEAD`, applies the commits
  in section 8, runs `./trace` for each app and task flavour, prints the tables with an
  explanation per `bazel≠gradle` row, asserts the expected verdicts, deletes the branch.
- **Alternatives.** Tagged fixture commits: stable but pollute history and go stale.

### D6. Pinned dependencies; Java 21 language level
- **Why.** "Between two revisions" is only well defined if each revision resolves to exactly
  one set of jars. Bazel 9's default Java toolchain is a remote JDK 21.
- **What.** Gradle version catalog + dependency locking (`gradle.lockfile` per module);
  Bazel `maven_install.json` (rules_jvm_external 7.2, `bazel run @maven//:pin`). `options.release = 21` and
  Bazel `--java_language_version=21 --java_runtime_version=remotejdk_21` plus the same two `--tool_*` flags:
  without them rules_jvm_external's jar stamper fails with `UnsupportedClassVersionError` (default tool JDK is 11).
- **Cons.** Lockfiles must be regenerated on every bump (that *is* the scenario).
- **Alternatives.** Java 25 language level: blocked until the Bazel toolchain story for 25 is
  confirmed; revisit later.

### D7. Deliberate `api` vs `implementation` edges
- **Why.** The difference is the single most important Gradle concept for compile-input
  impact, and it only shows if one edge of each kind is on an app's path.
- **What.** Exactly the graph in section 4.
- **Alternatives.** All `implementation`: appA would never see a core ABI change at compile
  time and the demo would teach less.

### D8. Default `--task compileJava`; `fingerprint` as the runtime flavour
- **Why.** User priority. Both flavours come from the same mechanism, so the option is free.
- **What.** `--task` accepts any task path suffix; docs name the two meaningful ones.

## 8. Scenarios and expected verdicts

Expected per-app verdict under `--task compileJava` / `--task fingerprint`, and the
bazel-diff column. `C` = changed/impacted, `-` = clean.

| # | Commit | appA cj/fp/bz | appB cj/fp/bz | appC cj/fp/bz | Lesson |
|---|---|---|---|---|---|
| S0 | baseline | base | base | base | |
| S1 | utils: body-only edit | - / C / C | - / C / C | - / C / C | ABI unchanged ⇒ no recompiles anywhere; runtime bytes differ |
| S2 | core: add public method | C / C / C | C / C / C | - / - / - | ABI change reaches appA through `api`; appC untouched |
| S3 | lib: commons-lang3 3.14.0 → 3.17.0 | - / C / C | - / - / - | - / - / - | `implementation` dep: runtime-only input for appA |
| S4 | appC: gson 2.10.1 → 2.11.0 | - / - / - | - / - / - | C / C / C | direct dep with ABI difference (verified: the bump changed `compileJava`'s key in the spike) |
| S5 | core: comment-only edit, same line count | - / - / C | - / - / C | - / - / - | bazel-diff hashes sources; Gradle hashes bytecode |
| S6 | build logic: `-Xlint:all` in both builds | C / C / C | C / C / C | C / C / C | build definition is an input; the build-logic jar is part of every Gradle task's implementation hash, so even `fingerprint` changes (verified in the spike) |

The scenario script asserts this table. Any deviation found during implementation is a
finding to record in `docs/scenarios.md`, not something to paper over.

## 9. Error handling

| Fault | Behaviour |
|---|---|
| unknown `--from`/`--to`, unknown app, empty range | message naming the bad value, exit 1, nothing created |
| stale worktrees from a killed run | pruned at start; reported |
| Gradle fails at one commit (compile error, wrapper download failure) | row `BUILD-FAILED` with the first error line, continue, exit 2 at the end |
| bazel or bazel-diff missing or failing | column `n/a` with one warning line, continue; `--no-bazel` silences |
| network down | `--offline` passes through to Gradle and Bazel; with locked deps and warm caches the run succeeds, otherwise `BUILD-FAILED` with the cause |
| subprocess hang | per-process timeout (default 10 min), process tree killed, row `BUILD-FAILED` |
| Ctrl-C | shutdown hook removes worktrees and temp files |
| debug output format not recognised | `BUILD-FAILED` row with "unrecognised cache debug output", never a silent `clean` |

## 10. Testing

TDD throughout: tests first, then the implementation that makes them pass.

- **Sample modules.** JUnit (Jupiter 6.1.3) unit tests for every public method in `utils`, `core`, `lib`
  and the three apps, run under both `./gradlew test` and `bazel test //...` (Bazel runs them through the
  JUnit Platform console launcher via a `junit5_test_suite` macro in `tools/defs.bzl`).
- **`FingerprintTask` and convention plugins.** Gradle TestKit tests in `build-logic`: key is
  stable across two runs; stable across two different project directories; changes when a
  runtime dependency version changes; unchanged on a comment-only edit; task is cacheable
  and `FROM-CACHE` on the second run; task fails clearly when the runtime classpath cannot
  be resolved.
- **Tracer unit tests** with a fake `ProcessRunner`: `CacheDebugLog` parser on captured
  fixtures (happy path, truncated log, interleaved daemon noise, no key line, two tasks in
  one log); `Comparator` culprit derivation for jar, source, input-value and implementation
  changes; `RevisionRange` on bad revs and empty ranges; `WorktreePool` cleanup on exception;
  `GradleKeyProvider`/`BazelDiffProvider` on non-zero exit, timeout, empty output, missing
  binary; `TableRenderer` truncation; `Cli` exit codes.
- **End-to-end test** (separate `e2eTest` source set, slower): builds a throwaway git repo
  from the real modules, applies two scenario commits, runs the real tracer, asserts the
  verdicts. Skipped when `bazel` is absent for the Bazel column only.
- **Scenario script** doubles as the acceptance test (section 8).

## 11. Documentation

- `README.md`: purpose, the module graph, the CLI, build/run commands. Must stay identical to
  the code; updated in the same commit as any change it describes.
- `docs/gradle-build.md` (layout, convention plugins, catalog, locking, cache settings),
  `docs/bazel-build.md` (MODULE, toolchain flags, maven pins, bazel-diff), `docs/tracer.md`
  (components, debug-output format, exit codes), `docs/scenarios.md` (table + explanations).

## 12. Delivery

Feature branch `feature/level1-input-tracer`; each commit ≤ 200 lines hand-written target,
400 hard max; generated files (`gradlew`, wrapper jar, `maven_install.json`, `*.lockfile`)
excluded from the count; each commit leaves the repo building and testing under both tools.

1. `chore(repo)`: `.gitignore`, this spec, README stub.
2. `feat(gradle)`: settings, wrapper 9.8.1, `gradle.properties`, version catalog, `build-logic`
   with the convention plugins (Java), dependency locking enabled.
3. `feat(libs)`: `utils`, `core`, `lib` sources, tests, Gradle build files.
4. `feat(app)`: `appA`, `appB`, `appC` sources, tests, Gradle build files.
5. `feat(bazel)`: `MODULE.bazel`, `.bazelrc`, `BUILD.bazel` for every module, `maven_install.json`.
6. `feat(build-logic)`: `FingerprintTask` with TestKit tests.
7. `feat(tracer)`: types and interfaces only (`ProcessRunner`, `TaskKey`, `Verdict`, provider
   interfaces) plus both build files for `tools/tracer`.
8. `feat(tracer)`: `CacheDebugLog` + `GradleKeyProvider` + tests.
9. `feat(tracer)`: `BazelDiffProvider` + tests.
10. `feat(tracer)`: `RevisionRange`, `WorktreePool`, `Comparator` + tests.
11. `feat(tracer)`: `TableRenderer`, `Cli`, `./trace` wrapper, e2e test.
12. `feat(demo)`: `tools/scenarios.sh`, `docs/scenarios.md`, README final.

Docs for each component land in the commit that introduces the component.

## 13. Confirmed before implementation (spike, 2026-10-16)

1. `-Dorg.gradle.caching.debug=true` line formats on Gradle 9.1 — captured verbatim, see §14.
2. bazel-diff CLI flags and the fine-grained repo name `maven` — confirmed with the v60.0.0 binary.
3. Bazel 9.1 + rules_java on a JDK 25 host — `remotejdk_21` for targets and tools, see D6.
4. Cache-key stability across worktree paths — the same project copied to another directory
   produced the identical `compileJava` key; the TestKit test in commit 6 keeps proving it.

## 14. Amendments after the independent review (2026-10-16)

Verified by experiment in a scratch directory (Gradle 9.1.0, Bazel 9.1.0, bazel-diff v60.0.0):

- Cache debug output, verbatim formats (paths shortened):
  `Appending implementation to build cache key: conv.FingerprintTask_Decorated@052c…`,
  `Appending input value fingerprint for 'options.release' to build cache key: 91e6…`,
  `Appending input file fingerprints for 'classpath' to build cache key: b19a… - COMPILE_CLASSPATH{/w/core/build/classes/java/main/demo/core/Greeter.class='demo/core/Greeter.class' / 5042…}`,
  `Appending input file fingerprints for 'runtimeClasspath' ...: CLASSPATH{..., /w/core/build/libs/core.jar=IGNORED / ba11…}`,
  `Build cache key for task ':app:compileJava' is 35c0f7cbe157cd2e30090cc5510cec8c`. Non-cacheable tasks print
  `Non-cacheable because Not worth caching [NOT_CACHEABLE]` instead of a key. Printed at the default log level.
- `compileJava` key: unchanged on a no-op rerun, unchanged on a body-only edit in a dependency, changed when a
  public method is added, unchanged again on a comment-only edit, identical after copying the project to another
  directory.
- `fingerprint` key (prototype of D1's task): changed on a gson bump and on a `-Xlint:all` build-logic change;
  `compileJava` of a direct gson consumer also changed on the bump (S4 confirmed) and on the build-logic change (S6).
- bazel-diff: `--fineGrainedHashExternalRepos=maven` and the canonical `rules_jvm_external++maven+maven` produce
  identical output (51 `@maven//` targets); coarse mode hashes none individually.
- Bazel on this host: `UnsupportedClassVersionError` from rules_jvm_external's stamper until
  `--tool_java_runtime_version=remotejdk_21` (and `--tool_java_language_version=21`) are set.

Design changes resulting from the review and from current versions:

| area | spec said | now |
|---|---|---|
| Gradle | 9.1.x | 9.8.1 (current) |
| JUnit | 5 | Jupiter 6.1.3 |
| bazel-diff | BCR module | pinned prebuilt v60.0.0 binary via `tools/fetch-bazel-diff.sh` (BCR module compiles Rust) |
| rules_java / rules_jvm_external | unspecified | 9.9.0 / 7.2 |
| convention plugins | unspecified | Java `Plugin<Project>` classes, not Kotlin DSL |
| culprit for a core change under `compileJava` | `core.jar` | `core classes` (class directories on the compile classpath) |
| S6 `fingerprint` column | clean | changed (build-logic jar is in every task's implementation hash) |
| CLI | — | `--tsv`, `--tsv-out <file>`, `--timeout-minutes <n>` added; Gradle runs use `--no-parallel` |
| Bazel tests | unspecified | JUnit console launcher macro; `build-logic` TestKit tests and the e2e suite stay Gradle-only |
| Bazel hygiene | — | `bazel clean --expunge` per worktree after hashing |
