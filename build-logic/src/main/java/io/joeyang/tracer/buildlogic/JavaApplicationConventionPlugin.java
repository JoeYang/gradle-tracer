package io.joeyang.tracer.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** An application module: base conventions plus the application plugin (run, installDist). */
public final class JavaApplicationConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaBaseConventionPlugin.class);
        project.getPluginManager().apply("application");
    }
}
