plugins {
    id("tracer.java-application")
}

application {
    mainClass.set("io.joeyang.tracer.cli.Cli")
    applicationName = "trace"
}
