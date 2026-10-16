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

## Dependency locking
Every module locks all configurations. Regenerate after a version change:

    ./gradlew <module>:dependencies --write-locks

The same command also refreshes the root `settings-gradle.lockfile`, which locks the version
catalog's own resolution (`incomingCatalogForLibs0`). Commit it with the module lockfiles.

## Tests
`./gradlew -p build-logic test` runs the TestKit tests (they need network for JUnit). Each TestKit
project uses its own local build cache (`<project>/.build-cache`), so the functional tests are hermetic.
