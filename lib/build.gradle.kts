plugins {
    id("tracer.java-library")
}

dependencies {
    api(project(":core"))                 // Service exposes core.Greeter, so consumers need core at compile time
    implementation(libs.commons.lang3)    // internal only: a bump here is a runtime-only input for appA
}
