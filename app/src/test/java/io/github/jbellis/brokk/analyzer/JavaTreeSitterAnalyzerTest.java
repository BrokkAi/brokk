package io.github.jbellis.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.testutil.TestProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaTreeSitterAnalyzerTest {

    private static final Logger logger = LoggerFactory.getLogger(JavaTreeSitterAnalyzerTest.class);

    @Nullable
    private static JavaTreeSitterAnalyzer analyzer;

    @Nullable
    private static TestProject testProject;

    @BeforeAll
    public static void setup() throws IOException {
        final var testPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(testPath), "Test resource directory 'testcode-java' not found.");
        testProject = new TestProject(testPath, Language.JAVA);
        logger.debug(
                "Setting up analyzer with test code from {}",
                testPath.toAbsolutePath().normalize());
        analyzer = new JavaTreeSitterAnalyzer(testProject);
    }

    @AfterAll
    public static void teardown() {
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    public void isEmptyTest() {
        // setup() should feed code into the server, and this method should behave as expected
        assertFalse(analyzer.isEmpty());
    }

    @Test
    public void extractMethodSource() {
        final var sourceOpt = analyzer.getMethodSource("A.method2");
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get().trim().stripIndent();
        final String expected =
                """
                public String method2(String input) {
                        return "prefix_" + input;
                    }

                public String method2(String input, int otherInput) {
                        // overload of method2
                        return "prefix_" + input + " " + otherInput;
                    }
                """
                        .trim()
                        .stripIndent();

        assertEquals(expected, source);
    }

    @Test
    public void extractMethodSourceNested() {
        final var sourceOpt = analyzer.getMethodSource("A$AInner$AInnerInner.method7");
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get().trim().stripIndent();

        final var expected =
                """
                public void method7() {
                                System.out.println("hello");
                            }
                """
                        .trim()
                        .stripIndent();

        assertEquals(expected, source);
    }

    @Test
    public void extractMethodSourceConstructor() {
        final var sourceOpt = analyzer.getMethodSource("B.B"); // TODO: Should we handle <init>?
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get().trim().stripIndent();

        final var expected =
                """
                        public B() {
                                System.out.println("B constructor");
                            }
                        """
                        .trim()
                        .stripIndent();

        assertEquals(expected, source);
    }

    @Test
    public void getClassSourceTest() {
        final var source = analyzer.getClassSource("A");
        assertNotNull(source);
        // Verify the source contains class definition and methods
        assertTrue(source.contains("class A {"));
        assertTrue(source.contains("public void method1()"));
        assertTrue(source.contains("public String method2(String input)"));
    }

    @Test
    public void getClassSourceNestedTest() {
        final var maybeSource = analyzer.getClassSource("A$AInner");
        assertNotNull(maybeSource);
        final var source = maybeSource.stripIndent();
        // Verify the source contains inner class definition
        final var expected =
                """
                public class AInner {
                        public class AInnerInner {
                            public void method7() {
                                System.out.println("hello");
                            }
                        }
                    }
                """
                        .trim()
                        .stripIndent();
        assertEquals(expected, source);
    }

    @Test
    public void getClassSourceTwiceNestedTest() {
        final var maybeSource = analyzer.getClassSource("A$AInner$AInnerInner");
        assertNotNull(maybeSource);
        final var source = maybeSource.stripIndent();
        // Verify the source contains inner class definition
        final var expected =
                """
                        public class AInnerInner {
                            public void method7() {
                                System.out.println("hello");
                            }
                        }
                """
                        .trim()
                        .stripIndent();
        assertEquals(expected, source);
    }

    @Test
    public void getClassSourceNotFoundTest() {
        assertThrows(SymbolNotFoundException.class, () -> analyzer.getClassSource("A$NonExistent"));
    }

    @Test
    public void getClassSourceNonexistentTest() {
        assertThrows(SymbolNotFoundException.class, () -> analyzer.getClassSource("NonExistentClass"));
    }

    @Test
    public void getSkeletonTestA() {
        final var skeletonOpt = analyzer.getSkeleton("A");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get().trim().stripIndent();

        final var expected =
                """
                public class A {
                  void method1()
                  String method2(String input)
                  String method2(String input, int otherInput)
                  Function<Integer, Integer> method3()
                  int method4(double foo, Integer bar)
                  void method5()
                  void method6()
                  void run()
                  public class AInner {
                    public class AInnerInner {
                      void method7()
                    }
                  }
                  public static class AInnerStatic {
                  }
                  void usesInnerClass()
                }
                """
                        .trim()
                        .stripIndent();
        assertEquals(expected, skeleton);
    }

    @Test
    public void getSkeletonTestD() {
        final var skeletonOpt = analyzer.getSkeleton("D");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get().trim().stripIndent();

        final var expected =
                """
                public class D {
                  public static int field1;
                  private String field2;
                  void methodD1()
                  void methodD2()
                  private static class DSubStatic {
                  }
                  private class DSub {
                  }
                }
                """
                        .trim()
                        .stripIndent();
        assertEquals(expected, skeleton);
    }

    @Test
    public void getGetSkeletonHeaderTest() {
        final var skeletonOpt = analyzer.getSkeletonHeader("D");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get().trim().stripIndent();

        final var expected =
                """
                public class D {
                  public static int field1;
                  private String field2;
                  [...]
                }
                """
                        .trim()
                        .stripIndent();
        assertEquals(expected, skeleton);
    }

    @Test
    public void getAllClassesTest() {
        final var classes = analyzer.getAllDeclarations().stream()
                .map(CodeUnit::fqName)
                .sorted()
                .toList();
        final var expected = List.of(
                "A",
                "A$AInner",
                "A$AInner$AInnerInner",
                "A$AInnerStatic",
                "AnonymousUsage",
                "AnonymousUsage$NestedClass",
                "B",
                "BaseClass",
                "C",
                "C$Foo",
                "CamelClass",
                "CyclicMethods",
                "D",
                "D$DSub",
                "D$DSubStatic",
                "E",
                "F",
                "Interface",
                "MethodReturner",
                "UseE",
                "UsePackaged",
                "XExtendsY",
                "io.github.jbellis.brokk.Foo");
        assertEquals(expected, classes);
    }

    @Test
    public void getDeclarationsInFileTest() {
        final var maybeFile = analyzer.getFileFor("D");
        assertTrue(maybeFile.isPresent());
        final var file = maybeFile.get();
        final var classes = analyzer.getDeclarationsInFile(file);

        final var expected = Set.of(
                // Classes
                CodeUnit.cls(file, "", "D"),
                CodeUnit.cls(file, "", "D$DSub"),
                CodeUnit.cls(file, "", "D$DSubStatic"),
                // Methods
                CodeUnit.fn(file, "", "D.methodD1"),
                CodeUnit.fn(file, "", "D.methodD2"),
                // Fields
                CodeUnit.field(file, "", "D.field1"),
                CodeUnit.field(file, "", "D.field2"));
        assertEquals(expected, classes);
    }

    @Test
    public void declarationsInPackagedFileTest() {
        final var file = new ProjectFile(testProject.getRoot(), "Packaged.java");
        final var declarations = analyzer.getDeclarationsInFile(file);
        final var expected = Set.of(
                // Class
                CodeUnit.cls(file, "io.github.jbellis.brokk", "Foo"),
                // Method
                CodeUnit.fn(file, "io.github.jbellis.brokk", "Foo.bar")
                // No fields in Packaged.java
                );
        assertEquals(expected, declarations);
    }

    @Test
    public void testGetDefinitionForClass() {
        var classDDef = analyzer.getDefinition("D");
        assertTrue(classDDef.isPresent(), "Should find definition for class 'D'");
        assertEquals("D", classDDef.get().fqName());
        assertTrue(classDDef.get().isClass());
    }

    @Test
    public void testGetDefinitionForMethod() {
        var method1Def = analyzer.getDefinition("A.method1");
        assertTrue(method1Def.isPresent(), "Should find definition for method 'A.method1'");
        assertEquals("A.method1", method1Def.get().fqName());
        assertTrue(method1Def.get().isFunction());
    }

    @Test
    public void testGetDefinitionForField() {
        var field1Def = analyzer.getDefinition("D.field1");
        assertTrue(field1Def.isPresent(), "Should find definition for field 'D.field1'");
        assertEquals("D.field1", field1Def.get().fqName());
        assertFalse(field1Def.get().isClass());
        assertFalse(field1Def.get().isFunction());
    }

    @Test
    public void testGetDefinitionNonExistent() {
        var nonExistentDef = analyzer.getDefinition("NonExistentSymbol");
        assertFalse(nonExistentDef.isPresent(), "Should not find definition for non-existent symbol");
    }

    @Test
    public void getMembersInClassTest() {
        final var members = analyzer.getMembersInClass("D").stream().sorted().toList();
        final var maybeFile = analyzer.getFileFor("D");
        assertTrue(maybeFile.isPresent());
        final var file = maybeFile.get();

        final var expected = Stream.of(
                        // Methods
                        CodeUnit.fn(file, "", "D.methodD1"),
                        CodeUnit.fn(file, "", "D.methodD2"),
                        // Fields
                        CodeUnit.field(file, "", "D.field1"),
                        CodeUnit.field(file, "", "D.field2"),
                        // Classes
                        CodeUnit.cls(file, "", "D$DSubStatic"),
                        CodeUnit.cls(file, "", "D$DSub"))
                .sorted()
                .toList();
        assertEquals(expected, members);
    }

    @Test
    public void getDirectClassChildren() {
        final var maybeClassD = analyzer.getDefinition("D");
        assertTrue(maybeClassD.isPresent());
        final var maybeFile = analyzer.getFileFor("D");
        assertTrue(maybeFile.isPresent());

        final var children =
                analyzer.directChildren(maybeClassD.get()).stream().sorted().toList();
        final var file = maybeFile.get();

        final var expected = Stream.of(
                        // Classes
                        CodeUnit.cls(file, "", "D$DSub"),
                        CodeUnit.cls(file, "", "D$DSubStatic"),
                        // Methods
                        CodeUnit.fn(file, "", "D.methodD1"),
                        CodeUnit.fn(file, "", "D.methodD2"),
                        // Fields
                        CodeUnit.field(file, "", "D.field1"),
                        CodeUnit.field(file, "", "D.field2"))
                .sorted()
                .toList();
        assertEquals(expected, children);
    }
}
