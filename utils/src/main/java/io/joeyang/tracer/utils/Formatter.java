package io.joeyang.tracer.utils;

/** Small text helpers shared by every module. */
public final class Formatter {
    private Formatter() {}

    /** Formats cents as dollars: 1234 → "$12.34". The sign goes in front: -750 → "-$7.50". */
    public static String money(long cents) {
        long abs = Math.absExact(cents);
        String sign = cents < 0 ? "-" : "";
        return sign + "$" + (abs / 100) + "." + String.format("%02d", abs % 100);
    }

    /** Right-pads {@code text} with spaces to {@code width}; longer text is returned unchanged. */
    public static String pad(String text, int width) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (width < 0) {
            throw new IllegalArgumentException("width must be >= 0, got " + width);
        }
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());   // SCENARIO S1 edits this line (body only)
    }
}
