package io.joeyang.tracer.app.b;

import io.joeyang.tracer.core.Greeter;
import java.io.PrintStream;

/** appB depends on core directly and knows nothing about lib or utils. */
public final class AppB {
    private AppB() {}

    public static String run(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("expected at most one argument, got " + args.length);
        }
        String name = args.length > 0 ? args[0] : "world";
        return new Greeter(10).greet(name);
    }

    static int main0(String[] args, PrintStream out, PrintStream err) {
        try {
            out.println(run(args));
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("appB: " + e.getMessage());
            return 2;
        }
    }

    public static void main(String[] args) {
        int code = main0(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }
}
