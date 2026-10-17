package io.joeyang.tracer.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs commands with {@link ProcessBuilder}; streams are drained concurrently so large output cannot deadlock. */
public final class DefaultProcessRunner implements ProcessRunner {

    @Override
    public Result run(Path cwd, Duration timeout, List<String> command) throws ProcessException {
        Process process;
        try {
            process = new ProcessBuilder(command).directory(cwd.toFile()).start();
        } catch (IOException e) {
            throw ProcessException.cannotStart(command, e);
        }
        CompletableFuture<String> stdout = drain(process.getInputStream());
        CompletableFuture<String> stderr = drain(process.getErrorStream());
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw ProcessException.interrupted(command);
        }
        if (!finished) {
            killTree(process);
            throw ProcessException.timeout(command, timeout);
        }
        return new Result(process.exitValue(), stdout.join(), stderr.join());
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static CompletableFuture<String> drain(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}
