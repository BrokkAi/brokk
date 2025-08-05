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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TreeSitterAnalyzerJavaTest {

    private final static Logger logger = LoggerFactory.getLogger(TreeSitterAnalyzerJavaTest.class);

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
                  }
                  public static class AInnerStatic {
                  }
                }
                """.trim().stripIndent();
        assertEquals(expected, skeleton);
    }
}

