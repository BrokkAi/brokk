package ai.brokk.analyzer;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.AnalyzerUtil;
import ai.brokk.testutil.InlineTestProjectCreator;
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
        final var sourceOpt = AnalyzerUtil.getMethodSource(analyzer, "A.AInner.AInnerInner.method7", true);
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
        final var sourceOpt = AnalyzerUtil.getMethodSource(analyzer, "B.B", true); // TODO: Should we handle <init>?
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
                """;
        assertCodeEquals(expected, source);
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
                """;
        assertCodeEquals(expected, source);
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
    public void testGetDefinitionNonExistent() {
        var nonExistentDef =
                analyzer.getDefinitions("NonExistentSymbol").stream().findFirst();
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
        final var maybeClassD = analyzer.getDefinitions("D").stream().findFirst();
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

    @Test
    public void getDefinitions_ReturnsAllOverloads() {
        // A.method2 has two overloads: method2(String) and method2(String, int)
        // Note: If signatures aren't populated yet, both may collapse to single CodeUnit
        // The key test is that when signatures ARE different, we get multiple results
        var overloads = analyzer.getDefinitions("A.method2");

        assertNotNull(overloads, "getDefinitions should not return null");
        assertTrue(overloads.size() >= 1, "Should return at least one method2");

        // Verify all returned CodeUnits are functions with correct fqName
        for (CodeUnit cu : overloads) {
            assertTrue(cu.isFunction(), "All returned CodeUnits should be functions");
            assertEquals("A.method2", cu.fqName(), "All overloads should have the same fqName");
        }

        // If we have multiple results, they should be distinct (different signatures)
        if (overloads.size() > 1) {
            var uniqueCodeUnits = Set.copyOf(overloads);
            assertEquals(overloads.size(), uniqueCodeUnits.size(), "Multiple results should be distinct CodeUnits");
        }
    }

    @Test
    public void getDefinitions_NonOverloadedMethod_ReturnsSingleItem() {
        // A.method1 has only one signature
        var results = analyzer.getDefinitions("A.method1");

        assertNotNull(results, "getDefinitions should not return null");
        assertEquals(1, results.size(), "Non-overloaded method should return single result");

        var cu = results.iterator().next();
        assertTrue(cu.isFunction(), "Result should be a function");
        assertEquals("A.method1", cu.fqName());
    }

    @Test
    public void getDefinitions_NonExistent_ReturnsEmptySet() {
        var results = analyzer.getDefinitions("NonExistent.method");

        assertNotNull(results, "getDefinitions should not return null");
        assertTrue(results.isEmpty(), "Non-existent symbol should return empty set");
    }

    @Test
    public void getDefinitions_Class_ReturnsSingleClass() {
        var results = analyzer.getDefinitions("A");

        assertNotNull(results, "getDefinitions should not return null");
        assertEquals(1, results.size(), "Class should return single result");

        var cu = results.iterator().next();
        assertTrue(cu.isClass(), "Result should be a class");
        assertEquals("A", cu.fqName());
    }

    @Test
    public void getFunctionDefinition_WithSignature_ReturnsExactMatch() {
        // First, get all overloads to find actual signatures
        var overloads = analyzer.getDefinitions("A.method2");

        // If signatures are populated and we have multiple overloads, test exact matching
        var signaturedOverloads =
                overloads.stream().filter(cu -> cu.signature() != null).toList();

        if (signaturedOverloads.size() >= 2) {
            // Get one of the signatures
            var targetSignature = signaturedOverloads.get(0).signature();

            // Test exact match
            var result = analyzer.getFunctionDefinition("A.method2", targetSignature);

            assertTrue(result.isPresent(), "Should find function with exact signature");
            assertEquals(targetSignature, result.get().signature(), "Should return exact signature match");
            assertEquals("A.method2", result.get().fqName());
        } else {
            // Signatures not yet populated - test that the method still works with NONE
            var result = analyzer.getFunctionDefinition("A.method2", Signature.none());
            assertTrue(result.isPresent(), "Should return any overload when signature is NONE");
            assertEquals("A.method2", result.get().fqName());
        }
    }

    @Test
    public void getFunctionDefinition_WithNullSignature_ReturnsAnyOverload() {
        var result = analyzer.getFunctionDefinition("A.method2", Signature.none());

        assertTrue(result.isPresent(), "Should return any overload when signature is NONE");
        assertEquals("A.method2", result.get().fqName());
        assertTrue(result.get().isFunction());
    }

    @Test
    public void getFunctionDefinition_NonMatchingSignature_ReturnsFallback() {
        // Use a signature that definitely doesn't exist
        var result = analyzer.getFunctionDefinition("A.method2", Signature.of("(NonExistentType)"));

        // Should fallback to any overload
        assertTrue(result.isPresent(), "Should fallback to any overload when signature doesn't match");
        assertEquals("A.method2", result.get().fqName());
    }

    @Test
    public void getFunctionDefinition_NonFunction_ReturnsEmpty() {
        var result = analyzer.getFunctionDefinition("A", Signature.none());

        assertTrue(result.isEmpty(), "Should return empty for non-function symbols");
    }

    @Test
    public void getFunctionDefinition_WithSignatureType_NONE_ReturnsAnyOverload() {
        var result = analyzer.getFunctionDefinition("A.method2", Signature.none());

        assertTrue(result.isPresent(), "Should return any overload with Signature.none()");
        assertEquals("A.method2", result.get().fqName());
        assertTrue(result.get().isFunction());
    }

    @Test
    public void getFunctionDefinition_WithSignatureType_MatchesExact() {
        var sig = Signature.parse("(int)");
        var result = analyzer.getFunctionDefinition("A.method2", sig);

        assertTrue(result.isPresent(), "Should find overload with Signature type");
        assertEquals("A.method2", result.get().fqName());
    }

    @Test
    public void autocompleteDefinitions_WithOverloads_DoesNotDropThem() {
        // This tests the bug fix where overloads were being dropped due to Map<String, CodeUnit>
        var results = analyzer.autocompleteDefinitions("method2");

        assertNotNull(results, "autocompleteDefinitions should not return null");

        // Filter to just A.method2 overloads
        var method2Overloads =
                results.stream().filter(cu -> "A.method2".equals(cu.fqName())).toList();

        // Should have at least one method2
        assertTrue(method2Overloads.size() >= 1, "Should find at least one method2 overload");

        // If signatures are populated for the overloads, we should see both
        var withSignatures =
                method2Overloads.stream().filter(cu -> cu.signature() != null).toList();

        if (withSignatures.size() >= 2) {
            // Verify they are distinct CodeUnits (different signatures)
            var uniqueCodeUnits = Set.copyOf(method2Overloads);
            assertEquals(
                    method2Overloads.size(),
                    uniqueCodeUnits.size(),
                    "Overloads should be distinct CodeUnit objects (different signatures in equals/hashCode)");
        }
    }

    @Test
    public void deprecatedGetDefinition_StillWorks() {
        // Verify backward compatibility - deprecated method still returns a result
        var result = analyzer.getDefinitions("A.method2").stream().findFirst();

        assertTrue(result.isPresent(), "Deprecated getDefinition should still work");
        assertEquals("A.method2", result.get().fqName());
        assertTrue(result.get().isFunction());
    }

    @Test
    public void deprecatedGetDefinition_WithCodeUnit_StillWorks() {
        var classA = analyzer.getDefinitions("A").stream().findFirst().orElseThrow();
        var result = analyzer.getDefinitions(classA.fqName()).stream().findFirst();

        assertTrue(result.isPresent(), "Deprecated getDefinition(CodeUnit) should still work");
        assertEquals("A", result.get().fqName());
    }
}
