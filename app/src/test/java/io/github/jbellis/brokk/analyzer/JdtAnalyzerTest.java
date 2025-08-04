package io.github.jbellis.brokk.analyzer;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class JdtAnalyzerTest {

    private final static Logger logger = LoggerFactory.getLogger(JdtAnalyzerTest.class);

    @Nullable
    private static JdtAnalyzer analyzer = null;

    @BeforeAll
    public static void setup() throws IOException {
        final var testPath = Path.of("src/test/resources/testcode-java");
//        final var testPath = Path.of("/Users/dave/Workspace/BrokkAi/brokk");
        logger.debug("Setting up analyzer with test code from {}", testPath.toAbsolutePath().normalize());
        analyzer = new JdtAnalyzer(testPath, new HashSet<>());
    }

    @AfterAll
    public static void teardown() {
        if (analyzer != null) {
            analyzer.close();
        }
    }

    @Test
    public void isEmptyTest() {
        // setup() should feed code into the server, and this method should behave as expected
        assertFalse(analyzer.isEmpty());
    }

    @Test
    public void isClassInProjectTest() {
        assert (analyzer.isClassInProject("A"));

        assert (!analyzer.isClassInProject("java.nio.filename.Path"));
        assert (!analyzer.isClassInProject("org.foo.Bar"));
    }

    @Test
    public void extractMethodSource() {
        final var sourceOpt = analyzer.getMethodSource("A.method2");
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get().trim().stripIndent();
        final String expected = """
                public String method2(String input) {
                        return "prefix_" + input;
                    }
                
                public String method2(String input, int otherInput) {
                        // overload of method2
                        return "prefix_" + input + " " + otherInput;
                    }
                """.trim().stripIndent();

        assertEquals(expected, source);
    }

    @Test
    public void extractMethodSourceNested() {
        final var sourceOpt = analyzer.getMethodSource("A$AInner$AInnerInner.method7");
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get().trim().stripIndent();

        final var expected = """
                public void method7() {
                                System.out.println("hello");
                            }
                """.trim().stripIndent();

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
                        """.trim().stripIndent();

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
        final var expected = """
                public class AInner {
                        public class AInnerInner {
                            public void method7() {
                                System.out.println("hello");
                            }
                        }
                    }
                """.trim().stripIndent();
        assertEquals(expected, source);
    }

    @Test
    public void getClassSourceTwiceNestedTest() {
        final var maybeSource = analyzer.getClassSource("A$AInner$AInnerInner");
        assertNotNull(maybeSource);
        final var source = maybeSource.stripIndent();
        // Verify the source contains inner class definition
        final var expected = """
                        public class AInnerInner {
                            public void method7() {
                                System.out.println("hello");
                            }
                        }
                """.trim().stripIndent();
        assertEquals(expected, source);
    }

    @Test
    public void getClassSourceFallbackTest() {
        final var maybeSource = analyzer.getClassSource("A$NonExistent");
        assertNotNull(maybeSource);
        final var source = maybeSource.stripIndent();
        // Verify that the class fallback works if subclasses (or anonymous classes) aren't resolved
        assertTrue(source.contains("class A {"));
        assertTrue(source.contains("public void method1()"));
        assertTrue(source.contains("public String method2(String input)"));
    }

    @Test
    public void getClassSourceNonexistentTest() {
        final var maybeSource = analyzer.getClassSource("NonExistentClass");
        assertNull(maybeSource);
    }

    @Test
    public void sanitizeTypeTest() {
        // Simple types
        assertEquals("String", analyzer.sanitizeType("java.lang.String"));
        assertEquals("String[]", analyzer.sanitizeType("java.lang.String[]"));

        // Generic types
        assertEquals(
                "Function<Integer, Integer>",
                analyzer.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>")
        );

        // Nested generic types
        assertEquals(
                "Map<String, List<Integer>>",
                analyzer.sanitizeType("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>")
        );

        // Method return type with generics
        assertEquals(
                "Function<Integer, Integer>",
                analyzer.sanitizeType("java.util.function.Function<java.lang.Integer, java.lang.Integer>")
        );
    }

    @Test
    public void getSkeletonTestA() {
        final var skeletonOpt = analyzer.getSkeleton("A");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get().trim().stripIndent();

        final var expected = """
                public class A {
                  public A() {...}
                  public void method1() {...}
                  public String method2(String input) {...}
                  public String method2(String input, int otherInput) {...}
                  public Function<Integer, Integer> method3() {...}
                  public static int method4(double foo, Integer bar) {...}
                  public void method5() {...}
                  public void method6() {...}
                  public class AInner {
                    public AInner() {...}
                    public class AInnerInner {
                      public AInnerInner() {...}
                      public void method7() {...}
                    }
                  }
                  public static class AInnerStatic {
                    public AInnerStatic() {...}
                  }
                }
                """.trim().stripIndent();
        assertEquals(expected, skeleton);
    }

    @Test
    public void getSkeletonTestD() {
        final var skeletonOpt = analyzer.getSkeleton("D");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get().trim().stripIndent();

        final var expected = """
                public class D {
                  public D() {...}
                  public int field1;
                  private String field2;
                  public void methodD1() {...}
                  public void methodD2() {...}
                  private static class DSubStatic {
                    public DSubStatic() {...}
                  }
                  private class DSub {
                    public DSub() {...}
                  }
                }
                """.trim().stripIndent();
        assertEquals(expected, skeleton);
    }

    @Test
    public void getGetSkeletonHeaderTest() {
        final var skeletonOpt = analyzer.getSkeletonHeader("D");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get().trim().stripIndent();

        final var expected = """
                public class D {
                  public int field1;
                  private String field2;
                  [... methods not shown ...]
                }
                """.trim().stripIndent();
        assertEquals(expected, skeleton);
    }

    @Test
    public void getAllClassesTest() {
        final var classes = analyzer.getAllDeclarations().stream().map(CodeUnit::fqName).sorted().toList();
        final var expected = List.of("A", "A.AInner", "A.AInner.AInnerInner", "A.AInnerStatic",
                "AnonymousUsage", "AnonymousUsage.NestedClass", "B", "BaseClass", "C", "C.Foo", "CamelClass",
                "CyclicMethods", "D", "D.DSub", "D.DSubStatic", "E", "F", "Foo", "Interface", "MethodReturner",
                "UseE", "UsePackaged", "XExtendsY");
        assertEquals(expected, classes);
    }

    @Test
    public void getCallgraphToTest() {
        final var callgraph = analyzer.getCallgraphTo("A.method1", 5);

        // Expect A.method1 -> [B.callsIntoA, D.methodD1]
        assertTrue(callgraph.containsKey("A.method1"), "Should contain A.method1 as a key");

        final var callers =
                callgraph.getOrDefault("A.method1", Collections.emptyList())
                        .stream()
                        .map(site -> site.target().fqName())
                        .collect(Collectors.toSet());
        assertEquals(Set.of("B.callsIntoA", "D.methodD1"), callers);
    }

    @Test
    public void getCallgraphFromTest() {
        final var callgraph = analyzer.getCallgraphFrom("B.callsIntoA", 5);

        // Expect B.callsIntoA -> [A.method1, A.method2]
        assertTrue(callgraph.containsKey("B.callsIntoA"), "Should contain B.callsIntoA as a key");

        final var callees =
                callgraph.getOrDefault("B.callsIntoA", Collections.emptyList())
                        .stream()
                        .map(site -> site.target().fqName())
                        .collect(Collectors.toSet());
        assertTrue(callees.contains("A.method1"), "Should call A.method1");
        assertTrue(callees.contains("A.method2"), "Should call A.method2");
    }

    @Test
    public void declarationsInPackagedFileTest() {
        final var file = new ProjectFile(analyzer.getProjectRoot(), "Packaged.java");
        final var declarations = analyzer.getDeclarationsInFile(file);
        final var expected = Set.of(
                // Class
                CodeUnit.cls(file, "io.github.jbellis.brokk", "Foo"),
                // Method
                CodeUnit.fn(file, "io.github.jbellis.brokk", "Foo.bar"),
                // Constructor
                CodeUnit.fn(file, "io.github.jbellis.brokk", "Foo.<init>")
                // No fields in Packaged.java
        );
        assertEquals(expected, declarations)
    }

    @Test
    public void getUsesMethodExistingTest() {
        final var symbol = "A.method2";
        final var usages = analyzer.getUses(symbol);

        // Expect references in B.callsIntoA() because it calls a.method2("test")
        final var actualMethodRefs = usages.stream().filter(CodeUnit::isFunction).map(CodeUnit::fqName).collect(Collectors.toSet());
        final var actualRefs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(Set.of("B.callsIntoA", "AnonymousUsage$1.run"), actualRefs);
    }

    @Test
    public void getUsesMethodNonexistentTest() {
        final var symbol = "A.noSuchMethod:java.lang.String()";
        final var ex = assertThrows(IllegalArgumentException.class, () -> analyzer.getUses(symbol));
        assertTrue(ex.getMessage().contains("not found as a method, field, or class"));
    }

    @Test
    public void getUsesFieldExistingTest() {
        final var symbol = "D.field1"; // fully qualified field name
        final var usages = analyzer.getUses(symbol);

        final var actualRefs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());
        assertEquals(Set.of("D.methodD2", "E.dMethod"), actualRefs);
    }

    @Test
    public void getUsesFieldNonexistentTest() {
        final var symbol = "D.notAField";
        final var ex = assertThrows(IllegalArgumentException.class, () -> analyzer.getUses(symbol));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    public void getUsesClassBasicTest() {
        // “A” has no static members, but it is used as a local type in B.callsIntoA and D.methodD1
        final var symbol = "A";
        final var usages = analyzer.getUses(symbol);

        // References to A include both function references and local variable references
        final var foundRefs = usages.stream().map(CodeUnit::fqName).collect(Collectors.toSet());

        // Get the usages of each type
        final var functionRefs = usages.stream().filter(CodeUnit::isFunction).map(CodeUnit::fqName).collect(Collectors.toSet());
        final var fieldRefs = usages.stream().filter(cu -> !cu.isFunction() && !cu.isClass()).map(CodeUnit::fqName).collect(Collectors.toSet());
        final var classRefs = usages.stream().filter(CodeUnit::isClass).map(CodeUnit::fqName).collect(Collectors.toSet());

        // There should be function usages in these methods
        assertEquals(Set.of("B.callsIntoA", "D.methodD1", "AnonymousUsage$1.run"), functionRefs);

        // Ensure we have the correct usage types with our refactored implementation
        final var all = new HashSet<String>();
        all.addAll(functionRefs);
        all.addAll(fieldRefs);
        all.addAll(classRefs);
        assertEquals(foundRefs, all);
    }

    @Test
    public void getUsesClassNonexistentTest() {
        final var symbol = "NoSuchClass";
        final var ex = assertThrows(IllegalArgumentException.class, () -> analyzer.getUses(symbol));
        assertTrue(
                ex.getMessage().contains("Symbol 'NoSuchClass' (resolved: 'NoSuchClass') not found as a method, field, or class")
        );
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
                CodeUnit.cls(file, "", "D.DSub"),
                CodeUnit.cls(file, "", "D.DSubStatic"),
                // Methods
                CodeUnit.fn(file, "", "D.methodD1"),
                CodeUnit.fn(file, "", "D.methodD2"),
                // Fields
                CodeUnit.field(file, "", "D.field1"),
                CodeUnit.field(file, "", "D.field2")
        );
        assertEquals(expected, classes);
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
    @Disabled("JDT LSP does not index field symbols")
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

}
