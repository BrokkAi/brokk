package ai.brokk.errorprone;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Test;

/**
 * Unit tests for BlockingOperationChecker using Error Prone's CompilationTestHelper.
 *
 * These tests add inlined sources (including minimal versions of our annotations)
 * and assert diagnostics on lines marked with:
 *   // BUG: Diagnostic contains: <substring>
 */
public class BlockingOperationCheckerTest {

    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(
                    BlockingOperationChecker.class, getClass())
            .setArgs(java.util.List.of("--release", "21"));

    @Test
    public void warnsOnBlockingMethodInvocation() {
        helper.addSourceLines(
                        "org/jetbrains/annotations/Blocking.java",
                        "package org.jetbrains.annotations;",
                        "public @interface Blocking {}")
                .addSourceLines(
                        "test/CF.java",
                        "package test;",
                        "import org.jetbrains.annotations.Blocking;",
                        "public interface CF {",
                        "  @Blocking",
                        "  java.util.Set<String> files();",
                        "}")
                .addSourceLines(
                        "test/Use.java",
                        "package test;",
                        "class Use {",
                        "  void f(CF cf) {",
                        "    // BUG: Diagnostic contains: computed",
                        "    cf.files();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void doesNotWarnOnUnannotatedMethod() {
        helper.addSourceLines(
                        "test/CF.java",
                        "package test;",
                        "public class CF {",
                        "  java.util.Set<String> computedFiles() { return java.util.Collections.emptySet(); }",
                        "}")
                .addSourceLines(
                        "test/Use.java",
                        "package test;",
                        "class Use {",
                        "  void f(CF cf) {",
                        "    cf.computedFiles();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void doesNotWarnWhenOverrideOmitsBlockingAnnotation() {
        helper.addSourceLines(
                        "org/jetbrains/annotations/Blocking.java",
                        "package org.jetbrains.annotations;",
                        "public @interface Blocking {}")
                .addSourceLines(
                        "test/Base.java",
                        "package test;",
                        "import org.jetbrains.annotations.Blocking;",
                        "public interface Base {",
                        "  @Blocking",
                        "  java.util.Set<String> files();",
                        "}")
                .addSourceLines(
                        "test/Sub.java",
                        "package test;",
                        "public class Sub implements Base {",
                        "  @Override",
                        "  public java.util.Set<String> files() { return java.util.Collections.emptySet(); }",
                        "}")
                .addSourceLines(
                        "test/Use.java",
                        "package test;",
                        "class Use {",
                        "  void f(Sub s) {",
                        "    // Sub::files is not annotated; checker should not warn",
                        "    s.files();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void warnsOnMemberReferenceToBlockingMethod() {
        helper.addSourceLines(
                        "org/jetbrains/annotations/Blocking.java",
                        "package org.jetbrains.annotations;",
                        "public @interface Blocking {}")
                .addSourceLines(
                        "test/CF.java",
                        "package test;",
                        "import org.jetbrains.annotations.Blocking;",
                        "import java.util.Set;",
                        "import java.util.function.Supplier;",
                        "public class CF {",
                        "  @Blocking",
                        "  public Set<String> files() { return java.util.Collections.emptySet(); }",
                        "  Supplier<Set<String>> supplier() {",
                        "    // BUG: Diagnostic contains: computed",
                        "    return this::files;",
                        "  }",
                        "}")
                .doTest();
    }
}
