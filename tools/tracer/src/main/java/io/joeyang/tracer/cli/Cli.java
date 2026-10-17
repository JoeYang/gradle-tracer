package io.joeyang.tracer.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Entry point: parses options and walks the range through worktrees, printing the verdicts. {@code --at} is a placeholder until a later commit. */
public final class Cli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 1;
    public static final int EXIT_TOOL_FAILURE = 2;
    public static final int EXIT_CHANGED = 3;
    // The JVM ignores $TMPDIR on Linux (java.io.tmpdir is always /tmp), so the default scratch root
    // reads the environment variable directly.
    /** One fixed scratch root, so the next run finds and removes whatever a killed run left behind. */
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

    private final ProcessRunner runner;
    private final Path repo;
    private final Path scratch;
    private final KeyProvider keys;
    private final ImpactProvider impacts; // null when --no-bazel

    Cli(ProcessRunner runner, Path repo, Path scratch, KeyProvider keys, ImpactProvider impacts) {
        this.runner = runner;
        this.repo = repo;
        this.scratch = scratch;
        this.keys = keys;
        this.impacts = impacts;
    }

    // Package-private and parameterized by lookup functions so the TMPDIR-vs-fallback behaviour is
    // directly testable without touching real environment variables or system properties.
    static Path defaultScratchRoot(Function<String, String> getenv, Supplier<String> javaIoTmpdir) {
        String tmpdir = getenv.apply("TMPDIR");
        String base = (tmpdir != null && !tmpdir.isBlank()) ? tmpdir : javaIoTmpdir.get();
        // Child processes such as <worktree>/gradlew run inside the worktree, so a relative root would
        // resolve differently there; resolve it once against the JVM's working directory (the repo root
        // after ./trace's cd).
        return Path.of(base, "gradle-tracer").toAbsolutePath().normalize();
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (UsageException e) {
            System.exit(usage(System.err, e.getMessage()));
            return;
        }
        ProcessRunner runner = new DefaultProcessRunner();
        Path repo = Path.of("").toAbsolutePath();
        KeyProvider keys = new GradleKeyProvider(runner, options.offline(), options.timeout());
        ImpactProvider impacts = options.bazel() ? new BazelDiffProvider(runner, repo, options.offline(), options.timeout()) : null;
        System.exit(new Cli(runner, repo, DEFAULT_SCRATCH, keys, impacts).run(options, System.out, System.err));
    }

    static int usage(PrintStream err, String problem) {
        err.println("trace: " + problem);
        err.print(USAGE);
        return EXIT_USAGE;
    }

    int run(Options o, PrintStream out, PrintStream err) {
        try {
            if (!Files.isDirectory(repo.resolve("app").resolve(o.app()))) {
                throw new UsageException("unknown app '" + o.app() + "': expected a directory app/" + o.app());
            }
            if (o.at() != null) {
                err.println("trace: --at arrives in a later commit");
                return EXIT_TOOL_FAILURE;
            }
            checkTsvOutWritable(o);
            return range(o, out, err);
        } catch (UsageException e) {
            return usage(err, e.getMessage());
        } catch (ProviderException e) {
            err.println("trace: " + e.getMessage());
            return EXIT_TOOL_FAILURE;
        }
    }

    /**
     * Checked before any worktree is created or any git command beyond option parsing runs, so a bad
     * {@code --tsv-out} path fails fast instead of after a whole range walk has already run.
     */
    private void checkTsvOutWritable(Options o) throws UsageException {
        Path tsvOut = o.tsvOut();
        if (tsvOut == null) {
            return;
        }
        if (Files.isDirectory(tsvOut)) {
            throw new UsageException("--tsv-out '" + tsvOut + "' is a directory");
        }
        Path parent = tsvOut.toAbsolutePath().getParent();
        if (parent == null) {
            parent = Path.of("").toAbsolutePath();
        }
        if (!Files.isDirectory(parent)) {
            throw new UsageException("--tsv-out '" + tsvOut + "': parent directory " + parent + " does not exist");
        }
        if (!Files.isWritable(parent)) {
            throw new UsageException("--tsv-out '" + tsvOut + "': parent directory " + parent + " is not writable");
        }
    }

    private int range(Options o, PrintStream out, PrintStream err) throws UsageException, ProviderException {
        List<Commit> commits = new RevisionRange(runner, repo).resolve(o.from(), o.to());
        int swept = deleteScratchFiles();
        if (swept > 0) {
            err.println("trace: removed " + swept + " stale scratch file(s) under " + scratch);
        }
        List<Verdict> rows = new ArrayList<>();
        try (WorktreePool pool = new WorktreePool(runner, repo, scratch, o.keepWorktrees())) {
            Optional<TaskKey> previousGood = Optional.empty();
            Path previousHashes = null;
            boolean bazelAlive = impacts != null;
            for (Commit commit : commits) {
                Path worktree = pool.checkout(commit);
                Optional<TaskKey> key = Optional.empty();
                Optional<String> error = Optional.empty();
                try {
                    key = Optional.of(keys.keyFor(worktree, o.taskPath()));
                } catch (ProviderException e) {
                    error = Optional.of(e.getMessage());
                }
                BazelStatus bazel = BazelStatus.NONE;
                if (bazelAlive) {
                    try {
                        Path hashes = impacts.hashes(worktree, scratch.resolve("hashes-" + commit.shortSha() + ".json"));
                        if (previousHashes != null) {
                            Set<String> impacted = impacts.impactedTargets(previousHashes, hashes);
                            bazel = impacted.contains(o.bazelTarget()) ? BazelStatus.IMPACTED : BazelStatus.CLEAN;
                        }
                        previousHashes = hashes;
                    } catch (ProviderException e) {
                        err.println("trace: bazel-diff unavailable: " + e.getMessage());
                        bazelAlive = false;
                        bazel = BazelStatus.NOT_AVAILABLE;
                    }
                } else if (impacts != null) {
                    bazel = BazelStatus.NOT_AVAILABLE;
                }
                rows.add(KeyComparator.verdict(commit, previousGood, key, error, bazel));
                if (key.isPresent()) {
                    previousGood = key;
                }
                if (!o.keepWorktrees()) {
                    pool.release(worktree);
                }
            }
        }
        if (!o.keepWorktrees()) {
            deleteScratchFiles();
        }
        String tsv = TableRenderer.renderTsv(rows);
        if (o.tsv()) {
            out.print(tsv);
        } else {
            out.print(TableRenderer.render(o.taskPath(), impacts == null ? null : o.bazelTarget(), rows));
        }
        if (o.tsvOut() != null) {
            try {
                Files.writeString(o.tsvOut(), tsv);
            } catch (IOException e) {
                throw new ProviderException("cannot write " + o.tsvOut() + ": " + e.getMessage(), e);
            }
        }
        if (rows.stream().anyMatch(v -> v.status() == Status.BUILD_FAILED)) {
            return EXIT_TOOL_FAILURE;
        }
        return rows.stream().anyMatch(v -> v.status() == Status.CHANGED) ? EXIT_CHANGED : EXIT_OK;
    }

    /** Deletes bazel-diff hash and impacted-target files under the scratch root; returns how many went. */
    private int deleteScratchFiles() {
        if (!Files.isDirectory(scratch)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> entries = Files.list(scratch)) {
            for (Path p : entries.toList()) {
                String name = p.getFileName().toString();
                if (Files.isRegularFile(p) && (name.startsWith("hashes-") || name.endsWith(".impacted.txt"))) {
                    Files.deleteIfExists(p);
                    count++;
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return count;
    }
}
