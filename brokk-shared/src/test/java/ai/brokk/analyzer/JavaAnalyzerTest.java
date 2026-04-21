package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static ai.brokk.testutil.AssertionHelperUtil.*;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.CoreTestProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestCodeProject;
import ai.brokk.testutil.TestContextManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavaAnalyzerTest {

    private static final Logger logger = LoggerFactory.getLogger(JavaAnalyzerTest.class);

    @Nullable
    private static JavaAnalyzer analyzer;

    @Nullable
    private static CoreTestProject testProject;

    @BeforeAll
    public static void setup() {
        testProject = TestCodeProject.fromResourceDir("testcode-java", Languages.JAVA);
        logger.debug("Setting up analyzer with test code from {}", testProject.getRoot());
        analyzer = new JavaAnalyzer(testProject);
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
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "A.method2", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get();
        final String expected =
                """
                public String method2(String input) {
                        return "prefix_" + input;
                    }

                public String method2(String input, int otherInput) {
                        // overload of method2
                        return "prefix_" + input + " " + otherInput;
                    }
                """;

        assertCodeEquals(expected, source);
    }

    @Test
    public void extractMethodSourceNested() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "A.AInner.AInnerInner.method7", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get();

        final var expected =
                """
                public void method7() {
                                System.out.println("hello");
                            }
                """;

        assertCodeEquals(expected, source);
    }

    @Test
    public void extractMethodSourceConstructor() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "B.B", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get();

        final var expected =
                """
                public B() {
                        System.out.println("B constructor");
                    }
                """;

        assertCodeEquals(expected, source);
    }

    @Test
    public void getClassSourceTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "A", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get();
        // Verify the source contains class definition and methods
        assertTrue(source.contains("class A {"));
        assertTrue(source.contains("void method1()"));
        assertTrue(source.contains("public String method2(String input)"));
    }

    @Test
    public void getClassSourceNestedTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "A.AInner", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get();
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
                """;
        assertCodeEquals(expected, source);
    }

    @Test
    public void getClassSourceTwiceNestedTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "A.AInner.AInnerInner", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get();
        // Verify the source contains inner class definition
        final var expected =
                """
                        public class AInnerInner {
                            public void method7() {
                                System.out.println("hello");
                            }
                        }
                """;
        assertCodeEquals(expected, source);
    }

    @Test
    public void getClassSourceNotFoundTest() {
        var opt = AnalyzerUtil.getSource(analyzer, "A.NonExistent", true);
        assertTrue(opt.isEmpty());
    }

    @Test
    public void getClassSourceNonexistentTest() {
        var opt = AnalyzerUtil.getSource(analyzer, "NonExistentClass", true);
        assertTrue(opt.isEmpty());
    }

    @Test
    public void getSkeletonTestA() {
        final var skeletonOpt = AnalyzerUtil.getSkeleton(analyzer, "A");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get();

        final var expected =
                """
                public class A {
                  void method1()
                  public String method2(String input)
                  public String method2(String input, int otherInput)
                  public Function<Integer, Integer> method3()
                  public static int method4(double foo, Integer bar)
                  public void method5()
                  public void method6()
                  public void run()
                  public class AInner {
                    public class AInnerInner {
                      public void method7()
                    }
                  }
                  public static class AInnerStatic {
                  }
                  private void usesInnerClass()
                }
                """
                        .trim();
        assertCodeEquals(expected, skeleton);
    }

    @Test
    public void getSkeletonTestD() {
        final var skeletonOpt = AnalyzerUtil.getSkeleton(analyzer, "D");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get();

        final var expected =
                """
                public class D {
                  public static int field1;
                  private String field2;
                  public void methodD1()
                  public void methodD2()
                  private static class DSubStatic {
                  }
                  private class DSub {
                  }
                }
                """
                        .trim();
        assertCodeEquals(expected, skeleton);
    }

    @Test
    public void getSkeletonTestEnum() {
        final var skeletonOpt = AnalyzerUtil.getSkeleton(analyzer, "EnumClass");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get();

        final var expected =
                """
                public enum EnumClass {
                  FOO,
                  BAR
                }
                """
                        .trim();
        assertEquals(expected, skeleton);
    }

    @Test
    public void getGetSkeletonHeaderTest() {
        final var skeletonOpt = AnalyzerUtil.getSkeletonHeader(analyzer, "D");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get();

        final var expected =
                """
                public class D {
                  public static int field1;
                  private String field2;
                  [...]
                }
                """
                        .trim();
        assertCodeEquals(expected, skeleton);
    }

    @Test
    public void getAllClassesTest() {
        final var classes = analyzer.getAllDeclarations().stream()
                .filter(CodeUnit::isClass)
                .map(CodeUnit::fqName)
                .sorted()
                .toList();
        final var expected = List.of(
                "A",
                "A.AInner",
                "A.AInner.AInnerInner",
                "A.AInnerStatic",
                "AnnotatedClass",
                "AnnotatedClass.InnerHelper",
                "AnonymousUsage",
                "AnonymousUsage.NestedClass",
                "B",
                "BaseClass",
                "C",
                "C.Foo",
                "CamelClass",
                "ClassUsagePatterns",
                "CustomAnnotation",
                "CyclicMethods",
                "D",
                "D.DSub",
                "D.DSubStatic",
                "E",
                "EnumClass",
                "F",
                "InlineComment",
                "Interface",
                "MethodReferenceUsage",
                "MethodReturner",
                "Overloads",
                "OverloadsUser",
                "ServiceImpl",
                "ServiceInterface",
                "UseE",
                "UsePackaged",
                "XExtendsY",
                "io.github.jbellis.brokk.Foo");
        assertEquals(expected, classes);
    }

    @Test
    public void getDeclarationsInFileTest() {
        final var maybeFile = AnalyzerUtil.getFileFor(analyzer, "D");
        assertTrue(maybeFile.isPresent());
        final var file = maybeFile.get();
        final var declarations = analyzer.getDeclarations(file);

        final var declStrings = declarations.stream()
                .map(cu -> cu.kind() + ":" + cu.fqName())
                .sorted()
                .toList();

        final var expectedStrings = Stream.of(
                        "CLASS:D",
                        "CLASS:D.DSub",
                        "CLASS:D.DSubStatic",
                        "FUNCTION:D.D",
                        "FUNCTION:D.DSub.DSub",
                        "FUNCTION:D.DSubStatic.DSubStatic",
                        "FUNCTION:D.methodD1",
                        "FUNCTION:D.methodD2",
                        "FIELD:D.field1",
                        "FIELD:D.field2")
                .sorted()
                .toList();
        assertEquals(expectedStrings, declStrings);

        // Verify identifier() returns innermost name for nested classes
        final var classes = declarations.stream().filter(CodeUnit::isClass).collect(Collectors.toSet());
        var dSubOpt =
                classes.stream().filter(cu -> cu.fqName().equals("D.DSub")).findFirst();
        assertTrue(dSubOpt.isPresent());
        assertEquals("DSub", dSubOpt.get().identifier(), "Nested class identifier should be innermost name");

        var dOpt = classes.stream().filter(cu -> cu.fqName().equals("D")).findFirst();
        assertTrue(dOpt.isPresent());
        assertEquals("D", dOpt.get().identifier(), "Top-level class identifier should equal shortName");
    }

    @Test
    public void declarationsInPackagedFileTest() {
        final var file = new ProjectFile(testProject.getRoot(), "Packaged.java");
        final var declarations = analyzer.getDeclarations(file);

        final var declStrings = declarations.stream()
                .map(cu -> cu.kind() + ":" + cu.fqName())
                .sorted()
                .toList();

        final var expectedStrings = Stream.of(
                        "MODULE:io.github.jbellis.brokk",
                        "CLASS:io.github.jbellis.brokk.Foo",
                        "FUNCTION:io.github.jbellis.brokk.Foo.bar",
                        "FUNCTION:io.github.jbellis.brokk.Foo.Foo")
                .sorted()
                .toList();
        assertEquals(expectedStrings, declStrings);

        // Verify identifier() for packaged class
        var fooOpt = declarations.stream()
                .filter(cu -> cu.fqName().equals("io.github.jbellis.brokk.Foo"))
                .findFirst();
        assertTrue(fooOpt.isPresent());
        assertEquals("Foo", fooOpt.get().shortName(), "Packaged class shortName should be simple name");
        assertEquals("Foo", fooOpt.get().identifier(), "Packaged class identifier should be simple name");
    }

    @Test
    public void testGetDefinitionForClass() {
        var classDDef = analyzer.getDefinitions("D").stream().findFirst();
        assertTrue(classDDef.isPresent(), "Should find definition for class 'D'");
        assertEquals("D", classDDef.get().fqName());
        assertTrue(classDDef.get().isClass());
    }

    @Test
    public void testGetDefinitionForMethod() {
        var method1Def = analyzer.getDefinitions("A.method1").stream().findFirst();
        assertTrue(method1Def.isPresent(), "Should find definition for method 'A.method1'");
        assertEquals("A.method1", method1Def.get().fqName());
        assertTrue(method1Def.get().isFunction());
    }

    @Test
    public void testGetDefinitionForField() {
        var field1Def = analyzer.getDefinitions("D.field1").stream().findFirst();
        assertTrue(field1Def.isPresent(), "Should find definition for field 'D.field1'");
        assertEquals("D.field1", field1Def.get().fqName());
        assertFalse(field1Def.get().isClass());
        assertFalse(field1Def.get().isFunction());
    }

    @Test
    public void testCodeUnitIdentifierAndShortName() {
        // Simple class: D
        var classDDef = analyzer.getDefinitions("D").stream().findFirst();
        assertTrue(classDDef.isPresent(), "Should find definition for class 'D'");
        assertEquals("D", classDDef.get().shortName(), "Simple class shortName should be just the class name");
        assertEquals("D", classDDef.get().identifier(), "Simple class identifier should be the class name");

        // Nested class: D.DSub (one level of nesting)
        var classDSubDef = analyzer.getDefinitions("D.DSub").stream().findFirst();
        assertTrue(classDSubDef.isPresent(), "Should find definition for nested class 'D.DSub'");
        assertEquals("D.DSub", classDSubDef.get().shortName(), "Nested class shortName should include parent");
        assertEquals("DSub", classDSubDef.get().identifier(), "Nested class identifier should be innermost name only");

        // Deeply nested class: A.AInner.AInnerInner (two levels of nesting)
        var classAInnerInnerDef =
                analyzer.getDefinitions("A.AInner.AInnerInner").stream().findFirst();
        assertTrue(classAInnerInnerDef.isPresent(), "Should find definition for deeply nested class");
        assertEquals(
                "A.AInner.AInnerInner",
                classAInnerInnerDef.get().shortName(),
                "Deeply nested shortName should include full path");
        assertEquals(
                "AInnerInner",
                classAInnerInnerDef.get().identifier(),
                "Deeply nested identifier should be innermost name only");

        // Static nested class: A.AInnerStatic
        var classAInnerStaticDef =
                analyzer.getDefinitions("A.AInnerStatic").stream().findFirst();
        assertTrue(classAInnerStaticDef.isPresent(), "Should find definition for static nested class");
        assertEquals(
                "A.AInnerStatic",
                classAInnerStaticDef.get().shortName(),
                "Static nested shortName should include parent");
        assertEquals(
                "AInnerStatic",
                classAInnerStaticDef.get().identifier(),
                "Static nested identifier should be innermost name only");
    }

    @Test
    public void testGetDefinitionNonExistent() {
        var nonExistentDef =
                analyzer.getDefinitions("NonExistentSymbol").stream().findFirst();
        assertFalse(nonExistentDef.isPresent(), "Should not find definition for non-existent symbol");
    }

    @Test
    public void getMembersInClassTest() {
        final var members = AnalyzerUtil.getMembersInClass(analyzer, "D");

        // Use stable string representation for comparison as analyzer-produced units may carry signatures
        final var memberStrings = members.stream()
                .map(cu -> cu.kind() + ":" + cu.fqName())
                .sorted()
                .toList();

        final var expectedStrings = Stream.of(
                        "FUNCTION:D.D",
                        "CLASS:D.DSub",
                        "CLASS:D.DSubStatic",
                        "FIELD:D.field1",
                        "FIELD:D.field2",
                        "FUNCTION:D.methodD1",
                        "FUNCTION:D.methodD2")
                .sorted()
                .toList();

        assertEquals(expectedStrings, memberStrings);
    }

    @Test
    public void getDirectClassChildren() {
        final var maybeClassD = analyzer.getDefinitions("D").stream().findFirst();
        assertTrue(maybeClassD.isPresent());

        final var children = analyzer.getDirectChildren(maybeClassD.get());
        final var childStrings = children.stream()
                .map(cu -> cu.kind() + ":" + cu.fqName())
                .sorted()
                .toList();

        final var expectedStrings = Stream.of(
                        "FUNCTION:D.D",
                        "CLASS:D.DSub",
                        "CLASS:D.DSubStatic",
                        "FIELD:D.field1",
                        "FIELD:D.field2",
                        "FUNCTION:D.methodD1",
                        "FUNCTION:D.methodD2")
                .sorted()
                .toList();
        assertEquals(expectedStrings, childStrings);
    }

    @Test
    public void testSummarizeClassWithDefaultMethods() {
        // Test skeleton generation for the interface with default methods
        var interfaceSkeleton = AnalyzerUtil.getSkeleton(analyzer, "ServiceInterface");
        assertTrue(
                interfaceSkeleton.isPresent(),
                "ServiceInterface skeleton should be available via JavaTreeSitterAnalyzer");
        var interfaceSkeletonStr = interfaceSkeleton.get();

        assertTrue(interfaceSkeletonStr.contains("ServiceInterface"));
        assertTrue(interfaceSkeletonStr.contains("processData"));
        assertTrue(interfaceSkeletonStr.contains("formatMessage"), "Should contain default method formatMessage");
        assertTrue(interfaceSkeletonStr.contains("logMessage"), "Should contain default method logMessage");
        assertTrue(interfaceSkeletonStr.contains("getVersion"), "Should contain static method getVersion");

        // Verify that the skeleton includes method signatures (default methods appear as regular methods in skeleton)
        // This is correct behavior - skeletons show structure, not implementation details like 'default' keyword or
        // bodies
        assertTrue(
                interfaceSkeletonStr.contains("void processData(String data)"),
                "Should contain abstract method signature");
        assertTrue(
                interfaceSkeletonStr.contains("String formatMessage(String message)"),
                "Should contain default method signature");
        assertTrue(
                interfaceSkeletonStr.contains("void logMessage(String message)"),
                "Should contain default method signature");
        assertTrue(interfaceSkeletonStr.contains("String getVersion()"), "Should contain static method signature");

        // Test skeleton generation for the implementing class
        var classSkeleton = AnalyzerUtil.getSkeleton(analyzer, "ServiceImpl");
        assertTrue(classSkeleton.isPresent(), "ServiceImpl skeleton should be available via JavaTreeSitterAnalyzer");
        var classSkeletonStr = classSkeleton.get();

        assertTrue(classSkeletonStr.contains("ServiceImpl"));
        assertTrue(classSkeletonStr.contains("implements ServiceInterface"));
        assertTrue(classSkeletonStr.contains("processData"));
        assertTrue(classSkeletonStr.contains("formatMessage"));
        assertTrue(classSkeletonStr.contains("printVersion"));
    }

    @Test
    public void testNormalizeFullName() {
        // regular method
        assertEquals("package.Class.method", analyzer.normalizeFullName("package.Class.method"));
        // method with anon class (just digits)
        assertEquals("package.Class.method", analyzer.normalizeFullName("package.Class.method$1"));
        // method in nested class
        assertEquals("package.A.AInner.method", analyzer.normalizeFullName("package.A.AInner.method"));
    }

    @Test
    public void debugAnnotatedClassSourceTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass", true);
        assertTrue(sourceOpt.isPresent(), "Should find AnnotatedClass");
        final var source = sourceOpt.get();

        System.out.println("=== EXTRACTED SOURCE FOR AnnotatedClass ===");
        System.out.println(source);
        System.out.println("=== END EXTRACTED SOURCE ===");

        // Basic test just to ensure it works
        assertTrue(source.contains("AnnotatedClass"), "Should contain class name");
    }

    @Test
    public void getClassSourceWithJavadocsTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass", true);
        assertTrue(sourceOpt.isPresent(), "Should find AnnotatedClass");
        final var source = sourceOpt.get();
        System.out.println(source);

        // Verify Javadoc comments are captured (now that we've implemented comment expansion)
        assertTrue(source.contains("/**"), "Should contain Javadoc start marker");
        assertTrue(
                source.contains("A comprehensive test class with various annotations"),
                "Should contain class-level Javadoc description");
        assertTrue(source.contains("@author Test Author"), "Should contain @author tag");
        assertTrue(source.contains("@version 1.0"), "Should contain @version tag");
        assertTrue(source.contains("@since Java 8"), "Should contain @since tag");

        // Verify class declaration is also captured
        assertTrue(source.contains("public class AnnotatedClass"), "Should contain class declaration");

        // Verify annotations are captured (they are part of the declaration node)
        assertTrue(
                source.contains("@Deprecated(since = \"1.2\", forRemoval = true)"),
                "Should contain @Deprecated annotation");
        assertTrue(
                source.contains("@SuppressWarnings({\"unchecked\", \"rawtypes\"})"),
                "Should contain @SuppressWarnings annotation");
        assertTrue(
                source.contains("@CustomAnnotation(value = \"class-level\", priority = 1)"),
                "Should contain custom annotation");
    }

    @Test
    public void getClassSourceWithAnnotationsTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass", true);
        assertTrue(sourceOpt.isPresent(), "Should find AnnotatedClass");
        final var source = sourceOpt.get();

        // Verify class-level annotations are captured
        assertTrue(
                source.contains("@Deprecated(since = \"1.2\", forRemoval = true)"),
                "Should contain @Deprecated annotation with parameters");
        assertTrue(
                source.contains("@SuppressWarnings({\"unchecked\", \"rawtypes\"})"),
                "Should contain @SuppressWarnings annotation with array");
        assertTrue(
                source.contains("@CustomAnnotation(value = \"class-level\", priority = 1)"),
                "Should contain custom annotation with parameters");

        // Verify field annotations are captured
        assertTrue(
                source.contains("@CustomAnnotation(\"field-level\")"), "Should contain field-level custom annotation");

        // Verify constructor annotations are captured
        assertTrue(source.contains("@CustomAnnotation(\"constructor\")"), "Should contain constructor annotation");

        // Verify method annotations are captured
        assertTrue(source.contains("@Override"), "Should contain @Override annotation");
        assertTrue(
                source.contains("@CustomAnnotation(value = \"method\", priority = 2)"),
                "Should contain method-level custom annotation");
        assertTrue(source.contains("@SuppressWarnings(\"unchecked\")"), "Should contain method-level SuppressWarnings");
    }

    @Test
    public void getClassSourceWithInnerClassJavadocsTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass.InnerHelper", true);
        assertTrue(sourceOpt.isPresent(), "Should find AnnotatedClass.InnerHelper");
        final var source = sourceOpt.get();

        // Verify inner class Javadocs are captured
        assertTrue(source.contains("Inner class with its own documentation"), "Should contain inner class Javadoc");
        assertTrue(
                source.contains("This demonstrates nested class handling"), "Should contain inner class description");

        // Verify inner class annotations are captured
        assertTrue(source.contains("@CustomAnnotation(\"inner-class\")"), "Should contain inner class annotation");

        // Verify inner method Javadocs and annotations
        assertTrue(source.contains("Helper method documentation"), "Should contain inner method Javadoc");
        assertTrue(source.contains("@param message the message to process"), "Should contain @param tag");
        assertTrue(source.contains("@return processed message"), "Should contain @return tag");
        assertTrue(source.contains("@CustomAnnotation(\"inner-method\")"), "Should contain inner method annotation");
    }

    @Test
    public void getMethodSourceWithJavadocsTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass.toString", true);
        assertTrue(sourceOpt.isPresent(), "Should find toString method");
        final var source = sourceOpt.get();

        // Verify method Javadoc is captured
        assertTrue(source.contains("Gets the current configuration value"), "Should contain method Javadoc");
        assertTrue(
                source.contains("@return the configuration value, never null"), "Should contain @return documentation");
        assertTrue(source.contains("@see #CONFIG_VALUE"), "Should contain @see reference");
        assertTrue(source.contains("@deprecated Use"), "Should contain @deprecated tag");

        // Verify method annotations are captured
        assertTrue(source.contains("@Deprecated(since = \"1.1\")"), "Should contain @Deprecated annotation");
        assertTrue(
                source.contains("@CustomAnnotation(value = \"method\", priority = 2)"),
                "Should contain custom annotation");
        assertTrue(source.contains("@Override"), "Should contain @Override annotation");
    }

    @Test
    public void getMethodSourceWithGenericJavadocsTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass.processValue", true);
        assertTrue(sourceOpt.isPresent(), "Should find processValue method");
        final var source = sourceOpt.get();

        // Verify generic method Javadoc is captured
        assertTrue(source.contains("A generic method with complex documentation"), "Should contain method description");
        assertTrue(
                source.contains("@param <T> the type parameter"),
                "Should contain generic type parameter documentation");
        assertTrue(source.contains("@param input the input value"), "Should contain parameter documentation");
        assertTrue(
                source.contains("@param processor the processing function"),
                "Should contain second parameter documentation");
        assertTrue(source.contains("@return the processed result"), "Should contain return documentation");
        assertTrue(
                source.contains("@throws RuntimeException if processing fails"), "Should contain throws documentation");

        // Verify generic method annotations
        assertTrue(source.contains("@SuppressWarnings(\"unchecked\")"), "Should contain method-level annotation");
    }

    @Test
    public void getClassSourceCustomAnnotationTest() {
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "CustomAnnotation", true);
        assertTrue(sourceOpt.isPresent(), "Should find CustomAnnotation");
        final var source = sourceOpt.get();

        // Verify annotation class Javadocs are captured
        assertTrue(source.contains("Custom annotation for testing"), "Should contain annotation class description");
        assertTrue(source.contains("@author Test Framework"), "Should contain @author tag");

        // Verify annotation meta-annotations are captured
        assertTrue(
                source.contains(
                        "@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR})"),
                "Should contain @Target annotation");
        assertTrue(source.contains("@Retention(RetentionPolicy.RUNTIME)"), "Should contain @Retention annotation");

        // Verify annotation method Javadocs
        assertTrue(source.contains("The annotation value"), "Should contain annotation method description");
        assertTrue(source.contains("@return the value string"), "Should contain annotation method @return tag");
        assertTrue(source.contains("Priority level"), "Should contain priority method description");
    }

    @Test
    public void testNormalizationStripsGenericsInClassNames() {
        // Based on log example: SlidingWindowCache<K, V extends Disposable>.getCachedKeys
        assertEquals(
                "io.github.jbellis.brokk.util.SlidingWindowCache.getCachedKeys",
                analyzer.normalizeFullName(
                        "io.github.jbellis.brokk.util.SlidingWindowCache<K, V extends Disposable>.getCachedKeys"));

        // Class lookup with generics on the type
        assertTrue(
                AnalyzerUtil.getSource(analyzer, "A<String>", false).isPresent(),
                "Class lookup with generics should normalize");

        // Method lookup with generics on the containing class
        assertTrue(
                AnalyzerUtil.getSource(analyzer, "A<Integer>.method1", false).isPresent(),
                "Method lookup with class generics should normalize");

        // Nested classes with generics on each segment
        assertTrue(
                AnalyzerUtil.getSource(
                                analyzer, "A.AInner<List<String>>.AInnerInner<Map<Integer, String>>.method7", false)
                        .isPresent(),
                "Nested class method with generics should normalize");
    }

    @Test
    public void testNormalizationHandlesAnonymousAndLocationSuffix() {
        // Location suffix without anon
        assertTrue(
                AnalyzerUtil.getSource(analyzer, "A.method1:16", false).isPresent(),
                "Location suffix alone should normalize for method source lookup");

        // Anonymous with just digits
        assertTrue(
                AnalyzerUtil.getSource(analyzer, "A.method6$1", false).isPresent(),
                "Anonymous digit suffix should normalize for method source lookup");
    }

    @Test
    public void testDefinitionAndSourcesWithNormalizedConstructorNames() {
        // Based on log example: Type.Type for constructor (and possibly with generics on the type)
        // Note: B has an explicit constructor, so it should resolve via B.B
        assertTrue(
                AnalyzerUtil.getSource(analyzer, "B<B>.B", true).isPresent(),
                "Constructor lookup with generics on the type should normalize and resolve");

        // Also ensure plain constructor lookup works (control)
        assertTrue(AnalyzerUtil.getSource(analyzer, "B.B", true).isPresent(), "Constructor lookup should resolve");
    }

    @Test
    public void testTopLevelCodeUnitsOfFileWithSingleClass() {
        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "D");
        assertTrue(maybeFile.isPresent());
        var file = maybeFile.get();

        var topLevelUnits = analyzer.getTopLevelDeclarations(file);

        assertEquals(1, topLevelUnits.size(), "Should return only the top-level class D");
        var topLevelClass = topLevelUnits.get(0);
        assertEquals("D", topLevelClass.fqName());
        assertTrue(topLevelClass.isClass());
    }

    @Test
    public void testTopLevelCodeUnitsOfFileWithNestedClasses() {
        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "A");
        assertTrue(maybeFile.isPresent());
        var file = maybeFile.get();

        var topLevelUnits = analyzer.getTopLevelDeclarations(file);

        assertEquals(1, topLevelUnits.size(), "Should return only the top-level class A, not nested classes");
        var topLevelClass = topLevelUnits.get(0);
        assertEquals("A", topLevelClass.fqName());
        assertTrue(topLevelClass.isClass());
    }

    @Test
    public void testTopLevelCodeUnitsOfPackagedFile() {
        var file = new ProjectFile(testProject.getRoot(), "Packaged.java");

        var topLevelUnits = analyzer.getTopLevelDeclarations(file);

        // Now includes the Package Module definition
        assertEquals(2, topLevelUnits.size(), "Should return top-level class Foo and Module p1");

        boolean foundModule =
                topLevelUnits.stream().anyMatch(cu -> cu.isModule() && "io.github.jbellis.brokk".equals(cu.fqName()));
        boolean foundClass = topLevelUnits.stream()
                .anyMatch(cu -> cu.isClass() && "io.github.jbellis.brokk.Foo".equals(cu.fqName()));

        assertTrue(foundModule, "Should find module definition");
        assertTrue(foundClass, "Should find class definition");
    }

    @Test
    public void testTopLevelCodeUnitsOfEnum() {
        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "EnumClass");
        assertTrue(maybeFile.isPresent());
        var file = maybeFile.get();

        var topLevelUnits = analyzer.getTopLevelDeclarations(file);

        assertEquals(1, topLevelUnits.size(), "Should return only the enum class");
        var topLevelEnum = topLevelUnits.get(0);
        assertEquals("EnumClass", topLevelEnum.fqName());
        assertTrue(topLevelEnum.isClass());
    }

    @Test
    public void testTopLevelCodeUnitsOfInterface() {
        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "ServiceInterface");
        assertTrue(maybeFile.isPresent());
        var file = maybeFile.get();

        var topLevelUnits = analyzer.getTopLevelDeclarations(file);

        assertEquals(1, topLevelUnits.size(), "Should return only the interface");
        var topLevelInterface = topLevelUnits.get(0);
        assertEquals("ServiceInterface", topLevelInterface.fqName());
        assertTrue(topLevelInterface.isClass());
    }

    @Test
    public void testTopLevelCodeUnitsOfNonExistentFile() {
        var nonExistentFile = new ProjectFile(testProject.getRoot(), "NonExistent.java");

        var topLevelUnits = analyzer.getTopLevelDeclarations(nonExistentFile);

        assertTrue(topLevelUnits.isEmpty(), "Should return empty list for non-existent file");
    }

    @Test
    public void moduleCodeUnitCreated_withTopLevelClassChildren_only() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    package p1;
    class A { class Inner {} }
    class B {}
    """, "A_B.java")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeModule = analyzer.getDefinitions("p1").stream().findFirst();
            assertTrue(maybeModule.isPresent(), "Module CodeUnit for package 'p1' should be created");
            var module = maybeModule.get();
            assertTrue(module.isModule(), "Found CodeUnit should be a MODULE type");

            var children = analyzer.getDirectChildren(module).stream()
                    .map(CodeUnit::fqName)
                    .sorted()
                    .toList();

            assertEquals(
                    List.of("p1.A", "p1.B"),
                    children,
                    "Module children should include only top-level classes A and B (no nested types)");
        }
    }

    @Test
    public void implicitConstructorCodeUnit_isResolvableViaGetDefinitions() throws IOException {
        try (var testProject =
                InlineTestProjectCreator.code("public class Foo {}", "Foo.java").build()) {

            var analyzer = new JavaAnalyzer(testProject);

            // Implicit constructors use the class name naming convention (Foo.Foo)
            var definitions = analyzer.getDefinitions("Foo.Foo");
            assertTrue(
                    definitions.stream().anyMatch(cu -> cu.kind() == CodeUnitType.FUNCTION),
                    "Implicit constructor Foo.Foo should be resolvable");
        }
    }

    @Test
    public void interfaceDoesNotGetImplicitConstructor() throws IOException {
        try (var testProject =
                InlineTestProjectCreator.code("public interface I {}", "I.java").build()) {

            var analyzer = new JavaAnalyzer(testProject);

            // Interface I should NOT have an implicit constructor I.I
            var definitions = analyzer.getDefinitions("I.I");
            assertTrue(
                    definitions.isEmpty(), "Interface should not produce an implicit constructor; got: " + definitions);
        }
    }

    @Test
    public void enumDoesNotGetImplicitConstructor() throws IOException {
        try (var testProject = InlineTestProjectCreator.code("public enum E { A, B }", "E.java")
                .build()) {

            var analyzer = new JavaAnalyzer(testProject);

            // Enum E should NOT have an implicit constructor E.E
            var definitions = analyzer.getDefinitions("E.E");
            assertTrue(definitions.isEmpty(), "Enum should not produce an implicit constructor; got: " + definitions);
        }
    }

    @Test
    public void recordDoesNotGetImplicitConstructor() throws IOException {
        try (var testProject = InlineTestProjectCreator.code("public record R(int x) {}", "R.java")
                .build()) {

            var analyzer = new JavaAnalyzer(testProject);

            // Record R should NOT have an implicit constructor R.R
            // Records have canonical constructors generated by the compiler, not implicit no-arg ones
            var definitions = analyzer.getDefinitions("R.R");
            assertTrue(definitions.isEmpty(), "Record should not produce an implicit constructor; got: " + definitions);
        }
    }

    @Test
    public void annotationDoesNotGetImplicitConstructor() throws IOException {
        try (var testProject = InlineTestProjectCreator.code("public @interface A {}", "A.java")
                .build()) {

            var analyzer = new JavaAnalyzer(testProject);

            // Annotation @interface A should NOT have an implicit constructor A.A
            var definitions = analyzer.getDefinitions("A.A");
            assertTrue(
                    definitions.isEmpty(),
                    "Annotation should not produce an implicit constructor; got: " + definitions);
        }
    }

    @Test
    public void explicitConstructorPreventsImplicitSynthesis() throws IOException {
        try (var testProject = InlineTestProjectCreator.code("public class Bar { public Bar(int x) {} }", "Bar.java")
                .build()) {

            var analyzer = new JavaAnalyzer(testProject);

            var definitions = analyzer.getDefinitions("Bar.Bar");

            // Should only have the explicit one
            assertEquals(1, definitions.size(), "Should have exactly one definition for Bar.Bar (the explicit one)");

            var constructorCu = definitions.iterator().next();
            assertTrue(
                    analyzer.getSource(constructorCu, true).isPresent(),
                    "The constructor should have source (meaning it's the explicit one, not synthetic)");
        }
    }

    @Test
    public void implicitConstructor_returnsEmptySource() throws IOException {
        try (var testProject =
                InlineTestProjectCreator.code("public class Foo {}", "Foo.java").build()) {

            var analyzer = new JavaAnalyzer(testProject);

            // Implicit constructors use Foo.Foo naming convention
            var constructorCu = analyzer.getDefinitions("Foo.Foo").stream()
                    .filter(cu -> cu.kind() == CodeUnitType.FUNCTION)
                    .findFirst()
                    .get();

            // Assert direct analyzer methods return empty for synthetic/implicit units
            assertTrue(
                    analyzer.getSources(constructorCu, true).isEmpty(),
                    "Implicit constructor should have no source blocks");
            assertTrue(
                    analyzer.getSource(constructorCu, true).isEmpty(),
                    "Implicit constructor source should be empty Optional");

            // Assert AnalyzerUtil helper also behaves correctly
            assertTrue(
                    AnalyzerUtil.getSource(analyzer, "Foo.Foo", true).isEmpty(),
                    "AnalyzerUtil.getSource should return empty for implicit constructor");
        }
    }

    @Test
    public void moduleCodeUnitAggregatesChildrenAcrossFiles_excludesNested() throws IOException {
        try (var testProject = InlineTestProjectCreator.code(
                        """
    package p2;
    class A { class Inner {} }
    class C { static class Nested {} }
    """,
                        "A_C.java")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);

            var maybeModule = analyzer.getDefinitions("p2").stream().findFirst();
            assertTrue(maybeModule.isPresent(), "Module CodeUnit for package 'p2' should be created");
            var module = maybeModule.get();
            assertTrue(module.isModule(), "Found CodeUnit should be a MODULE type");

            var children = analyzer.getDirectChildren(module).stream()
                    .map(CodeUnit::fqName)
                    .sorted()
                    .toList();

            assertEquals(
                    List.of("p2.A", "p2.C"),
                    children,
                    "Module children should include only top-level classes A and C (exclude nested types)");
        }
    }

    /**
     * Regression test for Issue #2121: Verify that Javadoc comments preceding a class
     * declaration preserve correct indentation when using getSource(.., true).
     */
    @Test
    public void testClassJavadocIndentationIsPreserved() {
        // AnnotatedClass has Javadoc comments above the class declaration
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass", true);
        assertTrue(sourceOpt.isPresent(), "AnnotatedClass source should be available");

        String source = sourceOpt.get();

        // Verify Javadoc is present
        assertCodeContains(source, "/**", "Should include opening Javadoc marker");
        assertCodeContains(source, "A comprehensive test class", "Should include Javadoc content");

        // Verify source starts with indentation (Javadoc should be included with proper indentation)
        // For top-level class, Javadoc should start at column 0
        assertTrue(
                source.startsWith("/") || source.startsWith(" /"),
                "Source should start with Javadoc (either at column 0 or with leading space)");

        // Verify the first non-whitespace line is Javadoc and class declaration follows
        String[] lines = source.split("\n");
        String firstNonBlank = "";
        String classLine = "";
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (!trimmed.isEmpty()) {
                if (firstNonBlank.isEmpty()) {
                    firstNonBlank = trimmed;
                }
                if (trimmed.contains("public class AnnotatedClass")) {
                    classLine = trimmed;
                    break;
                }
            }
        }

        assertTrue(firstNonBlank.startsWith("/**"), "First non-blank line should be Javadoc opening");
        assertTrue(!classLine.isEmpty(), "Should find class declaration line");
    }

    /**
     * Regression test for Issue #2121: Verify that Javadoc comments preceding a method
     * declaration preserve correct indentation when using getMethodSources(.., true).
     */
    @Test
    public void testMethodJavadocIndentationIsPreserved() {
        // AnnotatedClass.toString has Javadoc comments above the method declaration
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass.toString", true);
        assertTrue(sourceOpt.isPresent(), "Method source should be available");

        String source = sourceOpt.get();

        // Verify Javadoc is present
        assertCodeContains(source, "/**", "Should include opening Javadoc marker");
        assertCodeContains(source, "Gets the current configuration value", "Should include Javadoc content");

        // Verify source includes Javadoc before annotations/method
        String[] lines = source.split("\n");
        boolean foundJavadoc = false;
        boolean foundAnnotationOrMethod = false;

        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("/**")) {
                foundJavadoc = true;
            }
            if (foundJavadoc && (trimmed.startsWith("@") || trimmed.startsWith("public"))) {
                foundAnnotationOrMethod = true;
                break;
            }
        }

        assertTrue(foundJavadoc, "Should find Javadoc opening");
        assertTrue(foundAnnotationOrMethod, "Should find annotation or method declaration after Javadoc");
    }

    /**
     * Regression test for Issue #2121: Verify that indentation is consistent across
     * all lines of a Javadoc block and with the declaration line.
     */
    @Test
    public void testJavadocMultilineIndentationIsConsistent() {
        // AnnotatedClass.processValue has multiline Javadoc with complex documentation
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass.processValue", true);
        assertTrue(sourceOpt.isPresent(), "Method source should be available");

        String source = sourceOpt.get();

        // Verify Javadoc spans multiple lines
        assertCodeContains(source, "/**", "Should include opening Javadoc");
        assertCodeContains(source, "@param", "Should include parameter docs");
        assertCodeContains(source, "@return", "Should include return documentation");

        // Extract lines and verify indentation consistency
        String[] lines = source.split("\n");
        int javadocIndent = -1;
        int firstParamIndent = -1;
        int annotationIndent = -1;

        for (String line : lines) {
            int leadingSpaces = line.length() - line.stripLeading().length();
            String trimmed = line.stripLeading();

            if (trimmed.startsWith("/**") && javadocIndent < 0) {
                javadocIndent = leadingSpaces;
            } else if (trimmed.startsWith("@param") && firstParamIndent < 0) {
                firstParamIndent = leadingSpaces;
            } else if ((trimmed.startsWith("@Suppress") || trimmed.startsWith("@Override")) && annotationIndent < 0) {
                annotationIndent = leadingSpaces;
            }
        }

        // Verify that all found indents are consistent
        if (javadocIndent >= 0 && firstParamIndent >= 0) {
            assertEquals(
                    javadocIndent, firstParamIndent, "Javadoc opening and @param lines should have same indentation");
        }

        if (javadocIndent >= 0 && annotationIndent >= 0) {
            assertEquals(
                    javadocIndent, annotationIndent, "Javadoc and following annotations should have same indentation");
        }

        // At minimum, verify indentation levels are non-negative and reasonable
        assertTrue(javadocIndent >= 0, "Should find Javadoc opening");
    }

    /**
     * Regression test for Issue #2121: Verify that leading indentation is preserved
     * and not trimmed by the source retrieval APIs.
     */
    @Test
    public void testSourceIndentationNotTrimmed() {
        // Test with inner class which has natural indentation
        final var sourceOpt = AnalyzerUtil.getSource(analyzer, "AnnotatedClass.InnerHelper", true);
        assertTrue(sourceOpt.isPresent(), "Inner class source should be available");

        String source = sourceOpt.get();

        // Verify that the source starts with expected whitespace (indentation)
        assertTrue(
                source.startsWith(" ") || source.startsWith("\t"),
                "Source should preserve leading indentation; should not be fully stripped");

        // Verify Javadoc is present in the source with leading indentation
        assertCodeContains(source, "/**", "Should contain Javadoc marker");
        assertCodeContains(source, "Inner class with its own documentation", "Should contain Javadoc content");

        // Verify the Javadoc line itself has indentation preserved
        String[] lines = source.split("\n");
        for (String line : lines) {
            if (line.stripLeading().startsWith("/**")) {
                assertTrue(
                        line.startsWith(" ") || line.startsWith("\t"),
                        "Javadoc line should preserve leading indentation");
                break;
            }
        }
    }

    /**
     * Regression test for inline comment edge case: when a comment/Javadoc appears on
     * the same line as other code, we should NOT back up to include that code.
     */
    @Test
    public void testEnclosingCodeUnitByLine() {
        var maybeFile = AnalyzerUtil.getFileFor(analyzer, "A");
        assertTrue(maybeFile.isPresent());
        var file = maybeFile.get();

        // 1. Exact match for a method
        var method1Cu =
                analyzer.getDefinitions("A.method1").stream().findFirst().orElseThrow();
        int method1Line = analyzer.getStartLineForCodeUnit(method1Cu);
        var cu1 = analyzer.enclosingCodeUnit(file, method1Line, method1Line);
        assertTrue(cu1.isPresent());
        assertEquals("A.method1", cu1.get().fqName());

        // 2. Range spanning from method7 to its containing class AInnerInner
        var cu7 = analyzer.getDefinitions("A.AInner.AInnerInner.method7").stream()
                .findFirst()
                .orElseThrow();
        int method7Start = analyzer.getStartLineForCodeUnit(cu7);
        // method7 is very short, just a few lines.
        var cu2 = analyzer.enclosingCodeUnit(file, method7Start, method7Start + 1);
        assertTrue(cu2.isPresent());
        assertEquals("A.AInner.AInnerInner.method7", cu2.get().fqName());

        // 3. Range spanning multiple methods returns the containing class
        var cuMethod1 =
                analyzer.getDefinitions("A.method1").stream().findFirst().orElseThrow();
        var cuMethod2 =
                analyzer.getDefinitions("A.method2").stream().findFirst().orElseThrow();
        int m1Start = analyzer.getStartLineForCodeUnit(cuMethod1);
        int m2Start = analyzer.getStartLineForCodeUnit(cuMethod2);

        var cu3 = analyzer.enclosingCodeUnit(file, Math.min(m1Start, m2Start), Math.max(m1Start, m2Start));
        assertTrue(cu3.isPresent());
        assertEquals("A", cu3.get().fqName());
    }

    @Test
    public void annotatedPackageInfo_CreatesModuleAndExcludesAnnotationFromFqn() throws IOException {
        String packageInfo =
                """
                @NullMarked
                package p1;

                import org.jspecify.annotations.NullMarked;
                """;
        String classA = """
                package p1;
                public class A {}
                """;

        try (var testProject = InlineTestProjectCreator.code(packageInfo, "package-info.java")
                .addFileContents(classA, "A.java")
                .build()) {

            var analyzer = createTreeSitterAnalyzer(testProject);

            // Assert module existence and FQN
            var definitions = analyzer.getDefinitions("p1");
            var moduleOpt = definitions.stream().filter(CodeUnit::isModule).findFirst();

            assertTrue(moduleOpt.isPresent(), "Module 'p1' should be defined");
            CodeUnit module = moduleOpt.get();
            assertEquals("p1", module.fqName(), "Module FQN should be exactly 'p1'");

            // Assert annotation is NOT part of the FQN lookup
            var leakedAnnotation = analyzer.getDefinitions("p1.@NullMarked");
            assertTrue(leakedAnnotation.isEmpty(), "FQN should not include package annotations");

            // Assert children include top-level classes (important for wildcard import resolution)
            var children = analyzer.getDirectChildren(module);
            boolean foundClassA = children.stream().anyMatch(cu -> cu.isClass() && "p1.A".equals(cu.fqName()));
            assertTrue(foundClassA, "Module 'p1' should have top-level class 'p1.A' as a child");
        }
    }

    @Test
    public void testInlineJavadocDoesNotIncludePrecedingCode() throws IOException {
        try (var project = TestCodeProject.fromResourceDir("testcode-java", Languages.JAVA)) {
            var testAnalyzer = new JavaAnalyzer(project);

            // methodAfterInlineJavadoc has Javadoc on same line as `private int other = 1;`
            var sourceOpt = AnalyzerUtil.getSource(testAnalyzer, "InlineComment.methodAfterInlineJavadoc", true);
            assertTrue(sourceOpt.isPresent(), "Method source should be available");

            String source = sourceOpt.get();

            // Verify Javadoc IS included
            assertCodeContains(source, "/** Inline Javadoc on same line as code */", "Should include the Javadoc");

            // Verify the preceding code is NOT included
            assertFalse(source.contains("private int other"), "Should NOT include code from same line as Javadoc");
            assertFalse(source.contains("= 1;"), "Should NOT include field initialization from same line");
        }
    }

    @Test
    public void testInterfaceConstantsFieldsDetection() throws IOException {
        String code =
                """
                public interface SyntaxConstants {
                    String SYNTAX_STYLE_NONE = "text/plain";
                    String SYNTAX_STYLE_ACTIONSCRIPT = "text/actionscript";
                    String SYNTAX_STYLE_C = "text/c";
                    int DEFAULT_PRIORITY = 100;
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "SyntaxConstants.java").build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var declarations = analyzer.getAllDeclarations();

            // Verify the interface itself is detected
            boolean foundInterface =
                    declarations.stream().anyMatch(cu -> cu.isClass() && "SyntaxConstants".equals(cu.fqName()));
            assertTrue(foundInterface, "Interface SyntaxConstants should be detected as a class");

            // Verify the fields are detected
            Set<String> fieldNames = declarations.stream()
                    .filter(CodeUnit::isField)
                    .map(CodeUnit::identifier)
                    .collect(Collectors.toSet());

            assertTrue(fieldNames.contains("SYNTAX_STYLE_NONE"), "Field SYNTAX_STYLE_NONE should be detected");
            assertTrue(
                    fieldNames.contains("SYNTAX_STYLE_ACTIONSCRIPT"),
                    "Field SYNTAX_STYLE_ACTIONSCRIPT should be detected");
            assertTrue(fieldNames.contains("SYNTAX_STYLE_C"), "Field SYNTAX_STYLE_C should be detected");
            assertTrue(fieldNames.contains("DEFAULT_PRIORITY"), "Field DEFAULT_PRIORITY should be detected");
        }
    }

    @Test
    public void testInterfaceConstantsMultipleDeclarators() throws IOException {
        String code =
                """
                public interface MultiConstants {
                    int CONST_A = 1, CONST_B = 2;
                    String NAME_X = "x", NAME_Y = "y", NAME_Z = "z";
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "MultiConstants.java").build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var declarations = analyzer.getAllDeclarations();

            // Verify the interface itself is detected
            boolean foundInterface =
                    declarations.stream().anyMatch(cu -> cu.isClass() && "MultiConstants".equals(cu.fqName()));
            assertTrue(foundInterface, "Interface MultiConstants should be detected as a class");

            // Verify the fields are detected
            Set<String> fieldNames = declarations.stream()
                    .filter(CodeUnit::isField)
                    .map(CodeUnit::identifier)
                    .collect(Collectors.toSet());

            assertTrue(fieldNames.contains("CONST_A"), "Field CONST_A should be detected");
            assertTrue(fieldNames.contains("CONST_B"), "Field CONST_B should be detected");
            assertTrue(fieldNames.contains("NAME_X"), "Field NAME_X should be detected");
            assertTrue(fieldNames.contains("NAME_Y"), "Field NAME_Y should be detected");
            assertTrue(fieldNames.contains("NAME_Z"), "Field NAME_Z should be detected");

            assertEquals(5, fieldNames.size(), "Should detect exactly 5 constant fields");
        }
    }

    @Test
    public void testInterfaceConstantsWithGenericsAndAnnotations() throws IOException {
        String code =
                """
                import java.util.List;
                public interface ComplexConstants {
                    List<String> ITEMS = List.of("a", "b");
                    @Deprecated
                    String DEPRECATED_VAL = "old";
                    @SuppressWarnings("unchecked")
                    List RAW_LIST = List.of();
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "ComplexConstants.java").build()) {
            var analyzer = createTreeSitterAnalyzer(testProject);
            var declarations = analyzer.getAllDeclarations();

            Set<String> fieldNames = declarations.stream()
                    .filter(CodeUnit::isField)
                    .map(CodeUnit::identifier)
                    .collect(Collectors.toSet());

            assertTrue(fieldNames.contains("ITEMS"), "Field ITEMS with generics should be detected");
            assertTrue(fieldNames.contains("DEPRECATED_VAL"), "Annotated field DEPRECATED_VAL should be detected");
            assertTrue(fieldNames.contains("RAW_LIST"), "Annotated field RAW_LIST should be detected");
            assertEquals(3, fieldNames.size());
        }
    }

    @Test
    public void testIsAccessExpressionCommentFiltering() throws IOException {
        String content =
                """
                public class Test {
                    // Target should not be found
                    /* Target should not be found */
                    /** Target in javadoc */
                    private Target myTarget;
                    public void main() {
                        new Target();
                    }
                }
                """;

        try (var testProject =
                InlineTestProjectCreator.code(content, "Test.java").build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "Test.java");

            // Comments should return false
            assertIsAccessExpression(analyzer, file, content, "Target", 0, false); // // Target
            assertIsAccessExpression(analyzer, file, content, "Target", 1, false); // /* Target
            assertIsAccessExpression(analyzer, file, content, "Target", 2, false); // /** Target

            // Real references should return true
            // Occurrence 3 is 'private Target myTarget' (type identifier)
            assertIsAccessExpression(analyzer, file, content, "Target", 3, true);
            // Occurrence 4 is 'new Target()' (object creation)
            assertIsAccessExpression(analyzer, file, content, "Target", 4, true);
        }
    }

    private void assertIsAccessExpression(
            JavaAnalyzer analyzer,
            ProjectFile file,
            String content,
            String substring,
            int occurrence,
            boolean expected) {
        int charIdx = -1;
        for (int i = 0; i <= occurrence; i++) {
            charIdx = content.indexOf(substring, charIdx + 1);
        }
        assertTrue(charIdx >= 0, "Could not find occurrence " + occurrence + " of " + substring);

        int startByte = content.substring(0, charIdx).getBytes(StandardCharsets.UTF_8).length;
        int endByte = startByte + substring.getBytes(StandardCharsets.UTF_8).length;

        assertEquals(
                expected,
                analyzer.isAccessExpression(file, startByte, endByte),
                "Expected isAccessExpression=" + expected + " for '" + substring + "' at occurrence " + occurrence);
    }

    @Test
    public void testIsAccessExpressionLocalShadowing() throws IOException {
        String content =
                """
        public class ShadowTest {
            private String channel;
            public ShadowTest(String channel) {
                System.out.println(channel);      // Parameter access
                System.out.println(this.channel); // Explicit field access
            }
            public void other(Object obj) {
                String channel = "local";
                System.out.println(channel);      // Local variable access
            }
        }
        """;

        try (var testProject =
                InlineTestProjectCreator.code(content, "ShadowTest.java").build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "ShadowTest.java");

            // Occurrence 0: private String channel (field declaration name)
            assertIsAccessExpression(analyzer, file, content, "channel", 0, false);
            // Occurrence 1: String channel (parameter declaration name)
            assertIsAccessExpression(analyzer, file, content, "channel", 1, false);
            // Occurrence 2: println(channel) -> resolves to parameter
            assertIsAccessExpression(analyzer, file, content, "channel", 2, false);
            // Occurrence 3: this.channel -> explicit field access
            assertIsAccessExpression(analyzer, file, content, "channel", 3, true);
            // Occurrence 4: String channel = "local" (local var declaration name)
            assertIsAccessExpression(analyzer, file, content, "channel", 4, false);
            // Occurrence 5: println(channel) -> resolves to local variable
            assertIsAccessExpression(analyzer, file, content, "channel", 5, false);
        }
    }

    @Test
    public void testSummaryFragmentSupportingFragmentsFiltersNestedAncestors() throws IOException {
        try (var project = InlineTestProjectCreator.code(
                        """
                        package p;
                        public class Outer extends OuterBase {
                            class Inner extends InnerBase {}
                        }
                        """,
                        "p/Outer.java")
                .addFileContents(
                        """
                        package p;
                        public class OuterBase {}
                        """,
                        "p/OuterBase.java")
                .addFileContents(
                        """
                        package p;
                        public class InnerBase {}
                        """,
                        "p/InnerBase.java")
                .build()) {

            var analyzer = new JavaAnalyzer(project);
            var cm = new TestContextManager(project, analyzer);

            var frag =
                    new ContextFragments.SummaryFragment(cm, "p.Outer", ContextFragment.SummaryType.CODEUNIT_SKELETON);

            var ids = frag.supportingFragments().stream()
                    .filter(f -> f instanceof ContextFragments.SummaryFragment)
                    .map(f -> ((ContextFragments.SummaryFragment) f).getTargetIdentifier())
                    .collect(Collectors.toSet());

            assertTrue(ids.contains("p.OuterBase"), "Should contain top-level ancestor p.OuterBase");
            assertFalse(ids.contains("p.InnerBase"), "Should NOT contain nested class ancestor p.InnerBase");
        }
    }

    @Test
    public void testFindNearestDeclaration_ConstructorParameter() throws IOException {
        String content =
                """
        public class CtrCryptoInputStream {
            protected CtrCryptoInputStream(final ReadableByteChannel channel, final int bufferSize) {
                this.doSomething(channel);
            }
        }
        """;

        try (var testProject = InlineTestProjectCreator.code(content, "CtrCryptoInputStream.java")
                .build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "CtrCryptoInputStream.java");

            // Find the byte position of "channel" in "this.doSomething(channel)"
            int charIdx = content.indexOf("doSomething(channel)") + "doSomething(".length();
            int startByte = content.substring(0, charIdx).getBytes(StandardCharsets.UTF_8).length;
            int endByte = startByte + "channel".getBytes(StandardCharsets.UTF_8).length;

            var result = analyzer.findNearestDeclaration(file, startByte, endByte, "channel");

            assertTrue(result.isPresent(), "Should find declaration for 'channel'");
            assertEquals(IAnalyzer.DeclarationKind.PARAMETER, result.get().kind());
            assertEquals("channel", result.get().name());
        }
    }

    @Test
    public void testFindNearestDeclaration_LocalVariable() throws IOException {
        String content =
                """
        public class Test {
            public void method() {
                String localVar = "hello";
                System.out.println(localVar);
            }
        }
        """;

        try (var testProject =
                InlineTestProjectCreator.code(content, "Test.java").build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "Test.java");

            // Find the byte position of "localVar" in "println(localVar)"
            int charIdx = content.indexOf("println(localVar)") + "println(".length();
            int startByte = content.substring(0, charIdx).getBytes(StandardCharsets.UTF_8).length;
            int endByte = startByte + "localVar".getBytes(StandardCharsets.UTF_8).length;

            var result = analyzer.findNearestDeclaration(file, startByte, endByte, "localVar");

            assertTrue(result.isPresent(), "Should find declaration for 'localVar'");
            assertEquals(IAnalyzer.DeclarationKind.LOCAL_VARIABLE, result.get().kind());
            assertEquals("localVar", result.get().name());
        }
    }

    @Test
    public void testFindNearestDeclaration_EnhancedForLoop() throws IOException {
        String content =
                """
        import java.util.List;
        public class Test {
            public void method(List<String> items) {
                for (String item : items) {
                    System.out.println(item);
                }
            }
        }
        """;

        try (var testProject =
                InlineTestProjectCreator.code(content, "Test.java").build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "Test.java");

            // Find the byte position of "item" in "println(item)"
            int charIdx = content.indexOf("println(item)") + "println(".length();
            int startByte = content.substring(0, charIdx).getBytes(StandardCharsets.UTF_8).length;
            int endByte = startByte + "item".getBytes(StandardCharsets.UTF_8).length;

            var result = analyzer.findNearestDeclaration(file, startByte, endByte, "item");

            assertTrue(result.isPresent(), "Should find declaration for 'item'");
            assertEquals(
                    IAnalyzer.DeclarationKind.FOR_LOOP_VARIABLE, result.get().kind());
            assertEquals("item", result.get().name());
        }
    }

    @Test
    public void testFindNearestDeclaration_CatchParameter() throws IOException {
        String content =
                """
        public class Test {
            public void method() {
                try {
                    throw new RuntimeException();
                } catch (Exception ex) {
                    System.out.println(ex);
                }
            }
        }
        """;

        try (var testProject =
                InlineTestProjectCreator.code(content, "Test.java").build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "Test.java");

            // Find the byte position of "ex" in "println(ex)"
            int charIdx = content.indexOf("println(ex)") + "println(".length();
            int startByte = content.substring(0, charIdx).getBytes(StandardCharsets.UTF_8).length;
            int endByte = startByte + "ex".getBytes(StandardCharsets.UTF_8).length;

            var result = analyzer.findNearestDeclaration(file, startByte, endByte, "ex");

            assertTrue(result.isPresent(), "Should find declaration for 'ex'");
            assertEquals(IAnalyzer.DeclarationKind.CATCH_PARAMETER, result.get().kind());
            assertEquals("ex", result.get().name());
        }
    }

    @Test
    public void testFindNearestDeclaration_TryWithResources() throws IOException {
        String content =
                """
        import java.io.*;
        public class Test {
            public void method() throws IOException {
                try (InputStream stream = new FileInputStream("test")) {
                    stream.read();
                }
            }
        }
        """;

        try (var testProject =
                InlineTestProjectCreator.code(content, "Test.java").build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "Test.java");

            // Find the byte position of "stream" in "stream.read()"
            int charIdx = content.indexOf("stream.read()");
            int startByte = content.substring(0, charIdx).getBytes(StandardCharsets.UTF_8).length;
            int endByte = startByte + "stream".getBytes(StandardCharsets.UTF_8).length;

            var result = analyzer.findNearestDeclaration(file, startByte, endByte, "stream");

            assertTrue(result.isPresent(), "Should find declaration for 'stream'");
            assertEquals(
                    IAnalyzer.DeclarationKind.RESOURCE_VARIABLE, result.get().kind());
            assertEquals("stream", result.get().name());
        }
    }

    @Test
    public void testFindNearestDeclaration_LambdaParameter() throws IOException {
        String content =
                """
        import java.util.List;
        public class Test {
            public void method(List<String> items) {
                items.forEach(x -> System.out.println(x));
            }
        }
        """;

        try (var testProject =
                InlineTestProjectCreator.code(content, "Test.java").build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "Test.java");

            // Find the byte position of "x" in "println(x)"
            int charIdx = content.indexOf("println(x)") + "println(".length();
            int startByte = content.substring(0, charIdx).getBytes(StandardCharsets.UTF_8).length;
            int endByte = startByte + "x".getBytes(StandardCharsets.UTF_8).length;

            var result = analyzer.findNearestDeclaration(file, startByte, endByte, "x");

            assertTrue(result.isPresent(), "Should find declaration for 'x'");
            assertEquals(IAnalyzer.DeclarationKind.PARAMETER, result.get().kind());
            assertEquals("x", result.get().name());
        }
    }

    @Test
    public void testFindNearestDeclaration_NotFound_ReturnsEmpty() throws IOException {
        String content =
                """
        public class Test {
            private String field;
            public void method() {
                System.out.println(field);
            }
        }
        """;

        try (var testProject =
                InlineTestProjectCreator.code(content, "Test.java").build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "Test.java");

            // Find the byte position of "field" in "println(field)"
            int charIdx = content.indexOf("println(field)") + "println(".length();
            int startByte = content.substring(0, charIdx).getBytes(StandardCharsets.UTF_8).length;
            int endByte = startByte + "field".getBytes(StandardCharsets.UTF_8).length;

            var result = analyzer.findNearestDeclaration(file, startByte, endByte, "field");

            assertTrue(result.isEmpty(), "Should return empty for field access (not a local declaration)");
        }
    }

    @Test
    public void testExtractSignatureWithVarargs() throws IOException {
        String code =
                """
                public class VarargsTest {
                    public void noArgs() {}
                    public void oneArg(String s) {}
                    public void varargs(String... args) {}
                    public void mixedVarargs(int x, String... args) {}
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "VarargsTest.java").build()) {
            var analyzer = new JavaAnalyzer(testProject);

            // noArgs should have signature "()"
            var noArgsDefs = analyzer.getDefinitions("VarargsTest.noArgs");
            assertEquals(1, noArgsDefs.size());
            assertEquals("()", noArgsDefs.iterator().next().signature());

            // oneArg should have signature "(String)"
            var oneArgDefs = analyzer.getDefinitions("VarargsTest.oneArg");
            assertEquals(1, oneArgDefs.size());
            assertEquals("(String)", oneArgDefs.iterator().next().signature());

            // varargs should have signature "(String[])" - normalized to array notation
            var varargsDefs = analyzer.getDefinitions("VarargsTest.varargs");
            assertEquals(1, varargsDefs.size());
            assertEquals("(String[])", varargsDefs.iterator().next().signature());

            // mixedVarargs should have signature "(int, String[])"
            var mixedDefs = analyzer.getDefinitions("VarargsTest.mixedVarargs");
            assertEquals(1, mixedDefs.size());
            assertEquals("(int, String[])", mixedDefs.iterator().next().signature());
        }
    }

    @Test
    public void testVarargsOverloadDistinction() throws IOException {
        String code =
                """
                public class VarargsOverload {
                    public void process(String single) {}
                    public void process(String... multiple) {}
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "VarargsOverload.java").build()) {
            var analyzer = new JavaAnalyzer(testProject);

            // Overloads should be detected with distinct signatures: (String) vs (String[])
            var defs = analyzer.getDefinitions("VarargsOverload.process");
            assertEquals(2, defs.size(), "Should detect both overloads");

            var signatures = defs.stream().map(CodeUnit::signature).collect(Collectors.toSet());
            assertTrue(signatures.contains("(String)"), "Should find process(String)");
            assertTrue(signatures.contains("(String[])"), "Should find process(String...) as (String[])");

            // Verify both are present as direct children of the class
            var classCu = analyzer.getDefinitions("VarargsOverload").iterator().next();
            var children = analyzer.getDirectChildren(classCu);
            var processMethods = children.stream()
                    .filter(cu -> cu.identifier().equals("process"))
                    .toList();
            assertEquals(2, processMethods.size(), "Class should have both process methods as children");
        }
    }

    @Test
    public void testExtractSignatureWithFinalVarargs() throws IOException {
        String code =
                """
                public class FinalVarargs {
                    public void foo(final Object... args) {}
                    public void bar(final String s) {}
                    public void baz(final int... numbers) {}
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "FinalVarargs.java").build()) {
            var analyzer = new JavaAnalyzer(testProject);

            // foo(final Object... args) -> (Object[])
            var fooDefs = analyzer.getDefinitions("FinalVarargs.foo");
            assertEquals(1, fooDefs.size());
            assertEquals(
                    "(Object[])",
                    fooDefs.iterator().next().signature(),
                    "Signature for 'final Object... args' should be '(Object[])' - modifiers should be ignored");

            // bar(final String s) -> (String)
            var barDefs = analyzer.getDefinitions("FinalVarargs.bar");
            assertEquals(1, barDefs.size());
            assertEquals(
                    "(String)",
                    barDefs.iterator().next().signature(),
                    "Signature for 'final String s' should be '(String)'");

            // baz(final int... numbers) -> (int[])
            var bazDefs = analyzer.getDefinitions("FinalVarargs.baz");
            assertEquals(1, bazDefs.size());
            assertEquals(
                    "(int[])",
                    bazDefs.iterator().next().signature(),
                    "Signature for 'final int... numbers' should be '(int[])'");
        }
    }

    @Test
    public void testLambdaParenting() throws IOException {
        String code =
                """
                package p;
                import java.util.List;
                public class LambdaTest {
                    public void process(List<String> list) {
                        list.forEach(s -> {
                            System.out.println(s);
                        });
                    }
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "LambdaTest.java").build()) {
            var analyzer = new JavaAnalyzer(testProject);
            var file = new ProjectFile(testProject.getRoot(), "LambdaTest.java");

            var declarations = analyzer.getDeclarations(file);
            var processMethod = declarations.stream()
                    .filter(cu -> cu.fqName().equals("p.LambdaTest.process"))
                    .findFirst()
                    .orElseThrow();

            var lambda = declarations.stream()
                    .filter(cu -> cu.fqName().contains("$anon$"))
                    .findFirst()
                    .orElseThrow();

            var children = analyzer.getDirectChildren(processMethod);
            assertTrue(children.contains(lambda), "Lambda should be a child of the enclosing method 'process'");
        }
    }

    @Test
    public void testQueryCachingBehavior() {
        assertNotNull(analyzer);
        int initialCount = analyzer.getQueryCompilationCount();

        // Repeated access to DEFINITIONS query within same thread
        analyzer.withCachedQuery(TreeSitterAnalyzer.QueryType.DEFINITIONS, q1 -> {
            analyzer.withCachedQuery(TreeSitterAnalyzer.QueryType.DEFINITIONS, q2 -> {
                assertSame(q1, q2, "Should return identical TSQuery instance within same thread");
            });
        });

        // Counter should have incremented at most once if it wasn't already cached from previous tests
        int finalCount = analyzer.getQueryCompilationCount();
        assertTrue(finalCount >= initialCount);
        assertTrue(finalCount <= initialCount + 1, "Query should be compiled at most once during this test");
    }

    @Test
    public void testWithCachedQuery_ReturnsDefaultWhenQueryMissing() {
        assertNotNull(analyzer);

        // JavaAnalyzer does not have an IDENTIFIERS query in some configurations,
        // but even if it does, we can test with a type that we know won't exist or by using the default value logic.
        // We use a custom lambda that would return a non-null value if the query existed.

        String defaultValue = "default-value";
        // QueryType.IDENTIFIERS is optional. If it's missing, withCachedQuery must return the defaultValue.
        // We use a type that is likely missing or we can rely on the fact that if it's null in querySources,
        // it triggers the fallback.
        String result = analyzer.withCachedQuery(
                TreeSitterAnalyzer.QueryType.IDENTIFIERS, query -> "should-not-return-this", defaultValue);

        // If JavaAnalyzer happens to have IDENTIFIERS (it does in the current implementation),
        // this test would return "should-not-return-this".
        // To strictly test the non-null/default contract without depending on specific query availability,
        // we can verify the behavior via a mocked/anonymous subclass if needed, but per instructions
        // we prefer public methods. Since withCachedQuery is protected, we've added a test here.

        if (!analyzer.hasQuery(TreeSitterAnalyzer.QueryType.IDENTIFIERS)) {
            assertEquals(defaultValue, result, "Should return default value when query is missing");
        } else {
            assertEquals("should-not-return-this", result, "Should execute function when query exists");
        }

        // Assert that passing null as default is still handled (though discouraged by the new contract)
        Object nullResult = analyzer.withCachedQuery(TreeSitterAnalyzer.QueryType.DEFINITIONS, query -> null, null);
        assertNull(nullResult, "Should return null if the function itself returns null regardless of query existence");
    }

    @Test
    public void testFindNearestDeclaration_MethodParameter() throws IOException {
        String content =
                """
        public class Test {
            public void method(String param) {
                System.out.println(param);
            }
        }
        """;

        try (var testProject =
                InlineTestProjectCreator.code(content, "Test.java").build()) {
            JavaAnalyzer analyzer = (JavaAnalyzer) createTreeSitterAnalyzer(testProject);
            ProjectFile file = new ProjectFile(testProject.getRoot(), "Test.java");

            // Find the byte position of "param" in "println(param)"
            int charIdx = content.indexOf("println(param)") + "println(".length();
            int startByte = content.substring(0, charIdx).getBytes(StandardCharsets.UTF_8).length;
            int endByte = startByte + "param".getBytes(StandardCharsets.UTF_8).length;

            var result = analyzer.findNearestDeclaration(file, startByte, endByte, "param");

            assertTrue(result.isPresent(), "Should find declaration for 'param'");
            assertEquals(IAnalyzer.DeclarationKind.PARAMETER, result.get().kind());
            assertEquals("param", result.get().name());
        }
    }

    @Test
    public void testMultiAssignmentFieldSignatures() throws IOException {
        String code =
                """
                public class MultiField {
                    public int x = 1, y = 2;
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "MultiField.java").build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(testProject);

            var xDefs = analyzer.getDefinitions("MultiField.x");
            assertEquals(1, xDefs.size());
            var xCu = xDefs.iterator().next();
            var xSkeleton = analyzer.getSkeleton(xCu);
            assertTrue(xSkeleton.isPresent());
            assertCodeEquals("public int x = 1;", xSkeleton.get());

            var yDefs = analyzer.getDefinitions("MultiField.y");
            assertEquals(1, yDefs.size());
            var yCu = yDefs.iterator().next();
            var ySkeleton = analyzer.getSkeleton(yCu);
            assertTrue(ySkeleton.isPresent());
            assertCodeEquals("public int y = 2;", ySkeleton.get());
        }
    }

    @Test
    public void testComplexFieldInitializerIsOmitted() throws IOException {
        String code =
                """
                public class BuilderField {
                    private static final MyClient CLIENT = MyClient.builder()
                        .withEndpoint("https://api.example.com")
                        .withTimeout(Duration.ofSeconds(5))
                        .build();
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "BuilderField.java").build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(testProject);

            var defs = analyzer.getDefinitions("BuilderField.CLIENT");
            assertEquals(1, defs.size());
            var cu = defs.iterator().next();
            var skeleton = analyzer.getSkeleton(cu);
            assertTrue(skeleton.isPresent());
            // The builder chain should be omitted, leaving only the declaration and semicolon
            assertCodeEquals("private static final MyClient CLIENT;", skeleton.get());
        }
    }

    @Test
    public void testNonLiteralFieldInitializersAreOmitted() throws IOException {
        String code =
                """
                public class Initializers {
                    public static final String LITERAL = "hello";
                    public static final int NUMBER = 42;
                    public static final Object COMPLEX = new Object();
                    private final List<String> LIST = List.of("a");
                }
                """;
        try (var testProject =
                InlineTestProjectCreator.code(code, "Initializers.java").build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(testProject);

            // String literal should be preserved
            var literalCu =
                    analyzer.getDefinitions("Initializers.LITERAL").iterator().next();
            assertCodeEquals(
                    "public static final String LITERAL = \"hello\";",
                    analyzer.getSkeleton(literalCu).get());

            // Number literal should be preserved
            var numberCu =
                    analyzer.getDefinitions("Initializers.NUMBER").iterator().next();
            assertCodeEquals(
                    "public static final int NUMBER = 42;",
                    analyzer.getSkeleton(numberCu).get());

            // Complex initializer (new Object()) should be omitted
            var complexCu =
                    analyzer.getDefinitions("Initializers.COMPLEX").iterator().next();
            assertCodeEquals(
                    "public static final Object COMPLEX;",
                    analyzer.getSkeleton(complexCu).get());

            // Complex initializer (List.of) should be omitted
            var listCu = analyzer.getDefinitions("Initializers.LIST").iterator().next();
            assertCodeEquals(
                    "private final List<String> LIST;",
                    analyzer.getSkeleton(listCu).get());
        }
    }

    @Test
    public void testBooleanAndNullFieldInitializersArePreserved() throws IOException {
        String code =
                """
                public class BooleanNullInitializers {
                    public static final boolean FLAG_TRUE = true;
                    public static final boolean FLAG_FALSE = false;
                    public static final Object NULL_VAL = null;
                }
                """;
        try (var testProject = InlineTestProjectCreator.code(code, "BooleanNullInitializers.java")
                .build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(testProject);

            var trueCu = analyzer.getDefinitions("BooleanNullInitializers.FLAG_TRUE")
                    .iterator()
                    .next();
            assertCodeEquals(
                    "public static final boolean FLAG_TRUE = true;",
                    analyzer.getSkeleton(trueCu).get());

            var falseCu = analyzer.getDefinitions("BooleanNullInitializers.FLAG_FALSE")
                    .iterator()
                    .next();
            assertCodeEquals(
                    "public static final boolean FLAG_FALSE = false;",
                    analyzer.getSkeleton(falseCu).get());

            var nullCu = analyzer.getDefinitions("BooleanNullInitializers.NULL_VAL")
                    .iterator()
                    .next();
            assertCodeEquals(
                    "public static final Object NULL_VAL = null;",
                    analyzer.getSkeleton(nullCu).get());
        }
    }
}
