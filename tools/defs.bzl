"""Repo-wide Bazel macros: shared javacopts and a JUnit 5 runner built on the console launcher."""

load("@rules_java//java:java_binary.bzl", "java_binary")
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_java//java:java_test.bzl", "java_test")

# The repo forbids deprecated APIs; Gradle has no equivalent flag here, so this is what surfaces them under Bazel.
# SCENARIO S6 appends "-Xlint:all" here (and to the Gradle convention plugin) in the same commit.
JAVACOPTS = ["-Xlint:deprecation"]

_JUNIT_API = ["@maven//:org_junit_jupiter_junit_jupiter_api"]
_JUNIT_RUNTIME = ["@maven//:org_junit_platform_junit_platform_console_standalone"]

def tracer_java_library(name, **kwargs):
    """java_library with the repo-wide javacopts."""
    java_library(name = name, javacopts = JAVACOPTS, **kwargs)

def tracer_java_binary(name, **kwargs):
    """java_binary with the repo-wide javacopts."""
    java_binary(name = name, javacopts = JAVACOPTS, **kwargs)

def junit5_test_suite(name, srcs, deps = [], runtime_deps = [], resources = [], resource_strip_prefix = None, **kwargs):
    """One java_test per *Test.java in srcs, executed through org.junit.platform.console.ConsoleLauncher.

    Sources that are not tests (fakes, helpers) and resources go into <name>_support, a testonly library
    every test depends on. The suite target <name> groups all tests.
    """
    tests = [s for s in srcs if s.endswith("Test.java")]
    helpers = [s for s in srcs if not s.endswith("Test.java")]
    support = []
    if helpers or resources:
        java_library(
            name = name + "_support",
            testonly = True,
            srcs = helpers,
            resources = resources,
            resource_strip_prefix = resource_strip_prefix,
            deps = deps + _JUNIT_API,
            javacopts = JAVACOPTS,
        )
        support = [":" + name + "_support"]
    targets = []
    for src in tests:
        class_name = src.removeprefix("src/test/java/").removesuffix(".java").replace("/", ".")
        target = class_name.rsplit(".", 1)[-1]
        kwargs.setdefault("size", "small")
        java_test(
            name = target,
            srcs = [src],
            deps = deps + support + _JUNIT_API,
            runtime_deps = runtime_deps + _JUNIT_RUNTIME,
            javacopts = JAVACOPTS,
            main_class = "org.junit.platform.console.ConsoleLauncher",
            use_testrunner = False,
            args = ["execute", "--select-class", class_name, "--disable-banner", "--details=summary", "--fail-if-no-tests"],
            **kwargs
        )
        targets.append(":" + target)
    native.test_suite(name = name, tests = targets)
