# Recreating the build-input tracer

This file lets you rebuild the tracer somewhere else from a single prompt. Paste the prompt below into a fresh
coding-agent session (for example Claude Code) started in an empty directory. You get two things:

1. a small multi-module Gradle repo to trace, and
2. `./trace`, a CLI that walks a git range and reports which commits changed an app's Gradle build inputs, and which
   inputs they were.

To add the tracer to an existing Gradle repo instead, read Part 1 as "what the tracer needs from the build", and swap
in your own module names.

Everything in the prompt describes how this repo behaves today, as documented in `docs/tracer.md`,
`docs/gradle-build.md` and `docs/scenarios.md`. The section after the prompt explains the requirements that are not
obvious.

## The prompt

````text
Build a "build-input tracer" for a Gradle repo: a Java CLI that walks a git commit range and reports, per commit,
whether an app's Gradle build-cache key changed and which inputs changed it. Build it on Linux. It is POSIX-only and
uses only the JDK at runtime.

WAY OF WORKING
- First write a design spec and a task-by-task plan and show them to me for approval. Then build task by task with
  TDD: write a failing test, watch it fail, make it pass.
- Commits: Conventional Commits with a body that explains why. Aim for under 200 hand-written lines per commit; never
  go over 400. Put interfaces and types in their own commit before the implementation. Update the docs in the same
  commit as the code they describe. Work on a feature branch, never on main.
- After each task, review it twice: first against the spec, then for quality (failure paths, resource cleanup, tests).
  Fix what the reviews find before you start the next task.
- No deprecated APIs. Run Gradle with --warning-mode=all to check.

PART 1: THE REPO TO TRACE
- Use the Gradle 9.8.1 wrapper with the Kotlin DSL. Compile everything with `--release 21`.
- gradle.properties: org.gradle.caching=true (the tracer depends on this), org.gradle.parallel=true.
- settings.gradle.kts: `pluginManagement { includeBuild("build-logic") }`, and declare Maven Central once with
  RepositoriesMode.FAIL_ON_PROJECT_REPOS.
- gradle/libs.versions.toml is the only place a version appears: junit 6.1.3 (BOM), commons-lang3 3.18.0,
  gson 2.10.1. build-logic imports the same catalog.
- Lock every configuration in every module (lockAllConfigurations) and commit every gradle.lockfile, plus
  settings-gradle.lockfile.
- build-logic is an included build of Java convention plugins:
  - tracer.java-base applies `java` and sets --release 21, UTF-8, reproducible jars, JUnit Platform with JUnit from the
    catalog, and dependency locking. It also registers a task named `fingerprint` on every module:
    - a @CacheableTask whose only input is sourceSets.main.runtimeClasspath, declared @Classpath;
    - its output is build/fingerprint/runtime-classpath.txt. Nothing reads that file. The point is the task's cache
      key, which is a path- and timestamp-independent hash of the whole runtime classpath.
  - tracer.java-library = base + java-library.
  - tracer.java-application = base + application.
  - Write TestKit tests for the plugins, each with its own local build cache directory so they are hermetic.
- Modules (Java package io.<you>.tracer.<module>):
    utils              library, no deps
    core               library, implementation(project(":utils")); calls utils only inside method bodies
    lib                library, api(project(":core")) because a public method returns a core type;
                       implementation(commons-lang3), used only inside method bodies
    app:appA           application, implementation(project(":lib")), so it reaches core only through lib's api edge
    app:appB           application, implementation(project(":core"))
    app:appC           application, implementation(project(":utils")), implementation(gson)
    tools:tracer       application named "trace", no dependencies: the CLI below
  Each app validates its argument; on a bad one it prints "appX: <reason>" to stderr and exits 2. Give every module
  unit tests.

PART 2: THE TRACER CLI (tools/tracer)
Usage:
  trace <app> --from <rev> --to <rev> [--task <name>] [--offline] [--keep-worktrees] [--tsv] [--tsv-out <file>]
              [--timeout-minutes <n>]
  trace <app> --at <rev> [--task <name>] [--offline] [--keep-worktrees] [--timeout-minutes <n>]
- <app> maps to :app:<app>:<task>. --task defaults to `fingerprint` (runtime inputs). Use `compileJava` to ask
  "would it recompile?". --timeout-minutes is per external command and defaults to 10.
- Exit codes: 0 no change (or --at succeeded), 1 usage error, 2 a tool failed, 3 at least one CHANGED. If a range has
  both a BUILD-FAILED row and a CHANGED row, 2 wins over 3. Map UsageException to 1 and ProviderException to 2.
  Catch any other RuntimeException in run() and report it as "internal error: <class>: <message>" with exit 2, never
  as a stack trace.
- Option validation:
  - <app> must match [A-Za-z0-9][A-Za-z0-9_-]*.
  - Every value-taking option rejects a value that is blank or starts with "-", so `--task --offline` fails instead of
    swallowing the next flag. --timeout-minutes is the exception: it accepts only ASCII digits with an optional "-",
    and reports "needs a whole number", "too large" or "must be a positive integer".
  - --at rejects --tsv and --tsv-out.
- Interfaces for testability: ProcessRunner (run(cwd, timeout, command) -> Result; "could not run" is an exception)
  and KeyProvider (the Gradle oracle). Types: Commit, TaskKey (key, implementation hash, value fingerprints,
  property -> file -> hash), Status {BASE, CHANGED, CLEAN, BUILD_FAILED}, Verdict.
- DefaultProcessRunner:
  - capture UTF-8 stdout and stderr on named daemon threads, with stdin from /dev/null;
  - one deadline bounds both the wait and the output drain;
  - on timeout, kill every descendant, then the child itself. Descendants first, so the child's death doesn't hide
    its descendants.
- Gradle oracle (GradleKeyProvider + CacheDebugLog). In the worktree, run:
    <worktree>/gradlew --console=plain --no-parallel --build-cache -Dorg.gradle.caching.debug=true <task>
  (add --offline when asked). Parse the target task's block. Everything goes to stdout.
    "Appending implementation to build cache key: <class>@<hash>"                  -> implementation hash
    "Appending input value fingerprint for '<prop>' ...: <hash>"                   -> value input
    "Appending input file fingerprints for '<prop>' ...: <hash> - NORM{<path>='<n>' / <hash|DIR|MISSING>, ...}"
                                                                                    -> per-file hashes
    "Build cache key for task '<path>' is <32 hex>"                                 -> the key; closes the block
    "Non-cacheable because <reason>"                                                -> closes it with no key
  - If any other "Appending" line appears inside the block, fail loudly with "unrecognised cache debug output", naming
    the line. Never drop it silently.
  - When gradlew fails, report the real cause: first a javac "<file>.java:<n>: error:" line; else the first line
    under "* What went wrong:", joined with the next line when that one is indented; else the first non-blank line.
- Git layer (RevisionRange):
  - resolve(from, to) runs `git log --reverse --first-parent --format=%H%x1f%P%x1f%s from..to`. Check that each row's
    first parent is the previous row's sha, so an off-line `from` is a usage error that names both shas.
  - `git rev-parse --verify --quiet <rev>^{commit}` exiting 1 means an unknown revision (usage error). Any other
    failure is a ProviderException.
  - An empty range is a usage error.
  - resolveOne(rev) does the same for --at, including checking git log's exit code.
- Worktrees (WorktreePool):
  - One fixed scratch root, $TMPDIR/gradle-tracer. Read the TMPDIR environment variable directly, because the JVM
    ignores it on Linux. Make the root absolute and normalised.
  - Per commit: `git worktree add --detach <root>/wt-<sha7> <sha>`.
  - At start, delete stale directories named exactly wt-<7 hex>, then `git worktree prune`, in that order. Touch
    nothing else under the root.
  - close() and a shutdown hook run `git worktree remove --force`. A failed removal is a warning. --keep-worktrees
    skips cleanup and registers no hook.
- Scratch root safety, in Cli:
  - Create the root rwx------. An existing root must be owned by the current uid (compare unix:uid, not user names,
    with NOFOLLOW_LINKS) and have no group/other write. Otherwise exit 1 with a one-line message.
  - Hold an exclusive FileChannel.tryLock() on <root>/.lock for the whole run. Treat null and
    OverlappingFileLockException the same way. A second run then exits 1 with "another trace is using <root>".
  - Never sweep .lock.
- Fixed order in Cli.run:
  1. Check that the app directory exists.
  2. Pre-check --tsv-out: the parent exists, is a directory and is writable; the target is not a directory; an existing
     target is writable; a dangling symlink is rejected; for a symlink, check the real target's parent.
  3. Resolve the revisions. This is read-only git, so a bad revision exits 1 with nothing created.
  4. Create, check and lock the scratch root.
  5. Sweep.
  6. Walk.
- The walk:
  - A key-provider failure becomes that commit's BUILD-FAILED row, and the next commit is compared with the last good
    key.
  - A checkout failure, an interrupt, or an unexpected exception aborts the walk, and cleanup still runs. Check the
    interrupt flag at the top of each iteration, after a failed lookup, and once after the loop.
- Comparison (KeyComparator): no key -> BUILD-FAILED; no previous key -> base; equal keys -> clean; otherwise CHANGED,
  with culprits named by location-independent display names:
  - strip the worktree root (anchor on the /wt-<7 hex>/ segment, then the first /src/);
  - project dependencies appear as "core classes" (compile classpath) or "core.jar" (runtime classpath);
  - module-cache jars appear as "group:artifact", so a version bump is one culprit;
  - changed value properties by name;
  - "build logic" when the implementation hash differs, always sorted first;
  - "inputs changed (no fingerprint detail)" when nothing more specific is known.
  Show at most four culprits plus "+N more", and only when there are more than five.
- Output (TableRenderer):
  - The table has columns commit | subject | key | verdict | culprit. Pad and truncate by Unicode code point, not
    UTF-16 char, and end with a summary line: "N of M commits changed <compile|runtime> inputs of <app>. First:
    <sha>." or "No commit changed ... of <app>.", followed by " K commit(s) could not be built." when any row is
    BUILD-FAILED.
  - TSV: sha, subject, key, verdict, culprits, with no header; the first row is the base. In a BUILD-FAILED row the
    last column holds the error. Replace each tab, CR or LF inside a field with a space.
  - --tsv writes the TSV only. --tsv-out writes the table to stdout and the TSV to a file. Write the file atomically:
    a temp file next to the real target, then ATOMIC_MOVE. Keep an existing file's mode, and write through a symlink.
  - --at prints the key, the implementation hash, every value fingerprint, and every file fingerprint grouped by
    property.

PART 3: THE ./trace WRAPPER (repo root, executable bash)
  set -euo pipefail; unset CDPATH; resolve the script's real path with `readlink -f "${BASH_SOURCE[0]}"` and cd to
  its directory. Then:
    ./gradlew -q :tools:tracer:installDist >&2 || { echo "trace: building tools/tracer failed" >&2; exit 2; }
    exec "$ROOT/tools/tracer/build/install/trace/bin/trace" "$@"
  Document that a relative --tsv-out resolves against the repo root.

PART 4: TESTS
- Unit tests with fake ProcessRunner/KeyProvider cover every path, including failure injection:
  - every option-validation message;
  - unknown --from, --to and --at, and an empty range (assert that no scratch root is created);
  - lock held; a non-private, symlinked or foreign-owned root (inject the uid through a package-private seam);
  - scratch root is a file; .lock is a directory;
  - --tsv-out read-only, missing parent, dangling symlink, failed write (the previous file stays intact);
  - checkout failure mid-walk (no files left, lock released); interrupt; an unchecked exception;
  - exit codes 0, 2 and 3, and 2 winning over 3.
- e2eTest, a separate JvmTestSuite that `build` does not run, with a 15-minute timeout:
  - Copy only the files `git ls-files` lists into a throwaway repo, and commit a public method added to core.
  - Expect appA to be CHANGED (exit 3) and appC to be clean (exit 0).
  - Pass these to every fixture git call: `-c user.name=... -c user.email=... -c commit.gpgsign=false
    -c core.hooksPath=/dev/null -c core.excludesFile=/dev/null`.
- tools/scenarios.sh, the acceptance test:
  - In a temporary worktree on a temporary branch cut from HEAD, commit six teaching changes. Use the same git -c
    isolation flags plus core.autocrlf=false.
    S1 utils: method-body-only edit
    S2 core: add a public method
    S3 lib: commons-lang3 3.18.0 -> 3.20.0 (relock)
    S4 appC: gson 2.10.1 -> 2.11.0 (relock)
    S5 core: comment-only edit with the same line numbers
    S6 build-logic: add -Xlint:all to the compiler args
  - Run `./trace <app> --from BASE --to TIP --task <t> --tsv-out <f>` for appA/appB/appC x compileJava/fingerprint.
    Capture trace's exit code (with set -e, exit 3 would kill the script). Read the verdict from TSV column 4,
    skipping the base row. If a run writes no TSV, report FAIL and continue.
  - Assert these verdicts for S1..S6 (C = CHANGED, - = clean):
      appA compileJava "- C - - - C"   fingerprint "C C C - - C"
      appB compileJava "- C - - - C"   fingerprint "C C - - - C"
      appC compileJava "- - - C - C"   fingerprint "C - - C - C"
  - Snapshot `git status --porcelain` before the run and compare it afterwards, so the user's checkout is provably
    untouched.
  - Cleanup: remove the worktree, then `git worktree prune`, then delete the branch.
  - Don't capture commit SHAs with X="$(commit ...)", because errexit is off inside $(). Commit first, then
    rev-parse.
  - If a verdict disagrees, do NOT edit the expectations. Reproduce the case by hand with --at on both sides, find the
    fingerprint that moved, and report it to me.

DOCS: write a concise README (purpose, build, ./trace examples, demo), docs/gradle-build.md (plugins, module graph and
why each edge is api or implementation, locking, the fingerprint task), docs/tracer.md (every behaviour above) and
docs/scenarios.md (the S1-S6 table and what each one teaches).

DONE WHEN: `./gradlew build --warning-mode=all` is green with no warnings, `./gradlew :tools:tracer:e2eTest` passes,
`tools/scenarios.sh` ends with "all expectations held" and exit 0 and leaves no demo branch or worktree behind, and
`./trace appA --at HEAD` prints the key's composition.
````

## Why the prompt says what it says

Each of these was found the hard way while building the original, usually by a review or by a scenario run that
failed.

| Requirement | What goes wrong without it |
|---|---|
| Pin the Gradle version, and fail on any unrecognised `Appending` line | The cache-debug format is internal to Gradle. If it changes, a lenient parser builds a wrong key and reports `clean` without any warning. |
| Pass `--build-cache` explicitly | The flag overrides `caching=false` and makes Gradle print full input fingerprints. A task that is merely UP-TO-DATE prints only the key line. |
| Fresh worktree per commit, and compare against the last *good* key | Reusing a checkout leaks state between commits. Comparing against a failed commit hides the next real change. |
| `fingerprint` as the default task | `compileJava` uses ABI normalisation, so body-only edits and library bumps that sit behind `implementation` stay `clean` (S1, S3). That answers "would it recompile?", not "did what the app runs with change?". |
| Validate option values, and reject `--tsv`/`--tsv-out` with `--at` | Without the check, `--task --offline` took `--offline` as the task name and dropped offline mode, and `--at … --tsv-out f` exited 0 without writing anything. |
| Resolve revisions before taking the lock | Taking the lock first left `.lock` behind after a typo in a revision, even though "nothing created" was a requirement. |
| Private scratch root, compared by uid | `/tmp/gradle-tracer` is predictable, and the tool runs `gradlew` from inside it. Comparing user names fails when the uid has no passwd entry, as in `docker run -u $(id -u)`. |
| Kill descendants before the child on timeout | A Gradle daemon that this run spawned is still a descendant, so it dies with the run. A daemon reused from an earlier run is already detached, so it survives by design. |
| Atomic `--tsv-out`, keeping the mode, writing through a symlink | A plain write truncates the old file before writing, so a full disk loses the previous file. A naive temp-file-and-move changes the mode to 0600 and replaces symlinks. |
| `-c` git isolation in the tests and the demo | A global `commit.gpgsign`, a hook, or `core.excludesFile` with `*.jar` broke the fixture. The last one dropped `gradle-wrapper.jar`, so every row came back BUILD-FAILED. |
| Status snapshot instead of an ignore list in the demo | An ignore list breaks when a file's tracked state changes. It reported a false FAIL after a run that otherwise passed completely. |
| Set permissions with chmod after creating a directory, not with `asFileAttribute` | `asFileAttribute` is filtered by the process umask, so a group-writable test fixture passed under one test runner and failed under another. |

## What a correct run looks like

```text
$ ./trace appA --from <base> --to <tip>
Tracing inputs of :app:appA:fingerprint (gradle) over 6 commits

commit   subject                              gradle key  verdict       culprit
13ab5c1  refactor(tracer): reuse culpritColu… 08f1…b7     base
20bcb1b  utils: pad with a loop (body only)   6e94…92     CHANGED       utils.jar
815766a  core: add Greeter.shout()            2873…3d     CHANGED       core.jar
a3b392c  deps: commons-lang3 3.18.0 -> 3.20.0 75f1…12     CHANGED       org.apache.commons:commons-lang3
07ecf3c  deps: gson 2.10.1 -> 2.11.0          75f1…12     clean
c12f2a5  core: reword a comment               75f1…12     clean
f1ae4f5  build: enable -Xlint:all             ef15…21     CHANGED       build logic

4 of 6 commits changed runtime inputs of appA. First: 20bcb1b.
```

This is appA's `fingerprint` pass from a real `tools/scenarios.sh` run. The six commits are S1 to S6, and the verdicts
match the expected `C C C - - C`.
