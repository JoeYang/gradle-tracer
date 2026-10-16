package io.joeyang.tracer.cli;

/** The user asked for something impossible: unknown revision, unknown app, empty range. Exit code 1. */
public final class UsageException extends Exception {
    public UsageException(String message) {
        super(message);
    }
}
