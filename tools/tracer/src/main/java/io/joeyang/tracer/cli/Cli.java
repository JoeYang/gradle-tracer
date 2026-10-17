package io.joeyang.tracer.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Entry point. Options parsing lives here; the range walk arrives in the next task. */
public final class Cli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 1;
    public static final int EXIT_TOOL_FAILURE = 2;
    public static final int EXIT_CHANGED = 3;

    // The JVM ignores $TMPDIR on Linux (java.io.tmpdir is always /tmp), so read it directly to keep the
    // documented $TMPDIR/gradle-tracer behaviour true. Wired into main() in the next task.
    static final Path DEFAULT_SCRATCH = defaultScratchRoot(System::getenv, () -> System.getProperty("java.io.tmpdir"));

    // <app> is used as a directory name under app/; restrict it so a path-like or option-like value
    // never reaches the filesystem lookup.
    private static final Pattern APP_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    static final String USAGE = """
            usage: trace <app> --from <rev> --to <rev> [--task <name>] [--no-bazel] [--offline] [--keep-worktrees]
                                                        [--tsv] [--tsv-out <file>] [--timeout-minutes <n>]
                   trace <app> --at <rev> [--task <name>] [--offline] [--keep-worktrees] [--timeout-minutes <n>]

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
            if (!APP_NAME.matcher(app).matches()) {
                throw new UsageException("'" + app + "' is not a valid app name: expected letters, digits, "
                        + "'-' or '_', starting with a letter or digit");
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
                        // A blank value, or one that looks like another flag, is almost always a missing
                        // value swallowing the next option, e.g. "--from --to y" would otherwise parse
                        // "--to" as --from's value and then fail on "y" as unknown. Excluded for
                        // --timeout-minutes: its own numeric validation below gives a strictly more
                        // specific error for a negative or zero value than this generic message would.
                        if (!flag.equals("--timeout-minutes") && (value.isBlank() || value.startsWith("-"))) {
                            throw new UsageException(flag + " needs a value, got '" + value + "'");
                        }
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
            // --at prints the key and its composition with no comparison (spec §5), so the
            // comparison-only flags are meaningless alongside it.
            if (at != null && tsv) {
                throw new UsageException("--at cannot be combined with --tsv");
            }
            if (at != null && tsvOut != null) {
                throw new UsageException("--at cannot be combined with --tsv-out");
            }
            if (at != null && !bazel) {
                throw new UsageException("--at cannot be combined with --no-bazel");
            }
            return new Options(app, from, to, at, task, bazel, offline, keep, tsv, tsvOut, timeout);
        }

        // ASCII digits only (with an optional leading '-'), checked before parsing, so a non-ASCII digit
        // (e.g. the Arabic-indic '5') is reported as "needs a whole number", and a value that IS
        // all-ASCII-digits but too long to fit in a long is distinguished as "too large" instead.
        private static final Pattern TIMEOUT_VALUE = Pattern.compile("-?[0-9]+");

        private static Duration parseMinutes(String value) throws UsageException {
            if (!TIMEOUT_VALUE.matcher(value).matches()) {
                throw new UsageException("--timeout-minutes needs a whole number, got '" + value + "'");
            }
            long minutes;
            try {
                minutes = Long.parseLong(value);
            } catch (NumberFormatException e) {
                // A value with too many digits to fit in a long overflows here, including a negative one
                // (e.g. a minus sign followed by twenty 9s): "too large" fits either sign equally well.
                throw new UsageException("--timeout-minutes is too large, got '" + value + "'");
            }
            // DefaultProcessRunner rejects a zero or negative timeout with an unchecked exception, so
            // validate it here as a usage error instead of ever passing one through.
            if (minutes <= 0) {
                throw new UsageException("--timeout-minutes must be a positive integer, got " + minutes);
            }
            try {
                return Duration.ofMinutes(minutes);
            } catch (ArithmeticException e) {
                // Duration.ofMinutes overflows for absurdly large inputs; map that to a usage error instead
                // of letting ArithmeticException escape main().
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

    // Package-private and parameterized by lookup functions so the TMPDIR-vs-fallback behaviour is
    // directly testable without touching real environment variables or system properties.
    static Path defaultScratchRoot(Function<String, String> getenv, Supplier<String> javaIoTmpdir) {
        String tmpdir = getenv.apply("TMPDIR");
        String base = (tmpdir != null && !tmpdir.isBlank()) ? tmpdir : javaIoTmpdir.get();
        // ./trace changes directory before main() runs, so a relative TMPDIR must be resolved against
        // the CWD at call time instead of staying relative.
        return Path.of(base, "gradle-tracer").toAbsolutePath().normalize();
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
        System.err.println("trace: options accepted; the range walk arrives in a later commit");
        System.exit(EXIT_TOOL_FAILURE);
    }
}
