package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeUnitExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void testExtractsCodeUnitsToCsv() throws IOException {
        // Setup a small test project
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Path javaFile = projectRoot.resolve("MyClass.java");
        Files.writeString(
                javaFile,
                """
            package com.example;
            public class MyClass {
                private int myField;
                public void myMethod() {}
            }
            """);

        Path csvOutput = tempDir.resolve("output.csv");

        // Run extractor
        CodeUnitExtractor.extract(projectRoot, csvOutput);

        // Verify CSV exists and contains expected content
        assertTrue(Files.exists(csvOutput), "CSV output file should exist");
        List<String> lines = Files.readAllLines(csvOutput);

        // Expected output is sorted: CLASS, FIELD, FUNCTION, MODULE
        // Format: kind,fqName,shortName,identifier
        assertEquals(4, lines.size());
        assertEquals("CLASS,com.example.MyClass,MyClass,MyClass", lines.get(0));
        assertEquals("FIELD,com.example.MyClass.myField,MyClass.myField,myField", lines.get(1));
        assertEquals("FUNCTION,com.example.MyClass.myMethod,MyClass.myMethod,myMethod", lines.get(2));
        assertEquals("MODULE,com.example,example,example", lines.get(3));
    }

    @Test
    void testInvalidDirectoryThrowsException() {
        Path nonExistent = tempDir.resolve("non-existent");
        Path csvOutput = tempDir.resolve("output.csv");

        assertThrows(IllegalArgumentException.class, () -> CodeUnitExtractor.extract(nonExistent, csvOutput));
    }

    @Test
    void testWritePermissionIssue() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);

        // Create a directory where the file should be, making it unwritable as a file
        Path csvOutput = tempDir.resolve("unwritable_dir");
        Files.createDirectories(csvOutput);

        assertThrows(IOException.class, () -> CodeUnitExtractor.extract(projectRoot, csvOutput));
    }
}
