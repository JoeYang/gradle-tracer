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

When `gradlew` exits non-zero, the provider looks for Gradle's own `* What went wrong:` header and reports the first
non-blank line after it (falling back to the first non-blank stderr/stdout line when Gradle didn't print that header),
naming the task path in the message either way — so a real compile error or an unknown task reads as the actual cause
instead of the generic "FAILURE: Build failed with an exception." banner. Computing *which* culprit changed between two
commits' `TaskKey`s, and the `./trace` revision-range walk that drives this provider, arrive in later commits.
