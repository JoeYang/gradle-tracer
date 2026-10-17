# tracer

`tools/tracer` is a Java CLI. Today `Cli.main` parses and validates options. On a usage error it exits 1 with the
usage text. Valid options currently exit 2, printing a not-implemented message, because no verdict is produced
yet. The range walk, the `./trace` wrapper and exit codes 0 and 3 arrive in later commits.

## Vocabulary (`io.joeyang.tracer.cli`)
| type | meaning |
|---|---|
| `ProcessRunner` / `Result` | runs a command; non-zero exit is a `Result`, "could not run" is a `ProcessException` |
| `DefaultProcessRunner` | the real `ProcessRunner`: UTF-8 stdout/stderr capture on named daemon threads (`tracer-stdout-N`/`tracer-stderr-N`), stdin from `/dev/null`, a single deadline bounding both the process wait and the drain, and a process-tree kill (not just the direct child) on timeout |
| `Commit` | sha + subject |
| `TaskKey` | a task's cache key plus per-input fingerprints (`property → file → hash`), value fingerprints and implementation hash |
| `Status` | `BASE`, `CHANGED`, `CLEAN`, `BUILD_FAILED` — the Gradle verdict |
| `BazelStatus` | `IMPACTED`, `CLEAN`, `NOT_AVAILABLE`, `NONE` — the informational column |
| `Verdict` | one table row |
| `KeyProvider`, `ImpactProvider` | the two oracles behind interfaces so tests can fake them |
| `UsageException` → exit 1 (wired); `ProviderException` → exit 2, wired by a later commit | error classes |

## Exit codes
0 no change in range · 1 usage error (wired) · 2 a tool failed (some row is BUILD-FAILED) · 3 at least one
CHANGED — 0, the "a tool failed" sense of 2, and 3 are wired by a later commit; `Cli.main` exits 2 today as an
interim placeholder whenever the options are valid.

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
non-zero exit → the real Bazel `ERROR:` line (not bazel-diff's own wrapper line); no output file → explicit error;
a stray non-label line in `get-impacted-targets`' output → explicit error naming the line. Mapping these into the
`BazelStatus` column (`NOT_AVAILABLE` rather than failing the whole row) arrives in a later commit.

## Git layer
- `RevisionRange.resolve(from, to)` → `[from, …from..to oldest first]`, following the first-parent line so adjacent rows are parent and child even across merges. `from` must be an ancestor of `to` on that first-parent line (each row's own first parent, from `%P`, must equal the previous row's sha); a side-branch commit pulled in by a merge is a `UsageException` naming both shas, not a silent mismatch. Unknown revision (`git rev-parse --verify --quiet` exit 1) or an empty range → `UsageException`; any other git failure (not runnable, exit 128, a malformed `git log` line) → `ProviderException`, naming the offending line where there is one.
- `WorktreePool` → sweeps stale worktree directories *before* pruning (see below), then `git worktree add --detach <root>/wt-<sha7> <sha>` per commit. `close()` and a shutdown hook run `git worktree remove --force`; a failed removal is a warning, not a thrown exception, so one bad worktree doesn't stop the rest from being cleaned up.

### Worktree root rules
- The sweep at construction only ever touches directories directly under the pool's root whose name is exactly `wt-` plus 7 lowercase hex characters; anything else there (a stray file, an unrelated directory) is left alone.
- Two `WorktreePool`s must never point at the same root at the same time: the sweep-then-prune startup sequence and `wt-<sha7>` naming assume the root is exclusively theirs for the run's duration, and a second pool's sweep would delete worktrees the first one is actively using.
- `--keep-worktrees` leaves everything — the checked-out directories and their `git worktree` registrations — in place when the run ends; no shutdown hook is even registered, so an interrupted run leaves them too. The *next* pool opened on that root deletes whatever is there in its startup sweep (git included, via sweep-then-prune) regardless of `--keep-worktrees`; a commit `checkout()` again within the same run tears down and recreates its worktree rather than reusing it in place.

## Comparison
`KeyComparator.verdict(commit, previousGoodKey, currentKey, error, bazel)`: no current key → `BUILD-FAILED` (defaulting the
error message to "no cache key obtained" when none is given); no previous → `base`; equal keys → `clean`; otherwise `CHANGED`
with culprits = file fingerprints that differ (compared by location-independent display name: `core.jar`,
`appA/src/main/java/.../AppA.java`, `appA classes`, `appA resources` for a changed resource file, and `group:artifact` for
jars from the Gradle module cache so a version bump is one culprit — the worktree root is stripped first, using
`WorktreePool`'s `wt-<sha7>` directory naming, so a checkout path that itself contains `/src/` cannot leak into a name or
differ between commits), value properties that differ, and `build logic` when the task implementation hash differs, sorted
first so it is never hidden by the cap below. When none of the above differ despite the key changing, the single culprit is
"inputs changed (no fingerprint detail)". Capped at four named culprits plus a trailing `+N more`; exactly five print in
full with no `+N more` marker, since the cap only triggers above five.

`TableRenderer.render` prints the fixed-width table and a summary line; `renderTsv` prints `sha\tsubject\tkey\tbazel\tverdict\tculprits`
for scripts — for a `BUILD-FAILED` row, that last column is the error message, not the (empty) culprit list. Inside any
field, each tab, CR or LF character becomes a single space (so a CRLF becomes two spaces) so none of them can shift a
column. Table columns are padded and truncated by
Unicode code point, not UTF-16 char, so a surrogate pair is never split.

## Cli
`trace <app> --from A --to B [--task T] [--no-bazel] [--offline] [--keep-worktrees] [--tsv] [--tsv-out F] [--timeout-minutes N]`
and `trace <app> --at REV [--task T] [--offline] [--keep-worktrees] [--timeout-minutes N]`.

`<app>` must match `[A-Za-z0-9][A-Za-z0-9_-]*`; a blank, path-like (`.`, `..`, `../..`) or option-like (`-h`) name
is a usage error naming the rejected value. Every value-taking option (`--from`, `--to`, `--at`, `--task`,
`--tsv-out`, `--timeout-minutes`) rejects a value that is blank or starts with `-`, so a missing value can't
silently swallow the next option: `--from --to y` fails naming `--from`, rather than failing later on `y` as an
unknown option; a value missing at the end of the arguments fails the same way, naming the option. The one
exception is `--timeout-minutes`: its values are validated as integers instead (see below). `--at` prints the
key and its composition with no comparison, so it rejects `--tsv`, `--tsv-out` and `--no-bazel` alongside it,
naming both flags in the error.

`Cli.defaultScratchRoot` computes one fixed scratch root, `$TMPDIR/gradle-tracer`, that worktrees and bazel-diff
hash files will live under once the range walk is wired (never per pid — a fixed root is how the next run finds
and sweeps a killed run's leftovers). On Linux the JVM does not read `$TMPDIR` into `java.io.tmpdir` (it is
always `/tmp`), so it reads the `TMPDIR` environment variable directly and only falls back to `java.io.tmpdir`
when it is unset or blank, keeping the documented path real; either way, the result is resolved to an absolute,
normalized path, since `./trace` changes directory before `main` runs and a relative `TMPDIR` would otherwise
resolve against the wrong directory.

`--timeout-minutes` values are validated as integers instead of the generic blank-or-dash check above. The
value is checked against ASCII digits with an optional leading `-` before parsing: anything else, including a
non-ASCII digit, is "needs a whole number"; a value that is all ASCII digits but too large to fit a `long`, or
that overflows `Duration.ofMinutes`, is "too large"; and a value that parses but is zero or negative is "must
be a positive integer". `DefaultProcessRunner` rejects a non-positive timeout with an unchecked exception, so
the CLI validates it here as a usage error instead of ever passing one through.
