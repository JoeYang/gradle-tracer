#!/usr/bin/env bash
# Replays the teaching commits from docs/scenarios.md on a temporary branch (in a temporary worktree),
# runs ./trace for every app in both flavours, prints the tables and asserts the expected verdicts.
# Env: KEEP_BRANCH=1 keeps the demo branch.
set -euo pipefail
unset CDPATH

ROOT="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/.." && pwd)"
cd "$ROOT"
BRANCH="demo/scenarios-$(date +%Y%m%d-%H%M%S)"
WT="$(mktemp -d "${TMPDIR:-/tmp}/gradle-tracer-scenarios.XXXXXX")"
TRACE_FLAGS=()
PROJECTS=(utils core lib app:appA app:appB app:appC tools:tracer)
FAILURES=0

# Every lock artifact --write-locks can touch, for the two dependency-bump commits.
LOCK_PATHS=(gradle/libs.versions.toml settings-gradle.lockfile
  utils/gradle.lockfile core/gradle.lockfile lib/gradle.lockfile
  app/appA/gradle.lockfile app/appB/gradle.lockfile app/appC/gradle.lockfile tools/tracer/gradle.lockfile)

cleanup() {
  git -C "$ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || true
  rm -rf "$WT"
  [[ "${KEEP_BRANCH:-0}" == 1 ]] || git -C "$ROOT" branch -D "$BRANCH" >/dev/null 2>&1 || true
}
trap cleanup EXIT

say() { printf '\n== %s\n' "$*"; }

# Every git call that commits or checks out inside the demo worktree goes through this, so the
# developer's global git config (gpgsign, hooks, excludesFile) can never break the run.
# Mirrors EndToEndTest.java's GIT_ISOLATION (tools/tracer/src/e2eTest/java/io/joeyang/tracer/cli/EndToEndTest.java).
gitw() {
  git -C "$WT" -c commit.gpgsign=false -c core.hooksPath=/dev/null -c core.excludesFile=/dev/null "$@"
}

# edit FILE OLD NEW — exact replacement; fails loudly unless OLD occurs exactly once.
edit() {
  python3 - "$1" "$2" "$3" <<'PY'
import pathlib, sys
path, old, new = sys.argv[1:4]
p = pathlib.Path(path)
text = p.read_text()
n = text.count(old)
if n != 1:
    sys.exit(f"scenarios: expected exactly one occurrence of {old!r} in {path}, found {n}")
p.write_text(text.replace(old, new))
PY
}

relock() {
  local tasks=()
  for p in "${PROJECTS[@]}"; do tasks+=(":$p:dependencies"); done
  (cd "$WT" && ./gradlew -q "${tasks[@]}" --write-locks >/dev/null)
}

# commit MESSAGE PATH... — stages exactly the given paths and commits with an isolated git config.
commit() {
  local msg="$1"; shift
  gitw add -- "$@"
  gitw commit -q -F - <<EOF
$msg
EOF
  gitw rev-parse HEAD
}

say "creating $BRANCH in $WT"
git -C "$ROOT" -c commit.gpgsign=false -c core.hooksPath=/dev/null -c core.excludesFile=/dev/null \
  worktree add -q -b "$BRANCH" "$WT" HEAD
BASE="$(gitw rev-parse HEAD)"

say "S1 utils: body-only edit"
edit "$WT/utils/src/main/java/io/joeyang/tracer/utils/Formatter.java" \
'        return text + " ".repeat(width - text.length());   // SCENARIO S1 edits this line (body only)' \
'        StringBuilder padded = new StringBuilder(text);
        while (padded.length() < width) {
            padded.append(" ");
        }
        return padded.toString();'
commit "utils: pad with a loop (body only)" utils/src/main/java/io/joeyang/tracer/utils/Formatter.java >/dev/null

say "S2 core: add a public method"
edit "$WT/core/src/main/java/io/joeyang/tracer/core/Greeter.java" \
'        return Formatter.pad("Hello,", column) + name + "!";
    }
}' \
'        return Formatter.pad("Hello,", column) + name + "!";
    }

    public String shout(String name) {
        return greet(name).toUpperCase();
    }
}'
commit "core: add Greeter.shout()" core/src/main/java/io/joeyang/tracer/core/Greeter.java >/dev/null

say "S3 lib: commons-lang3 3.18.0 -> 3.20.0 (relocking; needs network)"
edit "$WT/gradle/libs.versions.toml" 'commons-lang3 = "3.18.0"' 'commons-lang3 = "3.20.0"'
relock
commit "deps: commons-lang3 3.18.0 -> 3.20.0" "${LOCK_PATHS[@]}" >/dev/null

say "S4 appC: gson 2.10.1 -> 2.11.0 (relocking; needs network)"
edit "$WT/gradle/libs.versions.toml" 'gson = "2.10.1"' 'gson = "2.11.0"'
relock
commit "deps: gson 2.10.1 -> 2.11.0" "${LOCK_PATHS[@]}" >/dev/null

say "S5 core: comment-only edit"
edit "$WT/core/src/main/java/io/joeyang/tracer/core/Greeter.java" '    // Greets by name.' '    // Greets the given name.'
commit "core: reword a comment" core/src/main/java/io/joeyang/tracer/core/Greeter.java >/dev/null

say "S6 build logic: -Xlint:all"
edit "$WT/build-logic/src/main/java/io/joeyang/tracer/buildlogic/JavaBaseConventionPlugin.java" \
'            task.getOptions().setEncoding("UTF-8");' \
'            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getCompilerArgs().add("-Xlint:all");'
TIP="$(commit "build: enable -Xlint:all" \
  build-logic/src/main/java/io/joeyang/tracer/buildlogic/JavaBaseConventionPlugin.java)"

# Expected gradle verdicts for S1..S6 (C = CHANGED, - = clean), per app and task.
declare -A EXPECT=(
  [appA:compileJava]="- C - - - C"  [appA:fingerprint]="C C C - - C"
  [appB:compileJava]="- C - - - C"  [appB:fingerprint]="C C - - - C"
  [appC:compileJava]="- - - C - C"  [appC:fingerprint]="C - - C - C"
)

check() {  # check LABEL EXPECTED ACTUAL
  if [[ "$2" == "$3" ]]; then printf 'OK   %-22s %s\n' "$1" "$3"; else printf 'FAIL %-22s expected [%s] got [%s]\n' "$1" "$2" "$3"; FAILURES=$((FAILURES + 1)); fi
}

for app in appA appB appC; do
  for task in compileJava fingerprint; do
    flags=("${TRACE_FLAGS[@]}")
    say "./trace $app --from ${BASE:0:7} --to ${TIP:0:7} --task $task"
    TSV="$WT/.trace-$app-$task.tsv"
    ./trace "$app" --from "$BASE" --to "$TIP" --task "$task" --tsv-out "$TSV" "${flags[@]}" || true
    gradle_actual="$(awk -F'\t' 'NR>1 { printf "%s%s", (NR>2 ? " " : ""), ($4=="CHANGED" ? "C" : ($4=="clean" ? "-" : "?")) }' "$TSV")"
    check "$app $task" "${EXPECT[$app:$task]}" "$gradle_actual"
  done
done

say "checkout untouched"
UNEXPECTED="$(git -C "$ROOT" status --porcelain | grep -v '^?? MEMORY.md$' || true)"
if [[ -n "$UNEXPECTED" ]]; then
  printf 'FAIL %-22s unexpected status in %s:\n%s\n' "checkout-untouched" "$ROOT" "$UNEXPECTED"
  FAILURES=$((FAILURES + 1))
else
  printf 'OK   %-22s %s\n' "checkout-untouched" "no unexpected status in $ROOT"
fi

cat <<'LEGEND'

Why the columns disagree (see docs/scenarios.md):
  S1 body-only edit      compile: ABI unchanged, nothing recompiles · fingerprint: runtime bytes changed
  S3 commons-lang3 bump  compile: implementation dep of lib, not on appA's compile classpath · fingerprint: changed
  S5 comment-only edit   identical bytecode, so neither key changes
  S6 build logic         every Gradle task key includes the build-logic jar
LEGEND

if [[ "$FAILURES" -eq 0 ]]; then say "all expectations held"; else say "$FAILURES expectation(s) failed"; fi
exit "$FAILURES"
