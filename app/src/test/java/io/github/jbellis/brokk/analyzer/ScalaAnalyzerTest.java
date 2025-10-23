package io.github.jbellis.brokk.analyzer;

import static io.github.jbellis.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.junit.jupiter.api.Test;

public class ScalaAnalyzerTest {

    @Test
    public void testSimpleUnqualifiedClass() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                class Foo() {}
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
    public void testSimpleUnqualifiedCaseClass() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                case class Foo()
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
}
