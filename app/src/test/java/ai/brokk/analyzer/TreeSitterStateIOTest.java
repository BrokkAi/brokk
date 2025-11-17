package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

public class TreeSitterStateIOTest {

    /**
     * Minimal test project providing just enough of IProject for TreeSitterAnalyzer.
     */
    static final class TestProject implements IProject {
        private final Path root;
        private final Set<ProjectFile> files;

        TestProject(Path root, Set<ProjectFile> files) {
            this.root = root;
            this.files = files;
        }

        @Override
        public Path getRoot() {
            return root;
        }

        @Override
        public Set<ProjectFile> getAnalyzableFiles(Language language) {
            // Return only files that match the language's extensions
            var exts = language.getExtensions();
            var result = new LinkedHashSet<ProjectFile>();
            for (var pf : files) {
                var ext = pf.extension();
                var normalized = ext.startsWith(".") ? ext.substring(1) : ext;
                if (exts.contains(normalized)) {
                    result.add(pf);
                }
            }
            return result;
        }

        @Override
        public Set<String> getExcludedDirectories() {
            return Collections.emptySet();
        }
    }

    @Test
    void roundTripJavaAnalyzerState() throws Exception {
        Path root = Files.createTempDirectory("brokk-java-proj");
        try {
            // Prepare a simple Java source file
            Path pkgDir = root.resolve("src").resolve("main").resolve("java").resolve("com").resolve("example");
            Files.createDirectories(pkgDir);
            Path javaFilePath = pkgDir.resolve("Hello.java");
            String src = """
                    package com.example;

                    public class Hello {
                        public int add(int a, int b) { return a + b; }
                    }
                    """;
            Files.writeString(javaFilePath, src);

            // Build project file set
            ProjectFile pf = new ProjectFile(root, root.relativize(javaFilePath));
            Set<ProjectFile> filesSet = Set.of(pf);
            IProject project = new TestProject(root, filesSet);

            // Build analyzer and assert we have declarations
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            var decls = analyzer.getAllDeclarations();
            assertFalse(decls.isEmpty(), "Expected at least one declaration from analyzer");

            String expectedFq = "com.example.Hello";
            assertTrue(
                    decls.stream().anyMatch(cu -> cu.fqName().equals(expectedFq)),
                    "Expected fqName " + expectedFq + " in declarations"
            );

            // Save analyzer state to the standard per-language storage location
            Path storage = Languages.JAVA.getStoragePath(project);
            TreeSitterStateIO.save(analyzer.snapshotState(), storage);
            assertTrue(Files.exists(storage), "Expected analyzer state file to exist: " + storage);

            // Reload analyzer from disk and validate equivalence of declarations
            IAnalyzer loaded = Languages.JAVA.loadAnalyzer(project);
            var reDecls = loaded.getAllDeclarations();
            assertFalse(reDecls.isEmpty(), "Reloaded declarations should not be empty");

            Set<String> origFq = new HashSet<>(decls.stream().map(CodeUnit::fqName).toList());
            Set<String> reFq = new HashSet<>(reDecls.stream().map(CodeUnit::fqName).toList());
            assertEquals(origFq, reFq, "FQNs after reload should match original");
            assertTrue(
                    reDecls.stream().anyMatch(cu -> cu.fqName().equals(expectedFq)),
                    "Reloaded analyzer missing expected fqName " + expectedFq
            );
        } finally {
            // Best-effort cleanup of temp dir
            try {
                recursiveDelete(root);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void recursiveDelete(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
