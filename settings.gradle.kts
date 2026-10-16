pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "gradle-tracer"

include("utils", "core", "lib")
include("app:appA", "app:appB", "app:appC")
include("tools:tracer")
