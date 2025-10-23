package io.github.jbellis.brokk.analyzer.skeleton;

import static io.github.jbellis.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.jbellis.brokk.analyzer.SkeletonProvider;
import io.github.jbellis.brokk.testutil.InlineTestProjectCreator;
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
            var scp = analyzer.as(SkeletonProvider.class)
                    .orElseGet(() -> fail("Analyzer does not support skeleton extraction!"));
            scp.getSkeleton("ai.brokk.Foo")
                    .ifPresentOrElse(
                            source -> assertEquals(
                                    """
                                            class Foo() {
                                              val field1: String = "Test";
                                              val multiLineField: String = "
                                                    das
                                                    ";
                                              foo1(): Int
                                            }
                                            """
                                            .strip(),
                                    source),
                            () -> fail("Could not find source code for 'Foo'!"));
        }
    }
}
