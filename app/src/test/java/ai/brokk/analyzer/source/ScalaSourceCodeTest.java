package ai.brokk.analyzer.source;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class ScalaSourceCodeTest {

    @Test
    public void testQualifiedClassAndMethodSource() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
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
            var analyzer = createTreeSitterAnalyzer(testProject);
            AnalyzerUtil.getClassSource(analyzer, "ai.brokk.Foo", false)
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

            AnalyzerUtil.getMethodSource(analyzer, "ai.brokk.Foo.foo1", false)
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

            AnalyzerUtil.getMethodSource(analyzer, "ai.brokk.foo2", false)
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
