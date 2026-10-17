package io.joeyang.tracer.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/** Entry point. Options parsing lives here; the range walk arrives in the next task. */
public final class Cli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 1;
    public static final int EXIT_TOOL_FAILURE = 2;
    public static final int EXIT_CHANGED = 3;

    // Amendment (code review): on Linux the JVM ignores $TMPDIR (java.io.tmpdir is always /tmp), so the
    // default scratch root must consult the environment variable explicitly to keep the documented
    // $TMPDIR/gradle-tracer behaviour true. Wired into main() in the next task.
    static final Path DEFAULT_SCRATCH = defaultScratchRoot(System::getenv, () -> System.getProperty("java.io.tmpdir"));

    static final String USAGE = """
            usage: trace <app> --from <rev> --to <rev> [--task <name>] [--no-bazel] [--offline] [--keep-worktrees]
                                                        [--tsv | --tsv-out <file>] [--timeout-minutes <n>]
                   trace <app> --at <rev> [--task <name>]

              <app>            appA | appB | appC   (maps to :app:<app>:<task> and //app/<app>:<app>)
              --task           Gradle task whose cache key is compared; default compileJava, alternative fingerprint
              --no-bazel       skip the bazel-diff column
              --offline        pass --offline to gradle; forbid repository downloads in bazel
              --keep-worktrees leave the checked-out worktrees and hash files in place for inspection
              --tsv            machine-readable output only (sha, subject, key, bazel, verdict, culprits)
              --tsv-out <file> print the table and also write the TSV to <file>
              --timeout-minutes per external command, default 10

            exit codes: 0 no change, 1 usage error, 2 a tool failed, 3 at least one CHANGED
            """;

    /** Parsed command line. */
    record Options(String app, String from, String to, String at, String task, boolean bazel, boolean offline,
                   boolean keepWorktrees, boolean tsv, Path tsvOut, Duration timeout) {

        static Options parse(String[] args) throws UsageException {
            if (args.length == 0) {
                throw new UsageException("missing <app>");
            }
            String app = args[0];
            if (app.startsWith("--")) {
                throw new UsageException("first argument must be the app name, got '" + app + "'");
            }
            String from = null, to = null, at = null, task = "compileJava";
            boolean bazel = true, offline = false, keep = false, tsv = false;
            Path tsvOut = null;
            Duration timeout = Duration.ofMinutes(10);
            for (int i = 1; i < args.length; i++) {
                String flag = args[i];
                switch (flag) {
                    case "--no-bazel" -> bazel = false;
                    case "--offline" -> offline = true;
                    case "--keep-worktrees" -> keep = true;
                    case "--tsv" -> tsv = true;
                    case "--from", "--to", "--at", "--task", "--tsv-out", "--timeout-minutes" -> {
                        if (i + 1 >= args.length) {
                            throw new UsageException(flag + " needs a value");
                        }
                        String value = args[++i];
                        switch (flag) {
                            case "--from" -> from = value;
                            case "--to" -> to = value;
                            case "--at" -> at = value;
                            case "--task" -> task = value;
                            case "--tsv-out" -> tsvOut = Path.of(value);
                            default -> timeout = parseMinutes(value);
                        }
                    }
                    default -> throw new UsageException("unknown option '" + flag + "'");
                }
            }
            if (at != null && (from != null || to != null)) {
                throw new UsageException("use either --at or --from/--to, not both");
            }
            if (at == null && (from == null || to == null)) {
                throw new UsageException("--from and --to are both required (or use --at)");
            }
            return new Options(app, from, to, at, task, bazel, offline, keep, tsv, tsvOut, timeout);
        }

        private static Duration parseMinutes(String value) throws UsageException {
            long minutes;
            try {
                minutes = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new UsageException("--timeout-minutes needs a whole number, got '" + value + "'");
            }
            // Amendment (code review): DefaultProcessRunner now rejects a zero or negative timeout with an
            // unchecked exception, so the CLI must never pass one through; reject it here as a usage error.
            if (minutes <= 0) {
                throw new UsageException("--timeout-minutes must be a positive integer, got " + minutes);
            }
            try {
                return Duration.ofMinutes(minutes);
            } catch (ArithmeticException e) {
                // Amendment (code review): Duration.ofMinutes overflows for absurdly large inputs; map that
                // to a usage error instead of letting ArithmeticException escape main().
                throw new UsageException("--timeout-minutes is too large, got '" + value + "'");
            }
        }

        String taskPath() {
            return ":app:" + app + ":" + task;
        }

        String bazelTarget() {
            return "//app/" + app + ":" + app;
        }
    }

    private Cli() {}

    // Amendment (code review): package-private and parameterized by lookup functions so the TMPDIR-vs-fallback
    // behaviour is directly testable without touching real environment variables or system properties.
    static Path defaultScratchRoot(Function<String, String> getenv, Supplier<String> javaIoTmpdir) {
        String tmpdir = getenv.apply("TMPDIR");
        String base = (tmpdir != null && !tmpdir.isBlank()) ? tmpdir : javaIoTmpdir.get();
        return Path.of(base, "gradle-tracer");
    }

    static int usage(PrintStream err, String problem) {
        err.println("trace: " + problem);
        err.print(USAGE);
        return EXIT_USAGE;
    }

    public static void main(String[] args) {
        try {
            Options.parse(args);
        } catch (UsageException e) {
            System.exit(usage(System.err, e.getMessage()));
            return;
        }
        System.err.println("trace: options accepted; the range walk arrives in the next task");
        System.exit(EXIT_TOOL_FAILURE);
    }
}
