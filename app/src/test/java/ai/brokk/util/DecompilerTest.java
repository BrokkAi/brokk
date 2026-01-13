package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecompilerTest {

    @TempDir
    Path tempDir;

    @Test
    void decompileJarBlocking_OverwriteFalse_ReturnsExistingResult() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        
        Path jarPath = tempDir.resolve("test-lib.jar");
        createDummyJar(jarPath);

        Path outputDir = projectRoot.resolve(".brokk/dependencies/test-lib");
        Files.createDirectories(outputDir);
        Path existingFile = outputDir.resolve("Existing.java");
        Files.writeString(existingFile, "public class Existing {}");

        // Execute with overwrite = false
        Optional<Decompiler.DecompileResult> resultOpt = Decompiler.decompileJarBlocking(jarPath, projectRoot, false);

        assertTrue(resultOpt.isPresent());
        Decompiler.DecompileResult result = resultOpt.get();
        assertEquals(outputDir, result.outputDir());
        assertEquals(1, result.filesExtracted());
        assertFalse(result.usedSources());
        assertTrue(Files.exists(existingFile), "Existing file should still exist when overwrite=false");
    }

    @Test
    void decompileJarBlocking_OverwriteTrue_ReplacesDirectory() throws IOException {
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);

        // Create a main JAR and a sibling sources JAR to avoid Fernflower decompilation errors
        Path jarPath = tempDir.resolve("test-lib.jar");
        Files.createFile(jarPath);
        Path sourcesJarPath = tempDir.resolve("test-lib-sources.jar");
        createDummySourcesJar(sourcesJarPath);

        Path outputDir = projectRoot.resolve(".brokk/dependencies/test-lib");
        Files.createDirectories(outputDir);
        Path existingFile = outputDir.resolve("OldFile.java");
        Files.writeString(existingFile, "public class OldFile {}");

        // Execute with overwrite = true
        Optional<Decompiler.DecompileResult> resultOpt = Decompiler.decompileJarBlocking(jarPath, projectRoot, true);

        assertTrue(resultOpt.isPresent());
        Decompiler.DecompileResult result = resultOpt.get();
        assertEquals(outputDir, result.outputDir());
        assertTrue(result.usedSources(), "Should have used the sibling sources JAR");
        assertFalse(Files.exists(existingFile), "Old directory should have been deleted when overwrite=true");
        assertTrue(result.filesExtracted() >= 1, "Should have extracted at least one file from the sources JAR");
        assertTrue(Files.exists(outputDir.resolve("com/example/Test.java")));
    }

    private void createDummyJar(Path jarPath) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            ZipEntry entry = new ZipEntry("Existing.java");
            zos.putNextEntry(entry);
            zos.write("public class Existing {}".getBytes());
            zos.closeEntry();
        }
    }

    private void createDummySourcesJar(Path jarPath) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jarPath))) {
            ZipEntry entry = new ZipEntry("com/example/Test.java");
            zos.putNextEntry(entry);
            zos.write("package com.example; public class Test {}".getBytes());
            zos.closeEntry();
        }
    }
}
