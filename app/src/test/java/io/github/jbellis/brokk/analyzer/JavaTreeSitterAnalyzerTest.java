package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.testutil.TestProject;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JavaTreeSitterAnalyzerTest {

    private final static Logger logger = LoggerFactory.getLogger(JavaTreeSitterAnalyzerTest.class);

    @Nullable
    private static JavaTreeSitterAnalyzer analyzer;
    @Nullable
    private static TestProject testProject;

    @BeforeAll
    public static void setup() throws IOException {
        final var testPath = Path.of("src/test/resources/testcode-java").toAbsolutePath().normalize();
        assertTrue(Files.exists(testPath), "Test resource directory 'testcode-java' not found.");
        testProject = new TestProject(testPath, Language.JAVA);
        logger.debug("Setting up analyzer with test code from {}", testPath.toAbsolutePath().normalize());
        analyzer = new JavaTreeSitterAnalyzer(testProject, new HashSet<>());
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

        final var expected = """
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
  public static int field1;
  private String field2;
  void methodD1()
  void methodD2()
  private static class DSubStatic {
  }
  private class DSub {
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
                  public static int field1;
                  private String field2;
                  [... methods not shown ...]
                }
                """.trim().stripIndent();
        assertEquals(expected, skeleton);
    }

    @Test
    public void getAllClassesTest() {
        final var classes = analyzer.getAllDeclarations().stream().map(CodeUnit::fqName).sorted().toList();
        final var expected = List.of("A", "A$AInner", "A$AInner$AInnerInner", "A$AInnerStatic",
                "AnonymousUsage", "AnonymousUsage$NestedClass", "B", "BaseClass", "C", "C$Foo", "CamelClass",
                "CyclicMethods", "D", "D$DSub", "D$DSubStatic", "E", "F", "Foo", "Interface", "MethodReturner",
                "UseE", "UsePackaged", "XExtendsY");
        assertEquals(expected, classes);
    }

}

