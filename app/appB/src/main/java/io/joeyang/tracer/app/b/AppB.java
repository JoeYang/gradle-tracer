package io.joeyang.tracer.app.b;

import io.joeyang.tracer.core.Greeter;

/** appB depends on core directly and knows nothing about lib or utils. */
public final class AppB {
    private AppB() {}

    public static String run(String[] args) {
        String name = args.length > 0 ? args[0] : "world";
        return new Greeter(10).greet(name);
    }

    public static void main(String[] args) {
        System.out.println(run(args));
    }
}
