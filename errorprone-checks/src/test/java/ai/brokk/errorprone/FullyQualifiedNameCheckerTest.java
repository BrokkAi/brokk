package ai.brokk.errorprone;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.CompilationTestHelper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for FullyQualifiedNameChecker. */
public class FullyQualifiedNameCheckerTest {

    private final CompilationTestHelper compilationHelper = CompilationTestHelper.newInstance(
                    FullyQualifiedNameChecker.class, getClass())
            .setArgs(List.of("--release", "21"));
    ;

    private final BugCheckerRefactoringTestHelper refactoringHelper =
            BugCheckerRefactoringTestHelper.newInstance(FullyQualifiedNameChecker.class, getClass());

    @Test
    public void flagsFullyQualifiedName() {
        compilationHelper
                .addSourceLines(
                        "Test.java",
                        "package test;",
                        "class Test {",
                        "  // BUG: Diagnostic contains: BrokkFullyQualifiedName",
                        "  java.util.List<String> list;",
                        "}")
                .doTest();
    }

    @Test
    public void suggestsFixWithImport() {
        refactoringHelper
                .addInputLines("Test.java", "package test;", "class Test {", "  java.util.List<String> list;", "}")
                .addOutputLines(
                        "Test.java",
                        "package test;",
                        "",
                        "import java.util.List;",
                        "",
                        "class Test {",
                        "  List<String> list;",
                        "}")
                .doTest();
    }

    @Test
    public void doesNotFlagWhenCollisionExists() {
        compilationHelper
                .addSourceLines(
                        "Test.java",
                        "package test;",
                        "import java.awt.List;",
                        "class Test {",
                        "  java.util.List<String> list;",
                        "}")
                .expectNoDiagnostics()
                .doTest();
    }

    @Test
    public void doesNotFlagSimpleName() {
        compilationHelper
                .addSourceLines(
                        "Test.java",
                        "package test;",
                        "import java.util.List;",
                        "class Test {",
                        "  List<String> list;",
                        "}")
                .expectNoDiagnostics()
                .doTest();
    }

    @Test
    public void doesNotFlagInnerClassWithImportedParent() {
        compilationHelper
                .addSourceLines(
                        "Test.java",
                        "package test;",
                        "import java.util.Map;",
                        "class Test {",
                        "  Map.Entry<String, String> entry;",
                        "}")
                .expectNoDiagnostics()
                .doTest();
    }

    @Test
    public void flagsFullyQualifiedJavaLang() {
        refactoringHelper
                .addInputLines("Test.java", "package test;", "class Test {", "  java.lang.String s = \"hi\";", "}")
                .addOutputLines("Test.java", "package test;", "", "class Test {", "  String s = \"hi\";", "}")
                .doTest();
    }

    @Test
    public void doesNotFlagWhenWildcardImportCollisionExists() {
        compilationHelper
                .addSourceLines(
                        "Test.java",
                        "package test;",
                        "import java.util.*;",
                        "class Test {",
                        "  private record Theme(List<String> utilBg, test.sub.List subBg) {",
                        "      static Theme create() {",
                        "          test.sub.List subBg = null;",
                        "          return new Theme(null, subBg);",
                        "      }",
                        "  }",
                        "}")
                .addSourceLines("List.java", "package test.sub;", "public class List {}")
                .expectNoDiagnostics()
                .doTest();
    }

    @Test
    public void doesNotFlagWhenWildcardImportCollisionExistsWithAwtColor() {
        compilationHelper
                .addSourceLines(
                        "Theme.java",
                        "package test;",
                        "import java.awt.*;",
                        "class Theme {",
                        "  Color awtBg;",
                        "  javafx.scene.paint.Color fxBg;",
                        "}")
                .addSourceLines("Color.java", "package javafx.scene.paint;", "public class Color {}")
                .expectNoDiagnostics()
                .doTest();
    }
}
