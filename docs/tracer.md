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
- `Appending input file fingerprints for '<property>' ...: <hash> - <NORMALIZATION>{<path>=<'normalized'|IGNORED> / <hash>, ...}` → per-file hashes
- `Build cache key for task '<path>' is <hash>` → the key
Project dependencies appear as class directories on the compile classpath and as jars on the runtime classpath, which is why
culprits read `core classes` under `compileJava` and `core.jar` under `fingerprint`. A task that prints `Non-cacheable because ...`
(jar, installDist, …) has no key; the provider reports the reason instead of a silent `clean`. An UP-TO-DATE task prints the
key line without input lines; the tracer always runs in a fresh worktree, so the inputs are present whenever the key changed.
