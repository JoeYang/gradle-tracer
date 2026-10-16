package io.joeyang.tracer.buildlogic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

/**
 * A cacheable task whose only input is the main runtime classpath. Its build-cache key is therefore
 * a content hash of everything the application ships: own classes, project jars, third-party jars.
 * {@code @Classpath} normalisation ignores paths and timestamps (and entry order inside a jar) but not contents,
 * so a library bump changes the key and a comment-only edit (identical bytecode) does not. The order of classpath
 * entries themselves is significant, and deterministic for a given build. The marker file exists only so the
 * task has an output and can be cached; the key is the product.
 */
@CacheableTask
public abstract class FingerprintTask extends DefaultTask {

    @Classpath
    public abstract ConfigurableFileCollection getRuntimeClasspath();

    @OutputFile
    public abstract RegularFileProperty getMarker();

    @TaskAction
    public void write() throws IOException {
        List<String> names = getRuntimeClasspath().getFiles().stream()
                .map(File::getName)
                .sorted()
                .toList();
        Path out = getMarker().get().getAsFile().toPath();
        Files.write(out, names);
    }
}
