# Scenario demo preflight: replay only from a clean branch tip

Date: 2026-10-18. Status: approved in conversation (D1, D2 "Go"). Follow-up to the Task 15 scenario replay.

## Problem

`tools/scenarios.sh` cuts its throwaway demo worktree from the committed `HEAD` (`worktree add -b $BRANCH $WT HEAD`).
Two states of the main checkout silently give a stale base:

- a detached HEAD replays from wherever the checkout happens to be, not from the branch tip;
- uncommitted edits are left out entirely, because the worktree only sees commits.

The second case surfaced on 2026-10-18: the greeting fix in `Greeter.java` was still uncommitted while HEAD carried the
old line, so S2's exact-match edit would have failed twenty seconds into a 40-minute run with a message about the wrong
file. The demo should refuse to start in either state and say which branch and commit it replays from.

## D1. A standalone `tools/clean-branch-tip.sh`

One script, one job: decide whether `<repo>` is a clean checkout at the tip of a named branch.

Contract (`tools/clean-branch-tip.sh <repo>`; checks run in this order, the first failure wins):

| State | stdout | stderr | exit |
|---|---|---|---|
| wrong argument count | | `usage: tools/clean-branch-tip.sh <repo>` | 2 |
| `git` not on PATH | | `error: git is not on PATH` | 2 |
| `<repo>` not inside a git work tree | | `error: <repo> is not inside a git work tree` | 2 |
| detached HEAD | | `error: <repo> is at a detached HEAD (<sha7>); check out a branch so the demo replays from its tip` | 1 |
| `git status --porcelain` non-empty | | `error: <repo> has uncommitted changes; commit or stash them so the demo replays exactly the tip of <branch>:` then each porcelain line indented two spaces | 1 |
| clean branch tip | `<branch> <sha40>` | | 0 |

Rules:

- `<repo>` is echoed back as given. Branch comes from `git symbolic-ref -q --short HEAD` (`rev-parse --abbrev-ref`
  prints the literal `HEAD` when detached, which is why it is not used). Branch names never contain spaces, so the
  caller can split the output on the first space.
- "Dirty" means plain `git status --porcelain` (modified, staged, untracked; ignored files do not count). It honours
  the developer's own git config on purpose, so the verdict matches what `git status` shows them. No `-c` isolation.
- Conventions as in `tools/fetch-bazel-diff.sh` (removed 2026-10-18; see tag `with-bazel`): `set -euo pipefail`, `error:` prefix on stderr, exit 1 for a refusal,
  exit 2 for a bad invocation. No `cd`; every git call uses `git -C "$repo"`.

Wiring in `tools/scenarios.sh`, immediately after `cd "$ROOT"` and before `BRANCH=`/`mktemp`/`worktree add`, so that a
refusal creates nothing and needs no cleanup:

```bash
SRC="$("$ROOT/tools/clean-branch-tip.sh" "$ROOT")"    # refuses with a message otherwise (set -e stops here)
SRC_BRANCH="${SRC%% *}"; SRC_HEAD="${SRC##* }"
```

The "creating …" line becomes `creating $BRANCH in $WT from $SRC_BRANCH @ ${SRC_HEAD:0:7}`. After
`BASE="$(gitw rev-parse HEAD)"` one assertion checks `BASE == SRC_HEAD` and exits 1 with both short shas otherwise
(the EXIT trap already removes the worktree at that point). This is the literal check that "the latest is the head of
the current branch", made at the point of use.

Alternatives considered: an inline function in `scenarios.sh` (its `ROOT` comes from `BASH_SOURCE`, so a test cannot
point it at a throwaway repo without test-only env knobs); replaying with uncommitted changes folded into a temporary
commit (hides the problem and the table would no longer name a real commit); do nothing (S2 does fail, but only after
the worktree, branch and first Gradle run exist).

## D2. JUnit cases in the existing `e2eTest` suite, written first

`CleanBranchTipScriptTest` beside `EndToEndTest` (`tools/tracer/src/e2eTest/java/io/joeyang/tracer/cli/`). It finds the
script through the `tracer.repoRoot` system property the suite already sets. Fixture: `git init -q -b work` in a
`@TempDir` plus one commit, every git call carrying `SAFE_GIT_CONFIG` and an explicit identity (same idiom as
`EndToEndTest`, via the module's `ProcessRunner`). The script under test runs through `ProcessBuilder`, which gives
separate stdout/stderr and lets one case override `PATH`; each invocation gets a 30-second wait so a hung git cannot
stall the suite.

Cases (each asserts exit code, and stdout or the stderr prefix):

1. clean tip prints `work <sha>`, sha equals `git rev-parse HEAD`, exit 0, empty stderr
2. detached HEAD: exit 1, stderr contains `detached HEAD`
3. modified tracked file: exit 1, stderr lists ` M <file>`
4. staged-only change: exit 1, stderr lists `M  <file>`
5. untracked file: exit 1, stderr lists `?? <file>`
6. file matched by `.gitignore`: exit 0 (ignored files do not count)
7. no argument: exit 2, stderr starts with `usage:`
8. plain directory that is not a work tree: exit 2, stderr contains `not inside a git work tree`
9. `PATH` without git (script launched as `/bin/bash <script>`): exit 2, stderr `error: git is not on PATH`, never a
   bash stack trace

Not covered on purpose: Bazel `sh_test` (needs git in the sandbox; the e2e suite is Gradle-only) and a bash test file
(no runner would pick it up).

## Docs

- `README.md` Demo section: one sentence that the script refuses to start unless the checkout is clean and on a
  branch, and prints the branch and commit it replays from.
- `docs/scenarios.md`: a "Preconditions" line with the same statement and the exit code.
- The embedded copy of `scenarios.sh` in `docs/superpowers/plans/2026-10-16-build-input-tracer.md` is a record of
  Task 15 as delivered and is not updated.

## Non-goals

- Checking whether the branch is behind an upstream (the repo has no remote).
- Replaying the working tree or a stash.
- Changing the post-run "checkout untouched" check. Its `?? MEMORY.md` exemption is dead now that MEMORY.md is tracked;
  removing it is a separate one-line tidy-up.

## Commits

1. `feat(tools): add clean-branch-tip.sh with its e2e tests` — script (about 45 lines) and the nine cases (about
   110 lines), tests written first.
2. `feat(scenarios): refuse to replay from a dirty or detached checkout` — the three wiring lines, the `BASE` assertion,
   README and `docs/scenarios.md`.
