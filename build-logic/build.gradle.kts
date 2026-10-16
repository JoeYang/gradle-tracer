plugins {
    `java-gradle-plugin`
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
    // TestKit builds need the real network for JUnit; keep them visible.
    testLogging { events("passed", "failed", "skipped") }
}

gradlePlugin {
    plugins {
        create("javaBase") {
            id = "tracer.java-base"
            implementationClass = "io.joeyang.tracer.buildlogic.JavaBaseConventionPlugin"
        }
        create("javaLibrary") {
            id = "tracer.java-library"
            implementationClass = "io.joeyang.tracer.buildlogic.JavaLibraryConventionPlugin"
        }
        create("javaApplication") {
            id = "tracer.java-application"
            implementationClass = "io.joeyang.tracer.buildlogic.JavaApplicationConventionPlugin"
        }
    }
}
