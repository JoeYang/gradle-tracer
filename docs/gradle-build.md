# Gradle build

## Layout
- `settings.gradle.kts` includes the `build-logic` build (`pluginManagement.includeBuild`) and declares
  Maven Central once for every project (`FAIL_ON_PROJECT_REPOS`).
- `gradle/libs.versions.toml` is the only place a dependency version appears. `build-logic` imports the same
  file so plugins can add JUnit from it.
- `gradle.properties` turns the build cache on (`org.gradle.caching=true`); the tracer depends on that.

## Convention plugins (`build-logic`, written in Java)
| id | applies | adds |
|---|---|---|
| `tracer.java-base` | `java` | `--release 21`, UTF-8, JUnit platform + JUnit from the catalog, reproducible jars, `lockAllConfigurations()` |
| `tracer.java-library` | base + `java-library` | `api` vs `implementation` separation |
| `tracer.java-application` | base + `application` | `run`, `installDist`; module sets `application.mainClass` |

## Modules
| module | plugin id | dependencies |
|---|---|---|
| `utils` | `tracer.java-library` | none |
| `core` | `tracer.java-library` | `implementation(project(":utils"))` |
| `lib` | `tracer.java-library` | `api(project(":core"))`, `implementation(libs.commons.lang3)` (3.18.0) |
| `app:appA` | `tracer.java-application` | `implementation(project(":lib"))` |
| `app:appB` | `tracer.java-application` | `implementation(project(":core"))` |
| `app:appC` | `tracer.java-application` | `implementation(project(":utils"))`, `implementation(libs.gson)` |

`core` depends on `utils` with `implementation` because `core.Greeter` calls `Formatter.pad` only
inside a method body; `utils` never appears in a consumer's public API, so it stays off their
compile classpath. `lib` depends on `core` with `api` because `lib.Service.greeter()` returns a
`Greeter`, so any caller needs `core` on its own compile classpath to use what it gets back. `lib`
depends on `commons-lang3` with `implementation` because `StringUtils` is used only inside
`Service`'s method bodies, never exposed in a signature.

Each app depends on its libraries with `implementation`: nothing consumes an app, so no app type
needs to be visible further up the graph. `appA` reaches `core` only through `lib`'s `api` edge,
`appB` depends on `core` directly, and `appC` never touches `core` or `lib`.

`./gradlew build` runs every module's tests (33 today).

## Dependency locking
Every module locks all configurations. Regenerate after a version change:

    ./gradlew <module>:dependencies --write-locks

The same command also refreshes the root `settings-gradle.lockfile`, which locks the version
catalog's own resolution (`incomingCatalogForLibs0`). Commit it with the module lockfiles.

## Tests
`./gradlew -p build-logic test` runs the TestKit tests (they need network for JUnit). Each TestKit
project uses its own local build cache (`<project>/.build-cache`), so the functional tests are hermetic.
