package io.joeyang.tracer.app.c;

import com.google.gson.Gson;
import io.joeyang.tracer.utils.Formatter;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** appC depends on utils and on gson directly; it never touches core or lib. */
public final class AppC {
    private AppC() {}

    public static String run(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("expected at most one argument, got " + args.length);
        }
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

    static int main0(String[] args, PrintStream out, PrintStream err) {
        try {
            out.println(run(args));
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("appC: " + e.getMessage());
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
