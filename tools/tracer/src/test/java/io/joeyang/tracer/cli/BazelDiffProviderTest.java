package io.joeyang.tracer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BazelDiffProviderTest {
    @TempDir Path tmp;
    Path repo, worktree, binary, out;

    @BeforeEach
    void setUp() throws IOException {
        repo = Files.createDirectories(tmp.resolve("repo"));
        worktree = Files.createDirectories(tmp.resolve("wt-aaaaaaa"));
        binary = tmp.resolve("bazel-diff");
        out = tmp.resolve("hashes-aaaaaaa.json");
    }

    BazelDiffProvider provider(FakeProcessRunner runner, boolean offline) {
        return new BazelDiffProvider(runner, repo, offline, Duration.ofMinutes(1), binary);
    }

    String generate() { return binary + " generate-hashes -w " + worktree + " -b bazel --fineGrainedHashExternalRepos=@maven"; }

    @Test void hashesRunsGenerateHashesFromTheRepoAndCleansTheWorktreeServer() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner().on(generate(), 0, "", "").on("bazel clean --expunge", 0, "", "");
        Files.writeString(out, "{}");

        Path result = provider(runner, false).hashes(worktree, out);

        assertEquals(out, result);
        assertEquals(List.of(binary.toString(), "generate-hashes", "-w", worktree.toString(), "-b", "bazel",
                "--fineGrainedHashExternalRepos=@maven", out.toString()), runner.calls.get(0));
        assertEquals(repo, runner.cwds.get(0));
        assertEquals(List.of("bazel", "clean", "--expunge"), runner.calls.get(1));
        assertEquals(worktree, runner.cwds.get(1));
    }

    @Test void offlineForbidsRepositoryDownloads() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner().on(binary.toString(), 0, "", "").on("bazel clean", 0, "", "");
        Files.writeString(out, "{}");
        provider(runner, true).hashes(worktree, out);
        assertTrue(runner.calls.get(0).contains("--bazelCommandOptions=--repository_disable_download"), runner.calls.get(0).toString());
    }

    @Test void missingOutputIsAProviderError() {
        FakeProcessRunner runner = new FakeProcessRunner().on(generate(), 0, "", "").on("bazel clean", 0, "", "");
        ProviderException e = assertThrows(ProviderException.class, () -> provider(runner, false).hashes(worktree, out));
        assertTrue(e.getMessage().contains("no hashes file"), e.getMessage());
    }

    @Test void nonZeroExitCarriesTheFirstErrorLine() {
        FakeProcessRunner runner = new FakeProcessRunner().on(generate(), 1, "", "ERROR: no such package '@maven//'\n").on("bazel clean", 0, "", "");
        ProviderException e = assertThrows(ProviderException.class, () -> provider(runner, false).hashes(worktree, out));
        assertTrue(e.getMessage().contains("no such package"), e.getMessage());
    }

    /**
     * Real stderr captured from {@code bazel-diff generate-hashes} against a worktree with a syntax error
     * injected into {@code utils/BUILD.bazel} (see commit message for how it was reproduced). bazel-diff's own
     * wrapper line ("[Error] Bazel query failed...") comes first; Bazel's real {@code ERROR:} lines come after.
     */
    private static final String BROKEN_PACKAGE_STDERR = ""
            + "\n"
            + "[Error] Bazel query failed, exit code 7: Loading: 0 packages loaded\n"
            + "ERROR: /repo/utils/BUILD.bazel:14:6: 'is' not supported, use '==' instead\n"
            + "ERROR: /repo/utils/BUILD.bazel:14:6: syntax error at 'is': expected newline\n"
            + "ERROR: package contains errors: utils\n"
            + "ERROR: package contains errors: utils: 'is' not supported, use '==' instead\n"
            + "ERROR: Error evaluating '//...:all-targets': error loading package 'utils': Package 'utils' contains errors\n";

    /**
     * Real stderr captured from a cold (freshly created) Bazel output base with {@code --repository_disable_download}:
     * bazel-diff's wrapper line comes first, then Bazel's repository-fetch failure, several lines in.
     */
    private static final String COLD_CACHE_OFFLINE_STDERR = ""
            + "\n"
            + "[Error] Bazel query failed, exit code 7: Loading: 0 packages loaded\n"
            + "INFO: Repo package_metadata+ defined by rule http_archive in @@bazel_tools//tools/build_defs/repo:http.bzl\n"
            + "INFO: Repo rules_license+ defined by rule http_archive in @@bazel_tools//tools/build_defs/repo:http.bzl\n"
            + "ERROR: /base/external/bazel_tools/tools/build_defs/repo/http.bzl:200:45: An error occurred during the fetch of repository 'package_metadata+':\n"
            + "   Traceback (most recent call last):\n"
            + "\tFile \"/base/external/bazel_tools/tools/build_defs/repo/http.bzl\", line 200, column 45, in _http_archive_impl\n"
            + "\t\tdownload_info = ctx.download_and_extract(\n"
            + "Error in download_and_extract: java.io.IOException: Failed to download repository @@package_metadata+: download is disabled.\n"
            + "ERROR: error loading package under directory '': no such package '@@package_metadata+//rules': java.io.IOException: Failed to download repository @@package_metadata+: download is disabled.\n";

    @Test void bazelErrorPrefersTheDownloadDisabledLineOverBazelDiffsOwnWrapperLine() {
        FakeProcessRunner runner = new FakeProcessRunner().on(generate(), 1, "", COLD_CACHE_OFFLINE_STDERR).on("bazel clean", 0, "", "");
        ProviderException e = assertThrows(ProviderException.class, () -> provider(runner, true).hashes(worktree, out));
        assertTrue(e.getMessage().contains("download is disabled"), e.getMessage());
        assertTrue(e.getMessage().contains(worktree.toString()), "must name the worktree: " + e.getMessage());
    }

    @Test void bazelErrorPrefersTheRealBazelErrorLineOverBazelDiffsOwnWrapperLine() {
        FakeProcessRunner runner = new FakeProcessRunner().on(generate(), 1, "", BROKEN_PACKAGE_STDERR).on("bazel clean", 0, "", "");
        ProviderException e = assertThrows(ProviderException.class, () -> provider(runner, false).hashes(worktree, out));
        assertTrue(e.getMessage().contains("'is' not supported"), e.getMessage());
        assertTrue(!e.getMessage().contains("[Error] Bazel query failed"), e.getMessage());
    }

    @Test void bazelErrorFallsBackToFirstErrorLineWhenNeitherPatternMatches() {
        FakeProcessRunner runner = new FakeProcessRunner().on(generate(), 1, "", "some other failure\n").on("bazel clean", 0, "", "");
        ProviderException e = assertThrows(ProviderException.class, () -> provider(runner, false).hashes(worktree, out));
        assertTrue(e.getMessage().contains("some other failure"), e.getMessage());
    }

    @Test void missingBinaryIsAProviderError() {
        FakeProcessRunner runner = new FakeProcessRunner()
                .failWith(binary.toString(), ProcessException.cannotStart(List.of(binary.toString()), new IOException("No such file")))
                .on("bazel clean", 0, "", "");
        ProviderException e = assertThrows(ProviderException.class, () -> provider(runner, false).hashes(worktree, out));
        assertTrue(e.getMessage().contains("cannot start"), e.getMessage());
        assertTrue(e.getMessage().contains("fetch-bazel-diff"), "must tell the user how to get it: " + e.getMessage());
    }

    @Test void cleanupFailureIsIgnored() throws Exception {
        FakeProcessRunner runner = new FakeProcessRunner().on(generate(), 0, "", "")
                .failWith("bazel clean", ProcessException.timeout(List.of("bazel"), Duration.ofSeconds(1)));
        Files.writeString(out, "{}");
        assertEquals(out, provider(runner, false).hashes(worktree, out));
    }

    @Test void impactedTargetsReadsTheOutputFile() throws Exception {
        Path prev = Files.writeString(tmp.resolve("hashes-a.json"), "{}");
        Path cur = Files.writeString(tmp.resolve("hashes-b.json"), "{}");
        Path impacted = tmp.resolve("hashes-b.json.impacted.txt");
        FakeProcessRunner runner = new FakeProcessRunner().on(binary + " get-impacted-targets", 0, "", "");
        Files.writeString(impacted, "//app/appA:appA\n//lib:lib\n\n");

        Set<String> targets = provider(runner, false).impactedTargets(prev, cur);

        assertEquals(Set.of("//app/appA:appA", "//lib:lib"), targets);
        assertEquals(List.of(binary.toString(), "get-impacted-targets", "-w", repo.toString(), "-b", "bazel",
                "--startingHashes", prev.toString(), "--finalHashes", cur.toString(), "-o", impacted.toString()), runner.calls.get(0));
    }

    @Test void emptyImpactedFileIsEmptySet() throws Exception {
        Path prev = Files.writeString(tmp.resolve("hashes-a.json"), "{}");
        Path cur = Files.writeString(tmp.resolve("hashes-b.json"), "{}");
        Files.writeString(tmp.resolve("hashes-b.json.impacted.txt"), "");
        FakeProcessRunner runner = new FakeProcessRunner().on(binary + " get-impacted-targets", 0, "", "");
        assertEquals(Set.of(), provider(runner, false).impactedTargets(prev, cur));
    }

    @Test void defaultBinaryPrefersTheFetchedCopy() throws IOException {
        assertEquals(Path.of("bazel-diff"), BazelDiffProvider.defaultBinary(repo));
        Path local = repo.resolve("tools/bin/bazel-diff");
        Files.createDirectories(local.getParent());
        Files.writeString(local, "#!/bin/sh\n");
        local.toFile().setExecutable(true);
        assertEquals(local, BazelDiffProvider.defaultBinary(repo));
    }
}
