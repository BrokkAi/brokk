package io.github.jbellis.brokk.analyzer;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        analyzer.findMethodSymbol("A", "method2").thenApply(methods -> {
            System.out.println(methods); 
            return null;
        }).join();

//        final var sourceOpt1  = analyzer.getMethodSource("com.ayon.service.RunningLomboks.tryExample1");
        final var sourceOpt  = analyzer.getMethodSource("A.method2");
        assert(sourceOpt.isPresent());
        final var source = sourceOpt.get().trim().stripIndent();
        // TODO: return all methods
        final String expected = """
                public String method2(String input) {
                        return "prefix_" + input;
                    }
                """.trim().stripIndent();

        assertEquals(expected, source);
    }


}
