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
import java.util.concurrent.atomic.AtomicInteger;

/** Runs commands with {@link ProcessBuilder}; streams are drained concurrently so large output cannot deadlock. */
public final class DefaultProcessRunner implements ProcessRunner {

    private static final AtomicInteger STDOUT_COUNT = new AtomicInteger();
    private static final AtomicInteger STDERR_COUNT = new AtomicInteger();

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
        CompletableFuture<String> stdout = drain(process.getInputStream(), "tracer-stdout-" + STDOUT_COUNT.incrementAndGet());
        CompletableFuture<String> stderr = drain(process.getErrorStream(), "tracer-stderr-" + STDERR_COUNT.incrementAndGet());
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
            killTree(process);
            throw asProcessException(command, e.getCause());
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

    /**
     * Turns a drain failure into a {@link ProcessException}. An {@link IOException} became {@code ioFailed}; a
     * {@link RuntimeException} or {@link Error} is rethrown as-is rather than being reported as an I/O failure, since
     * it is not one (e.g. it could be an {@link OutOfMemoryError} while buffering a huge stream).
     */
    private static ProcessException asProcessException(List<String> command, Throwable drainFailure) {
        if (drainFailure instanceof IOException io) {
            return ProcessException.ioFailed(command, io);
        }
        if (drainFailure instanceof RuntimeException re) {
            throw re;
        }
        if (drainFailure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("unexpected drain failure", drainFailure);
    }

    private static CompletableFuture<String> drain(InputStream in, String threadName) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try (in) {
                future.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Throwable t) {
                // Catch Throwable, not just IOException: an uncaught Error (e.g. OutOfMemoryError buffering a huge
                // stream) would otherwise leave this future incomplete forever, so run() would block out its whole
                // timeout budget waiting on it and then misreport a plain timeout instead of the real failure.
                future.completeExceptionally(t);
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
        return future;
    }
}
