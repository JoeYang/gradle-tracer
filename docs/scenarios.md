# Scenarios

`tools/scenarios.sh` replays these commits on a throwaway branch and runs `./trace` for every app in both flavours.
`cj` = `compileJava` key, `fp` = `fingerprint` key. `C` changed, `-` clean.

| # | commit | appA cj/fp | appB cj/fp | appC cj/fp | lesson |
|---|---|---|---|---|---|
| S1 | utils: body-only edit | - / C | - / C | - / C | ABI unchanged ⇒ no recompiles; runtime bytes differ everywhere |
| S2 | core: add public method | C / C | C / C | - / - | ABI change reaches appA through `api`; appC untouched |
| S3 | lib: commons-lang3 3.18.0 → 3.20.0 | - / C | - / - | - / - | `implementation` dep: runtime-only input for appA |
| S4 | appC: gson 2.10.1 → 2.11.0 | - / - | - / - | C / C | direct dep whose ABI differs |
| S5 | core: comment-only edit | - / - | - / - | - / - | Gradle hashes bytecode, so a comment-only edit changes no key |
| S6 | build logic: `-Xlint:all` | C / C | C / C | C / C | every Gradle task key includes the build-logic jar (implementation hash) |

## Findings
(Record here anything the run showed that differs from the table, with the fingerprint that moved.)
