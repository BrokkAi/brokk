package ai.brokk.analyzer.source;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.InlineCoreProject;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class ScalaSourceCodeTest {

    @Test
    public void testQualifiedClassAndMethodSource() throws IOException {
        try (var testProject = InlineCoreProject.code(
                        """
                                package ai.brokk;

                                class Foo() {

                                    val field1: String = "Test"
                                    val multiLineField: String = "
                                      das
                                      "

                                    def foo1(): Int = {
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
            AnalyzerUtil.getSource(analyzer, "ai.brokk.Foo", false)
                    .ifPresentOrElse(
                            source -> assertEquals(
                                    """
                                            class Foo() {

                                                val field1: String = "Test"
                                                val multiLineField: String = "
                                                  das
                                                  "

                                                def foo1(): Int = {
                                                    return 1 + 2;
                                                }
                                            }
                                            """
                                            .strip(),
                                    source),
                            () -> fail("Could not find source code for 'Foo'!"));

            AnalyzerUtil.getSource(analyzer, "ai.brokk.Foo.foo1", false)
                    .ifPresentOrElse(
                            source -> assertEquals(
                                    """
                                                def foo1(): Int = {
                                                    return 1 + 2;
                                                }
                                            """
                                            .strip(),
                                    source),
                            () -> fail("Could not find source code for 'Foo.foo1'!"));

            AnalyzerUtil.getSource(analyzer, "ai.brokk.foo2", false)
                    .ifPresentOrElse(
                            source -> assertEquals(
                                    """
                                            def foo2(): String = {
                                               return "Hello, world!";
                                            }
                                            """
                                            .strip(),
                                    source),
                            () -> fail("Could not find source code for 'foo2'!"));
        }
    }
}
