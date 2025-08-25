package io.github.jbellis.brokk.analyzer.linting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.wildfly.common.Assert.assertTrue;

import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.JdtAnalyzer;
import io.github.jbellis.brokk.analyzer.LintResult;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class JdtAnalyzerLintingTest {

    @TempDir
    Path tempDir;

    @Nullable
    private static JdtAnalyzer analyzer = null;

    private static IProject testProject;

    private static final Logger logger = LoggerFactory.getLogger(JdtAnalyzerLintingTest.class);

    @BeforeEach
    void setUp() throws IOException {
        tempDir = tempDir.toRealPath();
        Path testFile = tempDir.resolve("ErrorTest.java");
        Files.writeString(
                testFile,
                """
                        public class ErrorTest {
                            private UndefinedType field; // Error: undefined type

                            public void methodWithErrors() {
                                nonExistentMethod(); // Error: undefined method
                                int unused = 5; // Warning: unused variable
                            }

                            public void validMethod() {
                                System.out.println("This is valid");
                            }

                        }
                        """);

        Path validFile = tempDir.resolve("ValidTest.java");
        Files.writeString(
                validFile,
                """
                        public class ValidTest {

                            public void validMethod() {
                                System.out.println("This is completely valid");
                            }

                        }
                        """);

        testProject = new IProject() {

            @Override
            public Path getRoot() {
                return tempDir.toAbsolutePath();
            }

            @Override
            public Set<ProjectFile> getAllFiles() {
                var files = tempDir.toFile().listFiles();
                if (files == null) {
                    return Collections.emptySet();
                }
                return Arrays.stream(files)
                        .map(file -> new ProjectFile(tempDir, file.toPath()))
                        .collect(Collectors.toSet());
            }
        };
        logger.debug("Setting up analyzer with test code from {}", testProject.getRoot());
        analyzer = new JdtAnalyzer(testProject);
    }

    @AfterAll
    public static void teardown() throws IOException {
        if (analyzer != null) {
            analyzer.close();
        }

        try {
            testProject.close();
        } catch (Exception e) {
            logger.error("Exception encountered while closing the test project", e);
        }
    }

    @Test
    void testLintFilesWithoutErrors() {
        assertNotNull(analyzer, "Analyzer should be initialized");
        // Create a Java file with syntax error
        Path javaFile = tempDir.resolve("ValidTest.java");
        ProjectFile projectFile =
                new ProjectFile(tempDir, javaFile.getFileName().toString());

        // Test linting
        LintResult results = analyzer.lintFiles(List.of(projectFile));
        assertFalse(results.hasErrors());
    }

    @Test
    void testLintFilesWithErrors() {
        assertNotNull(analyzer, "Analyzer should be initialized");
        // Create a Java file with syntax error
        Path javaFile = tempDir.resolve("ErrorTest.java");
        ProjectFile projectFile =
                new ProjectFile(tempDir, javaFile.getFileName().toString());

        // Test linting
        LintResult results = analyzer.lintFiles(List.of(projectFile));
        assertTrue(results.hasErrors());

        final var errors = results.getErrors();
        assertFalse(errors.isEmpty());
    }
}
