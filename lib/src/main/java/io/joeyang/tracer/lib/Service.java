package io.joeyang.tracer.lib;

import io.joeyang.tracer.core.Greeter;
import org.apache.commons.lang3.StringUtils;

/**
 * Facade used by appA. It returns a {@link Greeter}, which is why lib declares core as an
 * {@code api} dependency: appA needs core on its compile classpath to call what it gets back.
 */
public final class Service {
    private final Greeter greeter = new Greeter(8);

    public Greeter greeter() {
        return greeter;
    }

    /** Trims and capitalises the name, then greets it. */
    public String describe(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        return greeter.greet(StringUtils.capitalize(name.trim()));
    }
}
