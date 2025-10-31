package ai.brokk.analyzer.skeleton;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class ScalaSkeletonTest {

    @Test
    public void testQualifiedClassAndMethodSkeleton() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk;

                                class Foo() {

                                    val field1: String = "Test"
                                    val multiLineField: String = "
                                      das
                                      "

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
            var analyzer = createTreeSitterAnalyzer(testProject);
            // Provider extracted via AnalyzerUtil

            AnalyzerUtil.getSkeleton(analyzer, "ai.brokk.Foo")
                    .ifPresentOrElse(
                            source -> assertEquals(
                                    """
                                            class Foo() {
                                              val field1: String = "Test";
                                              val multiLineField: String = "
                                                    das
                                                    ";
                                              private def foo1(): Int = {...}
                                            }
                                            """
                                            .strip(),
                                    source),
                            () -> fail("Could not find source code for 'Foo'!"));
        }
    }

    @Test
    public void testGenericMethodSkeleton() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
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
            var analyzer = createTreeSitterAnalyzer(testProject);
            // Provider extracted via AnalyzerUtil

            AnalyzerUtil.getSkeleton(analyzer, "ai.brokk.GenericFoo")
                    .ifPresentOrElse(
                            source -> assertEquals(
                                    """
                                            class GenericFoo[R]() {
                                              def genericMethod[T](arg: T): T = {...}
                                            }
                                            """
                                            .strip(),
                                    source),
                            () -> fail("Could not find source code for 'GenericFoo'!"));
        }
    }

    @Test
    public void testImplicitParameterMethodSkeleton() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
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
            var analyzer = createTreeSitterAnalyzer(testProject);
            // Provider extracted via AnalyzerUtil

            AnalyzerUtil.getSkeleton(analyzer, "ai.brokk.ImplicitFoo")
                    .ifPresentOrElse(
                            source -> assertEquals(
                                    """
                                            class ImplicitFoo() {
                                              def implicitMethod(arg: Int)(implicit ec: ExecutionContext): String = {...}
                                            }
                                            """
                                            .strip(),
                                    source),
                            () -> fail("Could not find source code for 'ImplicitFoo'!"));
        }
    }

    @Test
    public void testScala3SignificantWhitespaceSkeleton() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
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
            var analyzer = createTreeSitterAnalyzer(testProject);
            // Provider extracted via AnalyzerUtil

            AnalyzerUtil.getSkeleton(analyzer, "ai.brokk.WhitespaceClass")
                    .ifPresentOrElse(
                            // Note in the following, Scala 2 braces are used
                            source -> assertEquals(
                                    """
                                            class WhitespaceClass {
                                              val s = ""\"
                                                  line 1
                                                    line 2
                                                ""\";
                                              val i = 2;
                                            }
                                            """
                                            .strip(),
                                    source),
                            () -> fail("Could not find source code for 'WhitespaceClass'!"));
        }
    }
}
