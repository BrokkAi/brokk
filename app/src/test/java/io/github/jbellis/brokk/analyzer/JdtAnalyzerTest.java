package io.github.jbellis.brokk.analyzer;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

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
        analyzer.findSymbolsInWorkspace("")
                .thenApply((list) -> {
                    System.out.println(list);
                    return null;
                })
                .join();

        analyzer.getSymbolsInFile(Paths.get("A.java"))
                .thenApply((list) -> {
                    System.out.println(list);
                    return null;
                })
                .join();

        assert (analyzer.isClassInProject("A"));

        assert (!analyzer.isClassInProject("java.nio.filename.Path"));
        assert (!analyzer.isClassInProject("org.foo.Bar"));
    }


}
