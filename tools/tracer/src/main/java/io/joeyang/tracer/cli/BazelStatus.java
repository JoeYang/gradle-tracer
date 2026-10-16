package io.joeyang.tracer.cli;

/** The informational bazel-diff column. */
public enum BazelStatus {
    IMPACTED("impacted"), CLEAN("clean"), NOT_AVAILABLE("n/a"), NONE("-");

    private final String label;

    BazelStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
