package io.github.jbellis.brokk.analyzer;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
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
//        final var testPath = Path.of("/Users/dave/Workspace/test-repos/lombok-examples");
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
        final var source   = analyzer.getClassSource("A");
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
        final var source   = maybeSource.stripIndent();
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
        final var source   = maybeSource.stripIndent();
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
        final var source   = maybeSource.stripIndent();
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

}
