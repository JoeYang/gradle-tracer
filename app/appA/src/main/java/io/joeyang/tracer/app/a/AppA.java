package io.joeyang.tracer.app.a;

import io.joeyang.tracer.core.Greeter;
import io.joeyang.tracer.lib.Service;

/** appA talks to lib, and to the core type lib hands back. */
public final class AppA {
    private AppA() {}

    public static String run(String[] args) {
        String name = args.length > 0 ? args[0] : "world";
        Service service = new Service();
        Greeter greeter = service.greeter();          // a core type, reachable only because lib uses api(project(":core"))
        return service.describe(name) + System.lineSeparator() + greeter.greet(name);
    }

    public static void main(String[] args) {
        System.out.println(run(args));
    }
}
