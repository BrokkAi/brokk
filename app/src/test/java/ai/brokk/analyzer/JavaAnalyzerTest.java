package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.TestProject;
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

public class JavaAnalyzerTest {

    private static final Logger logger = LoggerFactory.getLogger(JavaAnalyzerTest.class);

    @Nullable
    private static JavaAnalyzer analyzer;

    @Nullable
    private static TestProject testProject;

    @BeforeAll
    public static void setup() throws IOException {
        final var testPath =
                Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(testPath), "Test resource directory 'testcode-java' not found.");
        testProject = new TestProject(testPath, Languages.JAVA);
        logger.debug(
                "Setting up analyzer with test code from {}",
                testPath.toAbsolutePath().normalize());
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
        final var sourceOpt = AnalyzerUtil.getMethodSource(analyzer, "A.method2", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get().trim();
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
                        .trim();

        assertEquals(expected, source);
    }

    @Test
    public void extractMethodSourceNested() {
        final var sourceOpt = AnalyzerUtil.getMethodSource(analyzer, "A.AInner.AInnerInner.method7", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get().trim();

        final var expected =
                """
                public void method7() {
                                System.out.println("hello");
                            }
                """
                        .trim();

        assertEquals(expected, source);
    }

    @Test
    public void extractMethodSourceConstructor() {
        final var sourceOpt = AnalyzerUtil.getMethodSource(analyzer, "B.B", true); // TODO: Should we handle <init>?
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get().trim();

        final var expected =
                """
                        public B() {
                                System.out.println("B constructor");
                            }
                        """
                        .trim();

        assertEquals(expected, source);
    }

    @Test
    public void getClassSourceTest() {
        final var sourceOpt = AnalyzerUtil.getClassSource(analyzer, "A", true);
        assertTrue(sourceOpt.isPresent());
        final var source = sourceOpt.get();
        // Verify the source contains class definition and methods
        assertTrue(source.contains("class A {"));
        assertTrue(source.contains("void method1()"));
        assertTrue(source.contains("public String method2(String input)"));
    }

    @Test
    public void getClassSourceNestedTest() {
        final var sourceOpt = AnalyzerUtil.getClassSource(analyzer, "A.AInner", true);
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
                """
                        .trim();
        assertEquals(expected, source);
    }

    @Test
    public void getClassSourceTwiceNestedTest() {
        final var sourceOpt = AnalyzerUtil.getClassSource(analyzer, "A.AInner.AInnerInner", true);
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
                """
                        .trim();
        assertEquals(expected, source);
    }

    @Test
    public void getClassSourceNotFoundTest() {
        var opt = AnalyzerUtil.getClassSource(analyzer, "A.NonExistent", true);
        assertTrue(opt.isEmpty());
    }

    @Test
    public void getClassSourceNonexistentTest() {
        var opt = AnalyzerUtil.getClassSource(analyzer, "NonExistentClass", true);
        assertTrue(opt.isEmpty());
    }

    @Test
    public void getSkeletonTestA() {
        final var skeletonOpt = AnalyzerUtil.getSkeleton(analyzer, "A");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get().trim();

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
        assertEquals(expected, skeleton);
    }

    @Test
    public void getSkeletonTestD() {
        final var skeletonOpt = AnalyzerUtil.getSkeleton(analyzer, "D");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get().trim();

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
        assertEquals(expected, skeleton);
    }

    @Test
    public void getSkeletonTestEnum() {
        final var skeletonOpt = AnalyzerUtil.getSkeleton(analyzer, "EnumClass");
        assertTrue(skeletonOpt.isPresent());
        final var skeleton = skeletonOpt.get().trim();

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
        final var skeleton = skeletonOpt.get().trim();

        final var expected =
                """
                public class D {
                  public static int field1;
                  private String field2;
                  [...]
                }
                """
                        .trim();
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
                "CustomAnnotation",
                "CyclicMethods",
                "D",
                "D.DSub",
                "D.DSubStatic",
                "E",
                "EnumClass",
                "F",
                "Interface",
                "MethodReferenceUsage",
                "MethodReturner",
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
        final var classes = analyzer.getDeclarations(file);

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
                CodeUnit.field(file, "", "D.field2"));
        assertEquals(expected, classes);
    }

    @Test
    public void declarationsInPackagedFileTest() {
        final var file = new ProjectFile(testProject.getRoot(), "Packaged.java");
        final var declarations = analyzer.getDeclarations(file);
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
        final var members =
                AnalyzerUtil.getMembersInClass(analyzer, "D").stream().sorted().toList();
        final var maybeFile = AnalyzerUtil.getFileFor(analyzer, "D");
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
                        CodeUnit.cls(file, "", "D.DSubStatic"),
                        CodeUnit.cls(file, "", "D.DSub"))
                .sorted()
                .toList();
        assertEquals(expected, members);
    }

    @Test
    public void getDirectClassChildren() {
        final var maybeClassD = analyzer.getDefinition("D");
        assertTrue(maybeClassD.isPresent());
        final var maybeFile = AnalyzerUtil.getFileFor(analyzer, "D");
        assertTrue(maybeFile.isPresent());

        final var children =
                analyzer.getDirectChildren(maybeClassD.get()).stream().sorted().toList();
        final var file = maybeFile.get();

        final var expected = Stream.of(
                        // Classes
                        CodeUnit.cls(file, "", "D.DSub"),
                        CodeUnit.cls(file, "", "D.DSubStatic"),
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
        final var sourceOpt = AnalyzerUtil.getClassSource(analyzer, "AnnotatedClass", true);
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
        final var sourceOpt = AnalyzerUtil.getClassSource(analyzer, "AnnotatedClass", true);
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
        final var sourceOpt = AnalyzerUtil.getClassSource(analyzer, "AnnotatedClass", true);
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
        final var sourceOpt = AnalyzerUtil.getClassSource(analyzer, "AnnotatedClass.InnerHelper", true);
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
        final var sourceOpt = AnalyzerUtil.getMethodSource(analyzer, "AnnotatedClass.toString", true);
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
        final var sourceOpt = AnalyzerUtil.getMethodSource(analyzer, "AnnotatedClass.processValue", true);
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
        final var sourceOpt = AnalyzerUtil.getClassSource(analyzer, "CustomAnnotation", true);
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
                AnalyzerUtil.getClassSource(analyzer, "A<String>", false).isPresent(),
                "Class lookup with generics should normalize");

        // Method lookup with generics on the containing class
        assertTrue(
                AnalyzerUtil.getMethodSource(analyzer, "A<Integer>.method1", false)
                        .isPresent(),
                "Method lookup with class generics should normalize");

        // Nested classes with generics on each segment
        assertTrue(
                AnalyzerUtil.getMethodSource(
                                analyzer, "A.AInner<List<String>>.AInnerInner<Map<Integer, String>>.method7", false)
                        .isPresent(),
                "Nested class method with generics should normalize");
    }

    @Test
    public void testNormalizationHandlesAnonymousAndLocationSuffix() {
        // Location suffix without anon
        assertTrue(
                AnalyzerUtil.getMethodSource(analyzer, "A.method1:16", false).isPresent(),
                "Location suffix alone should normalize for method source lookup");

        // Anonymous with just digits
        assertTrue(
                AnalyzerUtil.getMethodSource(analyzer, "A.method6$1", false).isPresent(),
                "Anonymous digit suffix should normalize for method source lookup");
    }

    @Test
    public void testDefinitionAndSourcesWithNormalizedConstructorNames() {
        // Based on log example: Type.Type for constructor (and possibly with generics on the type)
        assertTrue(
                AnalyzerUtil.getMethodSource(analyzer, "B<B>.B", true).isPresent(),
                "Constructor lookup with generics on the type should normalize and resolve");

        // Also ensure plain constructor lookup works (control)
        assertTrue(
                AnalyzerUtil.getMethodSource(analyzer, "B.B", true).isPresent(), "Constructor lookup should resolve");
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

        assertEquals(1, topLevelUnits.size(), "Should return only the top-level class Foo");
        var topLevelClass = topLevelUnits.get(0);
        assertEquals("io.github.jbellis.brokk.Foo", topLevelClass.fqName());
        assertTrue(topLevelClass.isClass());
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
}
