plugins {
    id("tracer.java-application")
}

dependencies {
    implementation(project(":lib"))
}

application {
    mainClass.set("io.joeyang.tracer.app.a.AppA")
}
