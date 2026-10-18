# Evaluating `./trace` against Develocity

Study date: 2026-10-18. Repository revision evaluated: `7b31e39`.
Develocity documentation consulted: Gradle plugin 4.6 and Develocity 2026.3.

## Assessment

Develocity's Build Scan comparison overlaps substantially with the input analysis in
`./trace`. The tracer's distinctive value is automatically replaying a first-parent Git
range and reporting which commits changed a selected application's build inputs.

The current tracer is a working, Gradle-only tool. It is useful for local investigation
and learning, with a narrower scope than Develocity. Keep its historical investigation
workflow focused; use Develocity for shared CI and cache diagnostics when available.

## What the tracer measures

```text
Git range -> detached worktree per revision -> Gradle task
                                              |
                                     cache-debug output
                                              |
                                     key + fingerprints
                                              |
                            comparison with last successful revision
                                              |
                                verdict + culprits -> table / TSV
```

- `fingerprint`, the default, measures the main runtime classpath: application classes
  and resources, project dependencies, and external jars.
- `--task compileJava` measures compilation inputs, including source and compiler
  settings and ABI-normalized dependencies. A dependency implementation change can
  change the runtime fingerprint while leaving the compile key unchanged.
- `--at REV` inspects one revision's key and fingerprints without comparing revisions.

These are Gradle task cache keys, not pure artifact digests. They include task
implementation information: build-logic changes can change a key even when runtime
bytes remain identical. `CHANGED` therefore does not prove changed application behavior,
and a changed compile key does not prove compilation executed: cached outputs may exist.
See [tracer implementation](tracer.md) and the [scenario expectations](scenarios.md).

## Capability comparison

| Question | `./trace` | Develocity |
|---|---|---|
| Which commits changed this app's inputs? | Checks out and builds each revision in a first-parent range | Compares recorded builds; no direct documented equivalent to automatic Git-range replay was identified in the sources reviewed |
| Which task inputs changed? | Compares keys and names changed inputs | Compares input properties and individual file fingerprints |
| Did dependencies change? | Detects changes through compile/runtime classpaths | Compares resolved dependency graphs, including transitive dependencies |
| Why was work not reused? | Provides input-change evidence for the selected task | Adds execution, cacheability, and reused-output origin information |
| Where does analysis happen? | Locally, without a scan server | Uses Build Scans published to Develocity |

Develocity documents its task-input comparison in the
[cache-miss tutorial](https://docs.gradle.com/develocity/legacy/tutorials/task-inputs-comparison/).
Its [Gradle plugin manual](https://docs.develocity.ai/gradle/4.6/gradle-plugin/#capturing-file-fingerprints)
states that individual input-file fingerprints are captured by default.
Gradle's own [cache-debugging guide](https://docs.gradle.org/current/userguide/build_cache_debugging.html)
recommends Build Scan comparison for the hash-comparison work that otherwise requires
manual analysis. The absence of a Git-range replay feature in this study is a finding
from the documentation reviewed, not proof that no extension or custom workflow exists.

## Strengths and limitations

The tracer can reconstruct comparisons without historical scans. It offers a useful
compile-versus-runtime distinction and script-friendly TSV output and exit codes.
There is no scan server to configure for this local workflow.

Its limitations matter when interpreting results or considering wider adoption:

- **Build cost:** one sequential checkout and Gradle invocation per revision. Caches
  help, but historical revisions must still build with available dependencies and tools.
- **Historical fidelity:** old commits are rebuilt in the current environment; the
  results do not reconstruct the original CI machine or its undeclared inputs.
- **Attribution across failures:** after a failed revision, the next successful key is
  compared with the last successful key, potentially spanning several commits.
- **Runtime coverage:** the runtime classpath excludes deployment configuration,
  external services, and packaging inputs unless explicitly represented in the task.
- **Maintenance:** diagnostic-text parsing must track Gradle output changes. The
  implementation rejects unfamiliar input lines instead of silently reporting clean.
- **Portability:** Bash, `readlink -f`, POSIX permissions, and Unix-path assumptions make
  the current implementation Unix-oriented.
- **Process cleanup:** descendants that detach or become reparented are not reliably
  covered by the runner's descendant-based timeout cleanup.

The earlier option-value validation and competing CLI scratch-sweep findings have been
addressed by validation and a whole-run scratch lock. See [current behavior](tracer.md).

## Related Develocity tools

### Build Scan comparison

This is the closest counterpart for explaining why two builds have different task
inputs. Develocity also provides dependency and environment comparisons and links to
the builds that produced reused outputs. Its comparison model is based on builds,
which need not map one-to-one to Git commits. See the
[Build Comparison definition](https://docs.develocity.ai/2026.1/miscellaneous/glossary/)
and [task-input tutorial](https://docs.gradle.com/develocity/legacy/tutorials/task-inputs-comparison/).

### Build Caching Optimizer

The [Build Caching Optimizer](https://docs.develocity.ai/2026.3/integrations/agentic-ai/skills/develocity-build-caching-optimizer/)
is an AI agent skill that investigates cache misses, applies fixes, reruns builds to
validate improvements, and can open a pull request. It requires a Develocity instance,
its MCP server and comparison tool, suitable access, and the installed agent skill.
Its purpose is cache optimization rather than producing a commit-by-commit input ledger.

### Build Caching Report

The [Build Caching Report](https://docs.develocity.ai/2026.3/integrations/agentic-ai/skills/develocity-build-caching-report/)
surveys recorded CI scans and ranks avoidable misses by wasted time. It reads no source
code and runs no builds itself. It requires Develocity/MCP access and commit metadata
on the recorded builds. It can identify opportunities for the Optimizer to investigate.

## Verification performed

Source inspection and an independent implementation review were followed by this smoke test:

```bash
./trace appA --from HEAD~1 --to HEAD --offline --tsv
```

At the evaluated revision, the range was `13ab5c1` to `7b31e39`. Both revisions produced
runtime key `08f15fe6981a6b710d92e6561bbb15b7`; the rows were `base` and `clean`, and the
command exited 0. The latest commit changed documentation, so this was the expected result.

This study did not rerun the full scenario suite or validate a live Develocity installation.
Develocity claims above are based on official documentation, not a hands-on deployment.

## Recommendation and tradeoffs

| Approach | Advantages | Costs and limits |
|---|---|---|
| Keep `./trace` focused on local history | Works without previously captured scans; simple CLI; educational | Rebuilds history; maintains a diagnostic parser; limited environment visibility |
| Use Develocity for ongoing CI diagnostics | Reuses captured build evidence; richer comparisons; cache-analysis tooling | Requires instrumentation, retained scans, and access to an appropriate Develocity instance |
| Combine them when needed | Local replay can investigate missing history; scans can provide deeper build evidence | Additional integration and operating complexity |

Retain `./trace` for focused historical questions. If Develocity is already available,
use its comparison features for routine cache investigations rather than expanding the
tracer into a general build-analysis platform. Integration is an option, not work
implemented or authorized by this study.
