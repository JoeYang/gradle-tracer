package io.joeyang.tracer.cli;

/** The Gradle verdict for one commit. */
public enum Status {
    BASE("base"), CHANGED("CHANGED"), CLEAN("clean"), BUILD_FAILED("BUILD-FAILED");

    private final String label;

    Status(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
