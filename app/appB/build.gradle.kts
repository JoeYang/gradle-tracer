plugins {
    id("tracer.java-application")
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("io.joeyang.tracer.app.b.AppB")
}
