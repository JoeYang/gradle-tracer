plugins {
    id("tracer.java-application")
}

application {
    mainClass.set("io.joeyang.tracer.cli.Cli")
    applicationName = "trace"
}

testing {
    suites {
        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
            }
            targets {
                all {
                    testTask.configure {
                        description = "Runs the tracer against a throwaway git repo built from this repo's modules (slow, spawns Gradle)."
                        systemProperty("tracer.repoRoot", rootDir.absolutePath)
                        shouldRunAfter(tasks.test)
                        outputs.upToDateWhen { false }
                    }
                }
            }
        }
    }
}
