package io.github.jbellis.brokk.analyzer;

import static io.github.jbellis.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class ScalaAnalyzerTest {

    @Test
    public void testSimpleUnqualifiedClasses() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                class Foo() {}
                                case class Bar()
                                """,
                        "Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            analyzer.getDefinition("Foo")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("Foo", cu.fqName());
                                assertEquals("", cu.packageName());
                                assertEquals("Foo", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo'!"));
            analyzer.getDefinition("Bar")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("Bar", cu.fqName());
                                assertEquals("", cu.packageName());
                                assertEquals("Bar", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Bar'!"));
        }
    }

    @Test
    public void testSimpleUnqualifiedTrait() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                trait Foo {}
                                """,
                        "Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            analyzer.getDefinition("Foo")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("Foo", cu.fqName());
                                assertEquals("", cu.packageName());
                                assertEquals("Foo", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo'!"));
        }
    }

    @Test
    public void testSimpleQualifiedClasses() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk

                                class Foo()

                                trait Bar
                                """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            analyzer.getDefinition("ai.brokk.Foo")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("ai.brokk.Foo", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo'!"));
            analyzer.getDefinition("ai.brokk.Bar")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("ai.brokk.Bar", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Bar", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Bar'!"));
        }
    }
}
