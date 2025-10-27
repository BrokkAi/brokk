package io.github.jbellis.brokk.analyzer;

import static io.github.jbellis.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class ScalaAnalyzerTest {

    @Test
    public void testSimpleUnqualifiedClasses() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                class Foo() {}
                                case class Bar()
                                object Baz {}
                                enum Color:
                                  case Red, Green, Blue
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
            analyzer.getDefinition("Baz$")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("Baz$", cu.fqName());
                                assertEquals("", cu.packageName());
                                assertEquals("Baz$", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Baz$'!"));
            analyzer.getDefinition("Color")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("Color", cu.fqName());
                                assertEquals("", cu.packageName());
                                assertEquals("Color", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Color'!"));
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

    @Test
    public void testSimpleMethodsWithinClasses() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk

                                class Foo {
                                  def test1(): Unit = {}
                                }
                                trait Bar {
                                  def test2: Unit = {}
                                }
                                object Baz {
                                  def test3: Unit = {}
                                }
                                """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            analyzer.getDefinition("ai.brokk.Foo.test1")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Foo.test1", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.test1", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.test1'!"));
            analyzer.getDefinition("ai.brokk.Bar.test2")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Bar.test2", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Bar.test2", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Bar.test2'!"));
            analyzer.getDefinition("ai.brokk.Baz$.test3")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Baz$.test3", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Baz$.test3", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Baz$.test3'!"));
        }
    }

    @Test
    @Disabled("TSA only supports nested functions in the context of JavaTSA lambdas")
    public void testSimpleNestedMethodsWithinClasses() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk

                                class Foo {
                                  def test1(): Unit = {
                                    def test2: Unit = {}
                                  }
                                }
                                """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            analyzer.getDefinition("ai.brokk.Foo.test1")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Foo.test1", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.test1", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.test1'!"));
            analyzer.getDefinition("ai.brokk.Foo.test1.test2")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Foo.test1.test2", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.test1.test2", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.test1.test2'!"));
        }
    }

    @Test
    public void testSimpleConstructorInClassDefinition() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk

                                class Foo(a: Int,  b: String)
                                """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            analyzer.getDefinition("ai.brokk.Foo.Foo")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Foo.Foo", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.Foo", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.Foo'!"));
        }
    }

    @Test
    public void testSecondaryConstructorsInClassDefinition() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk

                                class Foo {
                                  def this(a: Int,  b: String) = this(a)
                                }
                                """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            analyzer.getDefinition("ai.brokk.Foo.Foo")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Foo.Foo", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.Foo", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.Foo'!"));
        }
    }

    @Test
    public void testFieldsWithinClassesAndCompilationUnit() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk

                                var GLOBAL_VAR = "foo"
                                val GLOBAL_VAL = "bar"

                                class Foo:
                                  val Field1 = "123"
                                  var Field2 = 456
                                """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            analyzer.getDefinition("ai.brokk.GLOBAL_VAR")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.GLOBAL_VAR", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("GLOBAL_VAR", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'GLOBAL_VAR'!"));
            analyzer.getDefinition("ai.brokk.GLOBAL_VAL")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.GLOBAL_VAL", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("GLOBAL_VAL", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'GLOBAL_VAL'!"));

            analyzer.getDefinition("ai.brokk.Foo.Field1")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Foo.Field1", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.Field1", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.Field1'!"));
            analyzer.getDefinition("ai.brokk.Foo.Field2")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Foo.Field2", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.Field2", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.Field2'!"));
        }
    }

    @Test
    public void testFieldsWithinEnums() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk

                                enum Colors {
                                  case BLUE, GREEN
                                }

                                enum Sports {
                                  case SOCCER
                                  case RUGBY
                                }
                                """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            analyzer.getDefinition("ai.brokk.Colors.BLUE")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Colors.BLUE", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Colors.BLUE", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Colors.BLUE'!"));
            analyzer.getDefinition("ai.brokk.Colors.GREEN")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Colors.GREEN", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Colors.GREEN", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Colors.GREEN'!"));

            analyzer.getDefinition("ai.brokk.Sports.SOCCER")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Sports.SOCCER", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Sports.SOCCER", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Sports.SOCCER'!"));
            analyzer.getDefinition("ai.brokk.Sports.RUGBY")
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Sports.RUGBY", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Sports.RUGBY", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Sports.RUGBY'!"));
        }
    }
}
