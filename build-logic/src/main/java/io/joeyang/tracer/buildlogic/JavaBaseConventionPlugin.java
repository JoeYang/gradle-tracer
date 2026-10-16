package io.joeyang.tracer.buildlogic;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.MinimalExternalModuleDependency;
import org.gradle.api.artifacts.VersionCatalog;
import org.gradle.api.artifacts.VersionCatalogsExtension;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;

/**
 * Conventions shared by every module: Java 21 bytecode, JUnit platform tests,
 * reproducible jars and dependency locking. Library and application plugins build on it.
 */
public final class JavaBaseConventionPlugin implements Plugin<Project> {
    static final int RELEASE = 21;
    public static final String FINGERPRINT_TASK = "fingerprint";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().getRelease().set(RELEASE);
            task.getOptions().setEncoding("UTF-8");
        });
        project.getTasks().withType(Jar.class).configureEach(jar -> {
            jar.setPreserveFileTimestamps(false);
            jar.setReproducibleFileOrder(true);
        });
        project.getTasks().withType(Test.class).configureEach(Test::useJUnitPlatform);
        addJUnit(project);
        project.getDependencyLocking().lockAllConfigurations();
        registerFingerprint(project);
    }

    private static void registerFingerprint(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        project.getTasks().register(FINGERPRINT_TASK, FingerprintTask.class, task -> {
            task.setGroup("verification");
            task.setDescription("Cacheable no-op whose build-cache key fingerprints the main runtime classpath");
            task.getRuntimeClasspath().from(
                    sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath());
            task.getMarker().set(project.getLayout().getBuildDirectory().file("fingerprint/runtime-classpath.txt"));
        });
    }

    private static void addJUnit(Project project) {
        VersionCatalog libs = catalog(project);
        DependencyHandler deps = project.getDependencies();
        deps.addProvider("testImplementation", deps.platform(library(libs, "junit-bom")));
        deps.addProvider("testImplementation", library(libs, "junit-jupiter"));
        deps.addProvider("testRuntimeOnly", library(libs, "junit-platform-launcher"));
    }

    static VersionCatalog catalog(Project project) {
        VersionCatalogsExtension catalogs = project.getExtensions().findByType(VersionCatalogsExtension.class);
        if (catalogs == null) {
            throw new GradleException("tracer.java-base needs the 'libs' version catalog (gradle/libs.versions.toml)");
        }
        return catalogs.find("libs").orElseThrow(
                () -> new GradleException("version catalog 'libs' not found; expected gradle/libs.versions.toml"));
    }

    private static Provider<MinimalExternalModuleDependency> library(VersionCatalog libs, String alias) {
        return libs.findLibrary(alias).orElseThrow(
                () -> new GradleException("alias '" + alias + "' is missing from gradle/libs.versions.toml"));
    }
}
