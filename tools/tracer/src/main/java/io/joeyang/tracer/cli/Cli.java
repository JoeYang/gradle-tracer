package io.joeyang.tracer.cli;

import com.sun.security.auth.module.UnixSystem;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Entry point: parses options, walks the range through worktrees, and prints the verdicts (or, for {@code --at}, one commit's key). */
public final class Cli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 1;
    public static final int EXIT_TOOL_FAILURE = 2;
    public static final int EXIT_CHANGED = 3;
    /**
     * One fixed scratch root, so the next run finds and removes whatever a killed run left behind. The JVM
     * ignores {@code $TMPDIR} on Linux ({@code java.io.tmpdir} is always {@code /tmp}), so this reads the
     * environment variable directly instead.
     */
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
    private final LongSupplier currentUid;

    Cli(ProcessRunner runner, Path repo, Path scratch, KeyProvider keys, ImpactProvider impacts) {
        this(runner, repo, scratch, keys, impacts, () -> new UnixSystem().getUid());
    }

    // Package-private seam: lets tests simulate an ownership mismatch on the scratch root (checkScratchRootIsPrivate)
    // without needing root to chown a file to someone else.
    Cli(ProcessRunner runner, Path repo, Path scratch, KeyProvider keys, ImpactProvider impacts, LongSupplier currentUid) {
        this.runner = runner;
        this.repo = repo;
        this.scratch = scratch;
        this.keys = keys;
        this.impacts = impacts;
        this.currentUid = currentUid;
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
            // Revision resolution is read-only git, so it needs no lock; resolving before the lock means an
            // unknown --from/--to/--at (spec §9) exits 1 with no scratch root or .lock file left behind.
            if (o.at() != null) {
                Commit commit = new RevisionRange(runner, repo).resolveOne(o.at());
                return withScratchLock(err, () -> at(o, out, commit));
            }
            checkTsvOutWritable(o);
            List<Commit> commits = new RevisionRange(runner, repo).resolve(o.from(), o.to());
            return withScratchLock(err, () -> range(o, out, err, commits));
        } catch (UsageException e) {
            return usage(err, e.getMessage());
        } catch (ProviderException e) {
            err.println("trace: " + e.getMessage());
            return EXIT_TOOL_FAILURE;
        } catch (RuntimeException e) {
            // A provider or a filesystem call can throw an unchecked exception we didn't anticipate (e.g.
            // UncheckedIOException from Files.walk on an unreadable subdirectory); report it as a tool failure
            // instead of letting it escape as a raw stack trace with the JVM's own non-zero exit code.
            err.println("trace: internal error: " + e.getClass().getName() + ": " + e.getMessage());
            return EXIT_TOOL_FAILURE;
        }
    }

    /** The work done once the scratch root is locked: either {@link #at} or {@link #range}, with its revisions already resolved. */
    @FunctionalInterface
    private interface LockedWork {
        int run() throws UsageException, ProviderException;
    }

    /**
     * Locks the scratch root for the whole run, before any sweep or worktree work starts: two trace runs sharing
     * a root would otherwise sweep each other's live {@code wt-*} worktrees and hash files out from under one
     * another. {@code tryLock()} returns null for a lock held by another process, and throws
     * {@link OverlappingFileLockException} for one held elsewhere in this same JVM; both mean the root is busy.
     */
    private int withScratchLock(PrintStream err, LockedWork work) throws UsageException, ProviderException {
        if (!ensurePrivateScratchRoot(err)) {
            return EXIT_USAGE;
        }
        Path lockPath = scratch.resolve(".lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                // Lock contention isn't a command-line mistake, so it gets its own message, not the full usage
                // text; the exit code still reads as a usage error (something about this invocation, not a tool
                // failure, prevented it from running).
                err.println("trace: another trace is using " + scratch + "; wait for it or set TMPDIR to another directory");
                return EXIT_USAGE;
            }
            // try-with-resources (not a manual release() in a finally) so that if work.run() throws and release()
            // also fails, release()'s IOException is suppressed onto work's exception rather than replacing it.
            try (FileLock heldLock = lock) {
                return work.run();
            }
        } catch (IOException e) {
            throw new ProviderException("cannot lock scratch root " + lockPath + ": " + ioReason(e, lockPath), e);
        }
    }

    private static final Set<PosixFilePermission> PRIVATE_SCRATCH_PERMISSIONS = PosixFilePermissions.fromString("rwx------");

    /**
     * $TMPDIR/gradle-tracer is a predictable path, often under a shared {@code /tmp}: another local user could
     * pre-create it and tamper with the worktrees whose {@code gradlew} this tool executes, or hold {@code .lock}
     * to block every run. A fresh root is created {@code rwx------}; an existing one must already be owned by
     * the current user with no group/other write bit, or the run refuses to touch it.
     *
     * @return false (after printing the reason) when the root exists but isn't private; true otherwise
     */
    private boolean ensurePrivateScratchRoot(PrintStream err) throws ProviderException {
        // isSymbolicLink alongside isDirectory: a symlinked scratch root must go through the privacy check too
        // (which reads the link's own attributes, not its target's — see checkScratchRootIsPrivate), not get
        // treated as "doesn't exist yet" and recreated or (for a symlink to a file) fail as a generic IO error.
        if (Files.isDirectory(scratch, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(scratch)) {
            return checkScratchRootIsPrivate(err);
        }
        try {
            Path parent = scratch.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.createDirectory(scratch, PosixFilePermissions.asFileAttribute(PRIVATE_SCRATCH_PERMISSIONS));
        } catch (IOException e) {
            throw new ProviderException("cannot create scratch root " + scratch + ": " + ioReason(e, scratch), e);
        }
        return true;
    }

    private boolean checkScratchRootIsPrivate(PrintStream err) throws ProviderException {
        try {
            // NOFOLLOW_LINKS throughout: a symlinked scratch root is checked on the link itself, never on
            // whatever it points to. On Linux a symlink's own permissions report as world-read/write/execute
            // regardless of who made it, so this alone is enough to reject any symlink as "not private" without
            // a separate symlink check — it never needs to look at (or trust) the real target.
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(scratch, LinkOption.NOFOLLOW_LINKS);
            boolean groupOrOtherWrite = perms.contains(PosixFilePermission.GROUP_WRITE)
                    || perms.contains(PosixFilePermission.OTHERS_WRITE);
            // Compared by uid, not by Files.getOwner().getName(): for a uid with no passwd entry (e.g.
            // "docker run -u $(id -u)"), the JVM reports user.name as "?" while getOwner() still resolves to
            // the numeric uid, so a name comparison would wrongly call every later run "not private to you".
            long owner = ((Number) Files.getAttribute(scratch, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
            boolean notOwnedByMe = owner != currentUid.getAsLong();
            if (groupOrOtherWrite || notOwnedByMe) {
                // Not a command-line mistake, so it gets its own message, not the full usage text, same as lock
                // contention below; the exit code still reads as a usage error.
                err.println("trace: scratch root " + scratch
                        + " is not private to you; remove it or set TMPDIR to a private directory");
                return false;
            }
            return true;
        } catch (IOException e) {
            throw new ProviderException("cannot check scratch root " + scratch + ": " + ioReason(e, scratch), e);
        }
    }

    /**
     * {@code e.getMessage()}, falling back to the exception's simple class name when the message is missing or is
     * just the path again (some {@link IOException} subtypes report nothing more useful than that), so the
     * reported reason is never empty or redundant with the path already named alongside it. Mirrors
     * {@code BazelDiffProvider}'s identical fallback for the same class of message.
     */
    private static String ioReason(IOException e, Path path) {
        // FileSystemException's own getMessage() typically repeats the path we're already naming ("<path>: Is a
        // directory", or for some failures just "<path>" with no reason at all), so prefer its structured
        // getReason() first; a plain IOException's getMessage() doesn't have this problem.
        if (e instanceof FileSystemException fse) {
            return fse.getReason() != null ? fse.getReason() : e.getClass().getSimpleName();
        }
        String message = e.getMessage();
        return (message == null || message.equals(path.toString())) ? e.getClass().getSimpleName() : message;
    }

    /**
     * Checked before any worktree is created or any git command beyond option parsing runs, so a bad
     * {@code --tsv-out} path fails fast instead of after a whole range walk has already run.
     */
    private void checkTsvOutWritable(Options o) throws UsageException, ProviderException {
        Path tsvOut = o.tsvOut();
        if (tsvOut == null) {
            return;
        }
        if (Files.isDirectory(tsvOut)) {
            throw new UsageException("--tsv-out '" + tsvOut + "' is a directory");
        }
        if (Files.isSymbolicLink(tsvOut) && !Files.exists(tsvOut)) {
            throw new UsageException("--tsv-out '" + tsvOut + "' is a symlink to a missing file");
        }
        // The parent checked below is the REAL target's parent, not the symlink's own: a symlinked --tsv-out is
        // written through to whatever it points at (see writeTsvAtomically), so what matters is whether that
        // real location is writable, not the directory the symlink itself happens to live in.
        Path checkTarget = tsvOut;
        if (Files.exists(tsvOut)) {
            if (!Files.isWritable(tsvOut)) {
                throw new UsageException("--tsv-out '" + tsvOut + "' exists and is not writable");
            }
            try {
                checkTarget = tsvOut.toRealPath();
            } catch (IOException e) {
                throw new ProviderException("cannot resolve --tsv-out '" + tsvOut + "': " + ioReason(e, tsvOut), e);
            }
        }
        Path parent = checkTarget.toAbsolutePath().getParent();
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

    private int range(Options o, PrintStream out, PrintStream err, List<Commit> commits) throws ProviderException {
        int swept = deleteScratchFiles();
        if (swept > 0) {
            err.println("trace: removed " + swept + " stale scratch file(s) under " + scratch);
        }
        List<Verdict> rows = new ArrayList<>();
        try {
            try (WorktreePool pool = new WorktreePool(runner, repo, scratch, o.keepWorktrees())) {
                Optional<TaskKey> previousGood = Optional.empty();
                Path previousHashes = null;
                boolean bazelAlive = impacts != null;
                for (Commit commit : commits) {
                    // Checked here too, not only after a failed key lookup: an interrupted bazel-diff call
                    // degrades silently (as an ordinary bazel-diff failure, below), so the flag can still be set
                    // going into the next commit even though nothing in the previous iteration reported it as
                    // "interrupted". Catching it here, before this commit's checkout or any of its provider
                    // calls, is what stops it reaching the next checkout and failing there with a confusing
                    // unrelated error instead.
                    if (Thread.currentThread().isInterrupted()) {
                        throw new ProviderException("interrupted");
                    }
                    Path worktree = pool.checkout(commit);
                    Optional<TaskKey> key = Optional.empty();
                    Optional<String> error = Optional.empty();
                    try {
                        key = Optional.of(keys.keyFor(worktree, o.taskPath()));
                    } catch (ProviderException e) {
                        // An interrupted process call fails this one lookup but leaves the interrupt flag set, so
                        // every later process call would fail the same way; limping on would just produce a wall
                        // of BUILD-FAILED rows instead of the one honest signal that the run was asked to stop.
                        if (Thread.currentThread().isInterrupted()) {
                            throw new ProviderException("interrupted", e);
                        }
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
        } finally {
            // Runs whether the walk finished or a checkout/pool failure aborted it partway through: a killed
            // walk must leave no more behind than a killed run would (docs/tracer.md's "Range walk" promise).
            if (!o.keepWorktrees()) {
                deleteScratchFiles();
            }
        }
        // An interrupt during the *last* commit's bazel-diff call degrades silently like any other (the loop's
        // own top-of-iteration check never gets a next iteration to catch it in), so it would otherwise survive
        // only as that warning and let the walk finish normally. Catch it here too, before rendering anything.
        if (Thread.currentThread().isInterrupted()) {
            throw new ProviderException("interrupted");
        }
        String tsv = TableRenderer.renderTsv(rows);
        if (o.tsv()) {
            out.print(tsv);
        } else {
            out.print(TableRenderer.render(o.taskPath(), impacts == null ? null : o.bazelTarget(), rows));
        }
        if (o.tsvOut() != null) {
            writeTsvAtomically(o.tsvOut(), tsv);
        }
        if (rows.stream().anyMatch(v -> v.status() == Status.BUILD_FAILED)) {
            return EXIT_TOOL_FAILURE;
        }
        return rows.stream().anyMatch(v -> v.status() == Status.CHANGED) ? EXIT_CHANGED : EXIT_OK;
    }

    /**
     * Writes {@code content} to {@code target} via a temp file plus an atomic move, so a write that fails
     * partway through (disk full, permissions revoked mid-walk, ...) never leaves {@code target} truncated or
     * corrupted — the previous file, if any, is untouched.
     */
    private void writeTsvAtomically(Path target, String content) throws ProviderException {
        // toRealPath, not target itself: a symlinked --tsv-out must have its real target replaced, not the
        // symlink's own directory entry (which is what an atomic move onto the symlink's path would replace).
        Path realTarget = target;
        Set<PosixFilePermission> previousPermissions = null;
        try {
            if (Files.exists(target)) {
                realTarget = target.toRealPath();
                previousPermissions = Files.getPosixFilePermissions(realTarget);
            }
        } catch (IOException e) {
            throw new ProviderException("cannot write " + target + ": " + ioReason(e, target), e);
        }
        Path parent = realTarget.toAbsolutePath().getParent();
        if (parent == null) {
            parent = Path.of("").toAbsolutePath();
        }
        Path tmp = null;
        try {
            if (previousPermissions != null) {
                // An existing target's mode must survive the replacement: createTempFile's own (always 0600)
                // would otherwise silently become the new mode once the move discards the old inode.
                tmp = Files.createTempFile(parent, "trace-", ".tsv.tmp");
                Files.writeString(tmp, content);
                Files.setPosixFilePermissions(tmp, previousPermissions);
            } else {
                // A brand-new file has no previous mode to preserve, so it's written plainly (mode follows the
                // umask like any other file this tool creates) rather than through createTempFile, which is
                // always 0600 regardless of umask.
                Path candidate = parent.resolve(realTarget.getFileName() + ".tmp" + System.nanoTime());
                try (OutputStream newFile = Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    newFile.write(content.getBytes(StandardCharsets.UTF_8));
                }
                // Only assigned to tmp once CREATE_NEW has actually succeeded: if the name collided with a file
                // this run didn't create, the catch below must never delete it. Not tested: the collision (a
                // nanoTime() value reused) can't be forced deterministically.
                tmp = candidate;
            }
            Files.move(tmp, realTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
            throw new ProviderException("cannot write " + target + ": " + ioReason(e, target), e);
        }
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

    private int at(Options o, PrintStream out, Commit commit) throws ProviderException {
        try (WorktreePool pool = new WorktreePool(runner, repo, scratch, o.keepWorktrees())) {
            TaskKey key = keys.keyFor(pool.checkout(commit), o.taskPath());
            out.println(key.taskPath() + " at " + commit.shortSha() + " " + commit.subject());
            out.println("key: " + key.key());
            out.println("implementation: " + key.implementation());
            key.valueFingerprints().forEach((property, hash) -> out.println("value " + property + ": " + hash));
            key.fileFingerprints().forEach((property, files) -> {
                out.println("files " + property + ":");
                files.forEach((path, hash) -> out.println("  " + KeyComparator.displayName(path) + "  " + hash));
            });
        }
        return EXIT_OK;
    }
}
