package ai.brokk.errorprone;

import com.google.errorprone.CompilationTestHelper;
import java.util.List;
import org.junit.jupiter.api.Test;

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
            .setArgs(List.of("--release", "21"));

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
                        "import javax.swing.SwingUtilities;",
                        "class Use {",
                        "  void f(CF cf) {",
                        "    SwingUtilities.invokeLater(() -> {",
                        "      // BUG: Diagnostic contains: computed",
                        "      cf.files();",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void warnsOnBlockingInThenBranchOfEdtCheck() {
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
                        "import javax.swing.SwingUtilities;",
                        "class Use {",
                        "  void f(CF cf) {",
                        "    if (SwingUtilities.isEventDispatchThread()) {",
                        "      // BUG: Diagnostic contains: computed",
                        "      cf.files();",
                        "    }",
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
    public void doesNotWarnOnMemberReferenceToBlockingMethod() {
        helper.addSourceLines(
                        "org/jetbrains/annotations/Blocking.java",
                        "package org.jetbrains.annotations;",
                        "public @interface Blocking {}")
                .addSourceLines(
                        "test/CF.java",
                        "package test;",
                        "import org.jetbrains.annotations.Blocking;",
                        "import java.util.Set;",
                        "public class CF {",
                        "  @Blocking",
                        "  public Set<String> files() { return java.util.Collections.emptySet(); }",
                        "}")
                .addSourceLines(
                        "test/Use.java",
                        "package test;",
                        "import javax.swing.SwingUtilities;",
                        "import java.util.Set;",
                        "import java.util.function.Supplier;",
                        "class Use {",
                        "  void f(CF cf) {",
                        "    SwingUtilities.invokeLater(() -> {",
                        "      Supplier<Set<String>> s = cf::files;",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void doesNotWarnWhenGuardingFromEdt() {
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
                        "import javax.swing.SwingUtilities;",
                        "class Use {",
                        "  void f(CF cf) {",
                        "    if (SwingUtilities.isEventDispatchThread()) {",
                        "      return;",
                        "    }",
                        "    // Safe: guarded to only run off the EDT",
                        "    cf.files();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void doesNotWarnOnElseBranchOfEdtCheck() {
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
                        "import javax.swing.SwingUtilities;",
                        "class Use {",
                        "  void f(CF cf) {",
                        "    if (SwingUtilities.isEventDispatchThread()) {",
                        "      // do nothing on EDT",
                        "    } else {",
                        "      // Safe: only run off the EDT",
                        "      cf.files();",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }
}
