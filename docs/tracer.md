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
