# gradle-tracer

A learning repo: the same Java modules built by Gradle and Bazel, plus a tracer that reports which
commits changed an application's build inputs. Design: `docs/superpowers/specs/2026-10-16-build-input-tracer-design.md`.

## Layout

See `docs/gradle-build.md` (Gradle side) and `docs/bazel-build.md` (Bazel side, added later).

## Build

    ./gradlew help         # no modules yet; `./gradlew build` arrives with the first module commit
