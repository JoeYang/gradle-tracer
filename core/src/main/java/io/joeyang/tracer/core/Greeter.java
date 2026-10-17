package io.joeyang.tracer.core;

import io.joeyang.tracer.utils.Formatter;

/** Builds greetings. Used directly by appB and, through lib, by appA. */
public final class Greeter {
    private final int column;

    public Greeter(int column) {
        if (column < 0) {
            throw new IllegalArgumentException("column must be >= 0, got " + column);
        }
        this.column = column;
    }

    // Greets by name.
    public String greet(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return Formatter.pad("Hello,", column) + name + "! this is a test.";
    }
}
