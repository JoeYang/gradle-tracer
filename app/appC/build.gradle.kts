plugins {
    id("tracer.java-application")
}

dependencies {
    implementation(project(":utils"))
    implementation(libs.gson)
}

application {
    mainClass.set("io.joeyang.tracer.app.c.AppC")
}
