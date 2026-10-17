# Scenarios

`tools/scenarios.sh` replays these commits on a throwaway branch and runs `./trace` for every app in both flavours.
`cj` = `compileJava` key, `fp` = `fingerprint` key, `bz` = bazel-diff. `C` changed/impacted, `-` clean.

| # | commit | appA cj/fp/bz | appB cj/fp/bz | appC cj/fp/bz | lesson |
|---|---|---|---|---|---|
| S1 | utils: body-only edit | - / C / C | - / C / C | - / C / C | ABI unchanged ⇒ no recompiles; runtime bytes differ everywhere |
| S2 | core: add public method | C / C / C | C / C / C | - / - / - | ABI change reaches appA through `api`; appC untouched |
| S3 | lib: commons-lang3 3.18.0 → 3.20.0 | - / C / C | - / - / - | - / - / - | `implementation` dep: runtime-only input for appA |
| S4 | appC: gson 2.10.1 → 2.11.0 | - / - / - | - / - / - | C / C / C | direct dep whose ABI differs |
| S5 | core: comment-only edit | - / - / C | - / - / C | - / - / - | bazel-diff hashes sources; Gradle hashes bytecode |
| S6 | build logic: `-Xlint:all` in both builds | C / C / C | C / C / C | C / C / C | every Gradle task key includes the build-logic jar (implementation hash); every Bazel target hashes its `javacopts` |

## Findings
(Record here anything the run showed that differs from the table, with the fingerprint that moved.)
