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
                        "ai/brokk/annotations/BlockingOperation.java",
                        "package ai.brokk.annotations;",
                        "public @interface BlockingOperation { String nonBlocking(); }")
                .addSourceLines(
                        "ai/brokk/annotations/NonBlockingOperation.java",
                        "package ai.brokk.annotations;",
                        "public @interface NonBlockingOperation {}")
                .addSourceLines(
                        "test/CF.java",
                        "package test;",
                        "import ai.brokk.annotations.BlockingOperation;",
                        "public interface CF {",
                        "  @BlockingOperation(nonBlocking = \"computedFiles\")",
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
    public void doesNotWarnOnNonBlockingAnnotatedMethod() {
        helper.addSourceLines(
                        "ai/brokk/annotations/BlockingOperation.java",
                        "package ai.brokk.annotations;",
                        "public @interface BlockingOperation { String nonBlocking(); }")
                .addSourceLines(
                        "ai/brokk/annotations/NonBlockingOperation.java",
                        "package ai.brokk.annotations;",
                        "public @interface NonBlockingOperation {}")
                .addSourceLines(
                        "test/CF.java",
                        "package test;",
                        "import ai.brokk.annotations.NonBlockingOperation;",
                        "public class CF {",
                        "  @NonBlockingOperation",
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
                        "ai/brokk/annotations/BlockingOperation.java",
                        "package ai.brokk.annotations;",
                        "public @interface BlockingOperation { String nonBlocking(); }")
                .addSourceLines(
                        "ai/brokk/annotations/NonBlockingOperation.java",
                        "package ai.brokk.annotations;",
                        "public @interface NonBlockingOperation {}")
                .addSourceLines(
                        "test/Base.java",
                        "package test;",
                        "import ai.brokk.annotations.BlockingOperation;",
                        "public interface Base {",
                        "  @BlockingOperation(nonBlocking = \"computedFiles\")",
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
                        "ai/brokk/annotations/BlockingOperation.java",
                        "package ai.brokk.annotations;",
                        "public @interface BlockingOperation { String nonBlocking(); }")
                .addSourceLines(
                        "test/CF.java",
                        "package test;",
                        "import ai.brokk.annotations.BlockingOperation;",
                        "import java.util.Set;",
                        "import java.util.function.Supplier;",
                        "public class CF {",
                        "  @BlockingOperation(nonBlocking = \"computedFiles\")",
                        "  public Set<String> files() { return java.util.Collections.emptySet(); }",
                        "  Supplier<Set<String>> supplier() {",
                        "    // BUG: Diagnostic contains: computed",
                        "    return this::files;",
                        "  }",
                        "}")
                .doTest();
    }
}
