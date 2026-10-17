package io.joeyang.tracer.cli;

/** Hand-written cache-debug output in the exact shape Gradle prints, shared by the parser and provider tests. */
final class CacheDebugFixtures {
    private CacheDebugFixtures() {}

    static final String COMPILE_JAVA_LOG = """
            Downloading https://services.gradle.org/distributions/gradle-9.8.1-bin.zip
            > Task :core:compileJava
            Appending implementation to build cache key: org.gradle.api.tasks.compile.JavaCompile_Decorated@aaaa
            Appending input value fingerprint for 'options.release' to build cache key: 1111
            Appending input file fingerprints for 'stableSources' to build cache key: 2222 - RELATIVE_PATH{/w/core/src/main/java/io/joeyang/tracer/core/Greeter.java='io/joeyang/tracer/core/Greeter.java' / 3333}
            Build cache key for task ':core:compileJava' is c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0

            > Task :core:jar
            Appending implementation to build cache key: org.gradle.api.tasks.bundling.Jar_Decorated@cccc
            Non-cacheable because Not worth caching [NOT_CACHEABLE]

            > Task :app:appA:compileJava
            Appending implementation to build cache key: org.gradle.api.tasks.compile.JavaCompile_Decorated@bbbb
            Appending additional implementation to build cache key: org.gradle.api.tasks.compile.JavaCompile_Decorated@bbbb
            Appending input value fingerprint for 'options.release' to build cache key: 91e6b2f4b53f9e7b3eadb03039173cc5
            Appending input value fingerprint for 'options.compilerArgs' to build cache key: 4444
            Appending input file fingerprints for 'classpath' to build cache key: b19a - COMPILE_CLASSPATH{/w/core/build/classes/java/main/io/joeyang/tracer/core/Greeter.class='io/joeyang/tracer/core/Greeter.class' / 5042bbb7be5171b8d45ca1e0a8193342, /w/lib/build/classes/java/main/io/joeyang/tracer/lib/Service.class='io/joeyang/tracer/lib/Service.class' / 6666}
            Appending input file fingerprints for 'options.annotationProcessorPath' to build cache key: 5fd1 - CLASSPATH{EMPTY}
            Appending input file fingerprints for 'stableSources' to build cache key: 856d - RELATIVE_PATH{/w/app/appA/src/main/java/io/joeyang/tracer/app/a/AppA.java='io/joeyang/tracer/app/a/AppA.java' / 55e49a9f6bfdc6ea941195b682520e54}
            Appending output property name to build cache key: destinationDirectory
            Build cache key for task ':app:appA:compileJava' is 35c0f7cbe157cd2e30090cc5510cec8c

            BUILD SUCCESSFUL in 2s
            """;
}
