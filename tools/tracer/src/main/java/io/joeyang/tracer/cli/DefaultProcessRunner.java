package io.joeyang.tracer.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs commands with {@link ProcessBuilder}; streams are drained concurrently so large output cannot deadlock. */
public final class DefaultProcessRunner implements ProcessRunner {

    @Override
    public Result run(Path cwd, Duration timeout, List<String> command) throws ProcessException {
        Objects.requireNonNull(cwd, "cwd must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }

        Instant deadline = safeDeadline(timeout);
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .start();
        } catch (IOException e) {
            throw ProcessException.cannotStart(command, e);
        }
        CompletableFuture<String> stdout = drain(process.getInputStream());
        CompletableFuture<String> stderr = drain(process.getErrorStream());
        boolean finished;
        try {
            finished = process.waitFor(safeMillis(timeout), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw ProcessException.interrupted(command);
        }
        if (!finished) {
            killTree(process);
            throw ProcessException.timeout(command, timeout);
        }

        String out;
        String err;
        try {
            Duration remaining = remainingUntil(deadline);
            CompletableFuture.allOf(stdout, stderr).get(safeMillis(remaining), TimeUnit.MILLISECONDS);
            out = stdout.join();
            err = stderr.join();
        } catch (TimeoutException e) {
            killTree(process);
            throw ProcessException.timeout(command, timeout);
        } catch (ExecutionException e) {
            // drain() never completes exceptionally today; kept to satisfy Future#get's checked signature.
            killTree(process);
            throw new IllegalStateException("unexpected drain failure", e);
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw ProcessException.interrupted(command);
        }
        return new Result(process.exitValue(), out, err);
    }

    /** The budget still left before {@code deadline}; never negative, so a {@code get} call never blocks past it. */
    private static Duration remainingUntil(Instant deadline) {
        try {
            Duration remaining = Duration.between(Instant.now(), deadline);
            return remaining.isNegative() ? Duration.ZERO : remaining;
        } catch (ArithmeticException overflow) {
            return Duration.ofMillis(Long.MAX_VALUE);
        }
    }

    /** {@code Instant.now().plus(timeout)}, clamped instead of overflowing for an absurdly large timeout. */
    private static Instant safeDeadline(Duration timeout) {
        try {
            return Instant.now().plus(timeout);
        } catch (ArithmeticException | DateTimeException overflow) {
            return Instant.MAX;
        }
    }

    /** {@code duration.toMillis()}, clamped instead of overflowing for an absurdly large duration. */
    private static long safeMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
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
