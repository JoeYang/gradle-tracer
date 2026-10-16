package io.joeyang.tracer.app.c;

import com.google.gson.Gson;
import io.joeyang.tracer.utils.Formatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** appC depends on utils and on gson directly; it never touches core or lib. */
public final class AppC {
    private AppC() {}

    public static String run(String[] args) {
        long cents = 1234L;
        if (args.length > 0) {
            try {
                cents = Long.parseLong(args[0]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("expected a whole number of cents, got '" + args[0] + "'", e);
            }
        }
        Map<String, String> doc = new LinkedHashMap<>();
        doc.put("amount", Formatter.money(cents));
        doc.put("label", Formatter.pad("total", 8));
        return new Gson().toJson(doc);
    }

    public static void main(String[] args) {
        System.out.println(run(args));
    }
}
