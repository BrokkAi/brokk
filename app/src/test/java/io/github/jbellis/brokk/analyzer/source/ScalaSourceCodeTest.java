package io.github.jbellis.brokk.analyzer.source;

import static io.github.jbellis.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.SourceCodeProvider;
import io.github.jbellis.brokk.testutil.InlineTestProjectCreator;
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
            var scp = analyzer.as(SourceCodeProvider.class)
                    .orElseGet(() -> fail("Analyzer does not support source code extraction!"));
            scp.getClassSource("ai.brokk.Foo", false)
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

            scp.getMethodSource("ai.brokk.Foo.foo1", false)
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

            scp.getMethodSource("ai.brokk.foo2", false)
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
