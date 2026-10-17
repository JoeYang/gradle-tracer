# tracer

`tools/tracer` is a Java CLI. Today `Cli.main` ignores its arguments and exits with the usage
text and a "not implemented yet" message; a `./trace` wrapper that builds it with `installDist` and runs the launcher,
and the revision-range walk that produces real verdicts, arrive in later commits.

## Vocabulary (`io.joeyang.tracer.cli`)
| type | meaning |
|---|---|
| `ProcessRunner` / `Result` | runs a command; non-zero exit is a `Result`, "could not run" is a `ProcessException` |
| `Commit` | sha + subject |
| `TaskKey` | a task's cache key plus per-input fingerprints (`property → file → hash`), value fingerprints and implementation hash |
| `Status` | `BASE`, `CHANGED`, `CLEAN`, `BUILD_FAILED` — the Gradle verdict |
| `BazelStatus` | `IMPACTED`, `CLEAN`, `NOT_AVAILABLE`, `NONE` — the informational column |
| `Verdict` | one table row |
| `KeyProvider`, `ImpactProvider` | the two oracles behind interfaces so tests can fake them |
| `UsageException` → exit 1, `ProviderException` → exit 2 | error classes |

## Exit codes
0 no change in range · 1 usage error · 2 a tool failed (some row is BUILD-FAILED) · 3 at least one CHANGED

## Gradle oracle
`GradleKeyProvider` runs `<worktree>/gradlew --console=plain --no-parallel --build-cache -Dorg.gradle.caching.debug=true <task>`
(`--offline` when asked) and `CacheDebugLog` parses, per task block:
- `Appending implementation to build cache key: <class>@<hash>` → implementation hash (changes with build logic)
- `Appending input value fingerprint for '<property>' ...: <hash>` → scalar inputs such as `options.compilerArgs`
- `Appending input file fingerprints for '<property>' ...: <hash> - <NORMALIZATION>{<path>=<'normalized'|IGNORED> / <hash|DIR|MISSING>, ...}` → per-file hashes
  (a directory or a deleted file prints `DIR`/`MISSING` in place of a hash; project dependencies otherwise appear as class
  directories on the compile classpath and as jars on the runtime classpath, which later commits use to name a culprit like
  `core classes` under `compileJava` versus `core.jar` under `fingerprint`)
- `Build cache key for task '<path>' is <hash>` → the key, which closes the block
- `Non-cacheable because <reason>` (jar, installDist, …) also closes the block with no key; the provider reports the reason
  instead of a silent `clean`
Any other `Appending ...` line inside the task's own block — a normalization token outside the known set, a malformed
`{...}` entry, anything a future Gradle version prints differently — fails parsing immediately with "unrecognised cache
debug output" naming the line, per spec §9, rather than silently dropping or mis-splitting it into a wrong key. An
UP-TO-DATE task prints the key line without input lines; the tracer always runs in a fresh worktree, so the inputs are
present whenever the key changed.

When `gradlew` exits non-zero, the provider first looks for a javac error line (`<file>.java:<line>: error: <message>`)
anywhere in the output, since Gradle's `* What went wrong:` header for a compile failure only says "Compilation failed;
see the compiler output below." — not the actual error. Failing that, it looks for the header and joins its first
non-blank line with the following line when that one is indented (e.g. "Selection failed" / "Cannot locate tasks that
match ..." for an unknown task, where the real cause is the indented continuation, not the header's own first line).
Failing that, it falls back to the first non-blank stderr/stdout line. The task path is named in the message either way.
A line inside the target task's own cache-debug block that CacheDebugLog cannot recognise is reported the same way, as a
`ProviderException` naming the task and the offending line, instead of escaping uncaught. Computing *which* culprit
changed between two commits' `TaskKey`s, and the `./trace` revision-range walk that drives this provider, arrive in
later commits.

## Bazel oracle
`BazelDiffProvider` — see `docs/bazel-build.md`. Failure modes: binary missing → the error names `tools/fetch-bazel-diff.sh`;
non-zero exit → first error line; no output file → explicit error. The Cli turns any of these into an `n/a` column, never a verdict.
