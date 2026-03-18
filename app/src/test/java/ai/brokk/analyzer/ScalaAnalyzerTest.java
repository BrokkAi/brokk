package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.Map;
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
            analyzer.getDefinitions("Foo").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("Foo", cu.fqName());
                                assertEquals("", cu.packageName());
                                assertEquals("Foo", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo'!"));
            analyzer.getDefinitions("Bar").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("Bar", cu.fqName());
                                assertEquals("", cu.packageName());
                                assertEquals("Bar", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Bar'!"));
            analyzer.getDefinitions("Baz$").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("Baz$", cu.fqName());
                                assertEquals("", cu.packageName());
                                assertEquals("Baz$", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Baz$'!"));
            analyzer.getDefinitions("Color").stream()
                    .findFirst()
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
            analyzer.getDefinitions("Foo").stream()
                    .findFirst()
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
            analyzer.getDefinitions("ai.brokk.Foo").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isClass());
                                assertEquals("ai.brokk.Foo", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo'!"));
            analyzer.getDefinitions("ai.brokk.Bar").stream()
                    .findFirst()
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
            analyzer.getDefinitions("ai.brokk.Foo.test1").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Foo.test1", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.test1", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.test1'!"));
            analyzer.getDefinitions("ai.brokk.Bar.test2").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Bar.test2", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Bar.test2", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Bar.test2'!"));
            analyzer.getDefinitions("ai.brokk.Baz$.test3").stream()
                    .findFirst()
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
            analyzer.getDefinitions("ai.brokk.Foo.test1").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isFunction());
                                assertEquals("ai.brokk.Foo.test1", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.test1", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.test1'!"));
            analyzer.getDefinitions("ai.brokk.Foo.test1.test2").stream()
                    .findFirst()
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
            analyzer.getDefinitions("ai.brokk.Foo.Foo").stream()
                    .findFirst()
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
            analyzer.getDefinitions("ai.brokk.Foo.Foo").stream()
                    .findFirst()
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
            analyzer.getDefinitions("ai.brokk.GLOBAL_VAR").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.GLOBAL_VAR", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("GLOBAL_VAR", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'GLOBAL_VAR'!"));
            analyzer.getDefinitions("ai.brokk.GLOBAL_VAL").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.GLOBAL_VAL", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("GLOBAL_VAL", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'GLOBAL_VAL'!"));

            analyzer.getDefinitions("ai.brokk.Foo.Field1").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Foo.Field1", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Foo.Field1", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Foo.Field1'!"));
            analyzer.getDefinitions("ai.brokk.Foo.Field2").stream()
                    .findFirst()
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
    public void testFileSummaryNoSemicolonsAfterImports() throws IOException {
        // Verify that Scala file summaries do not have semicolons after imports
        try (var testProject = InlineTestProjectCreator.code(
                        """
                                package ai.brokk

                                import foo.bar

                                class Foo()
                                """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = new ProjectFile(testProject.getRoot(), "ai/brokk/Foo.scala");

            // Verify import statements do not have semicolons in Scala
            var importStatements = analyzer.importStatementsOf(file);
            assertFalse(importStatements.isEmpty(), "Should have import statements");
            for (String importStmt : importStatements) {
                assertFalse(
                        importStmt.strip().endsWith(";"),
                        "Scala import should not end with semicolon, but got: " + importStmt);
            }

            // Also verify that the class skeleton doesn't have errant semicolons
            var skeletons = analyzer.getSkeletons(file);
            var classSkeleton = skeletons.entrySet().stream()
                    .filter(e -> e.getKey().isClass() && e.getKey().fqName().equals("ai.brokk.Foo"))
                    .map(Map.Entry::getValue)
                    .findFirst();
            assertTrue(classSkeleton.isPresent(), "Should have a class skeleton for Foo");
            // Class definition line should not have semicolons (Scala style)
            String firstLine = classSkeleton.get().lines().findFirst().orElse("");
            assertFalse(
                    firstLine.endsWith(";"),
                    "Scala class declaration should not end with semicolon, but got: " + firstLine);
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
            analyzer.getDefinitions("ai.brokk.Colors.BLUE").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Colors.BLUE", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Colors.BLUE", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Colors.BLUE'!"));
            analyzer.getDefinitions("ai.brokk.Colors.GREEN").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Colors.GREEN", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Colors.GREEN", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Colors.GREEN'!"));

            analyzer.getDefinitions("ai.brokk.Sports.SOCCER").stream()
                    .findFirst()
                    .ifPresentOrElse(
                            cu -> {
                                assertTrue(cu.isField());
                                assertEquals("ai.brokk.Sports.SOCCER", cu.fqName());
                                assertEquals("ai.brokk", cu.packageName());
                                assertEquals("Sports.SOCCER", cu.shortName());
                            },
                            () -> fail("Could not find code unit 'Sports.SOCCER'!"));
            analyzer.getDefinitions("ai.brokk.Sports.RUGBY").stream()
                    .findFirst()
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

    @Test
    public void testMethodNameCanCollideWithConstructorName() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                        package ai.brokk

                        class Foo {
                          def Foo(): Int = 1
                        }
                        """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);

            // Class should be resolved
            var classes = analyzer.getDefinitions("ai.brokk.Foo");
            assertEquals(1, classes.size());
            assertTrue(classes.iterator().next().isClass());

            // Method/Constructor collision:
            // ScalaAnalyzer.createCodeUnit normalizes both primary constructors and methods named after the class to
            // Foo.Foo.
            var methods = analyzer.getDefinitions("ai.brokk.Foo.Foo");

            // We assert that the size is 1 to document that the analyzer currently collapses these definitions
            // due to naming collisions and lacks the AST context in getDefinitions to distinguish them.
            assertEquals(
                    1,
                    methods.size(),
                    "Expected 1 definition for Foo.Foo due to naming collision between constructor and method in Scala normalization");

            assertTrue(methods.iterator().next().isFunction());
        }
    }

    @Test
    public void testMultiAssignmentFieldSignatures() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
                        package ai.brokk

                        class Foo {
                          var x, y: Int = 1
                          val a, b = "test"
                        }
                        """,
                        "ai/brokk/Foo.scala")
                .build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = new ProjectFile(testProject.getRoot(), "ai/brokk/Foo.scala");

            var xUnit = new CodeUnit(file, CodeUnitType.FIELD, "ai.brokk", "Foo.x");
            var yUnit = new CodeUnit(file, CodeUnitType.FIELD, "ai.brokk", "Foo.y");
            var aUnit = new CodeUnit(file, CodeUnitType.FIELD, "ai.brokk", "Foo.a");
            var bUnit = new CodeUnit(file, CodeUnitType.FIELD, "ai.brokk", "Foo.b");

            assertCodeEquals("var x: Int = 1", analyzer.getSkeleton(xUnit).get());
            assertCodeEquals("var y: Int = 1", analyzer.getSkeleton(yUnit).get());
            assertCodeEquals("val a = \"test\"", analyzer.getSkeleton(aUnit).get());
            assertCodeEquals("val b = \"test\"", analyzer.getSkeleton(bUnit).get());
        }
    }

    @Test
    @Disabled("Pending TS tree inspection")
    public void testComplexFieldInitializerIsOmitted() throws IOException {
        String code =
                """
                package ai.brokk

                class ComplexField {
                  val obj = new Object()
                }
                """;
        try (var testProject = InlineTestProjectCreator.code(code, "ai/brokk/ComplexField.scala").build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var file = new ProjectFile(testProject.getRoot(), "ai/brokk/ComplexField.scala");

            var cu = new CodeUnit(file, CodeUnitType.FIELD, "ai.brokk", "ComplexField.obj");
            var skeleton = analyzer.getSkeleton(cu);
            assertTrue(skeleton.isPresent());
            // The object creation should be omitted, leaving only the declaration
            assertCodeEquals("val obj", skeleton.get());
        }
    }
}
