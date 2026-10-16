package io.joeyang.tracer.cli;

/** A tool (git, gradle, bazel) failed in a way that prevents a verdict for one commit. Exit code 2 territory. */
public final class ProviderException extends Exception {
    public ProviderException(String message) {
        super(message);
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
