# gradle-tracer

A learning repo: the same Java modules built by Gradle and Bazel, plus a tracer that reports which
commits changed an application's build inputs. Design: `docs/superpowers/specs/2026-10-16-build-input-tracer-design.md`.

## Layout

See `docs/gradle-build.md` (Gradle side) and `docs/bazel-build.md` (Bazel side).

## Build and test

    ./gradlew build                      # every module + unit tests
    ./gradlew :tools:tracer:e2eTest      # tracer end-to-end (slow)
    bazel test //...                     # the same modules and tests under Bazel

## Trace

    ./trace appA --from <rev> --to <rev>                 # which commits changed appA's compile inputs
    ./trace appA --from <rev> --to <rev> --task fingerprint   # … its runtime inputs (library bumps included)
    ./trace appA --at HEAD                               # what the key is made of

See `docs/tracer.md` for the table format and exit codes.

## Demo

    tools/scenarios.sh            # replays six teaching commits and checks every verdict (20–40 min, needs network)
    NO_BAZEL=1 tools/scenarios.sh # Gradle only

`docs/scenarios.md` explains each row.
