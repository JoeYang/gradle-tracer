package io.joeyang.tracer.cli;

import java.io.PrintStream;

/** Entry point. Only usage handling exists until Tasks 12 and 13. */
public final class Cli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 1;
    public static final int EXIT_TOOL_FAILURE = 2;
    public static final int EXIT_CHANGED = 3;

    static final String USAGE = """
            usage: trace <app> --from <rev> --to <rev> [--task <name>] [--no-bazel] [--offline] [--keep-worktrees] [--tsv]
                   trace <app> --at <rev> [--task <name>]

              <app>       appA | appB | appC   (maps to :app:<app>:<task> and //app/<app>:<app>)
              --task      Gradle task whose cache key is compared; default compileJava, alternative fingerprint
              --no-bazel  skip the bazel-diff column
              --offline   pass --offline to gradle and bazel
              --tsv       machine-readable output (sha, subject, key, bazel, verdict, culprits)

            exit codes: 0 no change, 1 usage error, 2 a tool failed, 3 at least one CHANGED
            """;

    private Cli() {}

    static int usage(PrintStream err, String problem) {
        err.println("trace: " + problem);
        err.print(USAGE);
        return EXIT_USAGE;
    }

    public static void main(String[] args) {
        System.exit(usage(System.err, "not implemented yet"));
    }
}
