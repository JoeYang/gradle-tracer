package io.joeyang.tracer.app.a;

import io.joeyang.tracer.core.Greeter;
import io.joeyang.tracer.lib.Service;
import java.io.PrintStream;

/** appA talks to lib, and to the core type lib hands back. */
public final class AppA {
    private AppA() {}

    public static String run(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("expected at most one argument, got " + args.length);
        }
        String name = args.length > 0 ? args[0].strip() : "world";
        Service service = new Service();
        Greeter greeter = service.greeter();          // a core type, reachable only because lib uses api(project(":core"))
        return service.describe(name) + System.lineSeparator() + greeter.greet(name);
    }

    static int main0(String[] args, PrintStream out, PrintStream err) {
        try {
            out.println(run(args));
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("appA: " + e.getMessage());
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
