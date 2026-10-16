package io.joeyang.tracer.buildlogic;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** A library module: base conventions plus the java-library plugin (api vs implementation). */
public final class JavaLibraryConventionPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaBaseConventionPlugin.class);
        project.getPluginManager().apply("java-library");
    }
}
