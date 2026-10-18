# gradle-tracer

A learning repo: a set of Java modules built by Gradle, plus a tracer that reports which
commits changed an application's build inputs. The build is Gradle only on purpose, a deliberate exception to the author's Bazel-for-Java default.
Design: `docs/tracer.md` (the tracer) and `docs/gradle-build.md` (the build). The original Gradle+Bazel
design, superseded when the repo went Gradle-only, is recorded for history at
`docs/superpowers/specs/2026-10-16-build-input-tracer-design.md`.

## Layout

See `docs/gradle-build.md`.

## Build and test

    ./gradlew build                      # every module + unit tests
    ./gradlew :tools:tracer:e2eTest      # tracer end-to-end (slow)

## Trace

    ./trace appA --from <rev> --to <rev>                    # which commits changed appA's runtime inputs (library bumps included)
    ./trace appA --from <rev> --to <rev> --task compileJava # … only its compile inputs (would appA recompile?)
    ./trace appA --at HEAD                                  # what the key is made of

See `docs/tracer.md` for the table format and exit codes.

## Demo

    tools/scenarios.sh # replays six teaching commits and checks every verdict (20–40 min, needs network)

`docs/scenarios.md` explains each row.

## Recreate it elsewhere

`docs/recreate-tracer.md` holds a single prompt that rebuilds this repo and the tracer from scratch.
