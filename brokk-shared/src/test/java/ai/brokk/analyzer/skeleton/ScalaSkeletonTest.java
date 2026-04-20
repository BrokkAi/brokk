package ai.brokk.analyzer.skeleton;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.fail;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.InlineCoreProject;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class ScalaSkeletonTest {

    @Test
    public void testQualifiedClassAndMethodSkeleton() throws IOException {
        try (var testProject = InlineCoreProject.code(
                        """
                                package ai.brokk;

                                class Foo() {

                                    val field1: String = "Test"
                                    val multiLineField: String = \"\"\"
                                      das
                                      \"\"\"

                                    private def foo1(): Int = {
                                        return 1 + 2;
                                    }
                                }

                                def foo2(): String = {
                                   return "Hello, world!";
                                }
                                """,
                        "Foo.scala")
                .build()) {
            var analyzer = testProject.getAnalyzer();
            // Provider extracted via AnalyzerUtil

            AnalyzerUtil.getSkeleton(analyzer, "ai.brokk.Foo")
                    .ifPresentOrElse(
                            source -> assertCodeEquals(
                                    "class Foo() {\n"
                                            + "  val field1: String = \"Test\"\n"
                                            + "  val multiLineField: String = \"\"\"\n"
                                            + "        das\n"
                                            + "        \"\"\"\n"
                                            + "  private def foo1(): Int = {...}\n"
                                            + "}",
                                    source),
                            () -> fail("Could not find source code for 'Foo'!"));
        }
    }

    @Test
    public void testGenericMethodSkeleton() throws IOException {
        try (var testProject = InlineCoreProject.code(
                        """
                                package ai.brokk;

                                class GenericFoo[R]() {
                                    def genericMethod[T](arg: T): T = {
                                        return arg;
                                    }
                                }
                                """,
                        "GenericFoo.scala")
                .build()) {
            var analyzer = testProject.getAnalyzer();
            // Provider extracted via AnalyzerUtil

            AnalyzerUtil.getSkeleton(analyzer, "ai.brokk.GenericFoo")
                    .ifPresentOrElse(
                            source -> assertCodeEquals(
                                    """
                                            class GenericFoo[R]() {
                                              def genericMethod[T](arg: T): T = {...}
                                            }
                                            """,
                                    source),
                            () -> fail("Could not find source code for 'GenericFoo'!"));
        }
    }

    @Test
    public void testImplicitParameterMethodSkeleton() throws IOException {
        try (var testProject = InlineCoreProject.code(
                        """
                                package ai.brokk;

                                import scala.concurrent.ExecutionContext;

                                class ImplicitFoo() {
                                    def implicitMethod(arg: Int)(implicit ec: ExecutionContext): String = {
                                        return "done";
                                    }
                                }
                                """,
                        "ImplicitFoo.scala")
                .build()) {
            var analyzer = testProject.getAnalyzer();
            // Provider extracted via AnalyzerUtil

            AnalyzerUtil.getSkeleton(analyzer, "ai.brokk.ImplicitFoo")
                    .ifPresentOrElse(
                            source -> assertCodeEquals(
                                    """
                                            class ImplicitFoo() {
                                              def implicitMethod(arg: Int)(implicit ec: ExecutionContext): String = {...}
                                            }
                                            """,
                                    source),
                            () -> fail("Could not find source code for 'ImplicitFoo'!"));
        }
    }

    @Test
    public void testScala3SignificantWhitespaceSkeleton() throws IOException {
        try (var testProject = InlineCoreProject.code(
                        """
                                package ai.brokk;

                                class WhitespaceClass:
                                  val s = \"\"\"
                                    line 1
                                      line 2
                                  \"\"\"

                                  val i = 2
                                """,
                        "WhitespaceClass.scala")
                .build()) {
            var analyzer = testProject.getAnalyzer();
            // Provider extracted via AnalyzerUtil

            AnalyzerUtil.getSkeleton(analyzer, "ai.brokk.WhitespaceClass")
                    .ifPresentOrElse(
                            // Note in the following, Scala 2 braces are used
                            source -> assertCodeEquals(
                                    "class WhitespaceClass {\n"
                                            + "  val s = \"\"\"\n"
                                            + "      line 1\n"
                                            + "        line 2\n"
                                            + "    \"\"\"\n"
                                            + "  val i = 2\n"
                                            + "}",
                                    source),
                            () -> fail("Could not find source code for 'WhitespaceClass'!"));
        }
    }
}
