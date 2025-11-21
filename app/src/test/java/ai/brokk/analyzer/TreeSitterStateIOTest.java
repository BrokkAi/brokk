package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class TreeSitterStateIOTest {

    @Test
    void roundTripJavaAnalyzerState() throws Exception {
        // Build an ephemeral project with a single Java file; project cleans itself up when closed
        var builder = InlineTestProjectCreator.code(
                """
                        package com.example;

                        public class Hello {
                            public int add(int a, int b) { return a + b; }
                        }
                        """,
                "src/main/java/com/example/Hello.java");

        try (IProject project = builder.build()) {
            // Build analyzer and assert we have declarations
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            var decls = analyzer.getAllDeclarations();
            assertFalse(decls.isEmpty(), "Expected at least one declaration from analyzer");

            String expectedFq = "com.example.Hello";
            assertTrue(
                    decls.stream().anyMatch(cu -> cu.fqName().equals(expectedFq)),
                    "Expected fqName " + expectedFq + " in declarations");

            // Save analyzer state to the standard per-language storage location
            Path storage = Languages.JAVA.getStoragePath(project);
            TreeSitterStateIO.save(analyzer.snapshotState(), storage);
            assertTrue(Files.exists(storage), "Expected analyzer state file to exist: " + storage);

            // Reload analyzer from disk and validate equivalence of declarations
            IAnalyzer loaded = Languages.JAVA.loadAnalyzer(project);
            var reDecls = loaded.getAllDeclarations();
            assertFalse(reDecls.isEmpty(), "Reloaded declarations should not be empty");

            Set<String> origFq =
                    new HashSet<>(decls.stream().map(CodeUnit::fqName).toList());
            Set<String> reFq =
                    new HashSet<>(reDecls.stream().map(CodeUnit::fqName).toList());
            assertEquals(origFq, reFq, "FQNs after reload should match original");
            assertTrue(
                    reDecls.stream().anyMatch(cu -> cu.fqName().equals(expectedFq)),
                    "Reloaded analyzer missing expected fqName " + expectedFq);
        }
    }

    @Test
    void roundTripCppAnalyzerRebuildsParseTreesOnUpdate() throws Exception {
        // TreeSitterStateIO omits parse tree persistence; this test ensures that after deserialization
        // an update on a changed file lazily reconstructs the missing parse tree via treeOf(...).

        var builder = InlineTestProjectCreator.code(
                """
                    int add(int a, int b) { return a + b; }
                    int main() { return add(1, 2); }
                    """,
                "main.cpp");

        try (IProject project = builder.build()) {
            // Build C++ analyzer and assert declarations/skeletons exist before persistence
            CppAnalyzer analyzer = new CppAnalyzer(project);

            ProjectFile cppFile = new ProjectFile(project.getRoot(), Path.of("main.cpp"));
            assertFalse(analyzer.getSkeletons(cppFile).isEmpty(), "Expected C++ skeletons before save");
            assertNotNull(analyzer.treeOf(cppFile), "Expected parse tree before save");

            // Save analyzer state
            Path storage = Languages.CPP_TREESITTER.getStoragePath(project);
            TreeSitterStateIO.save(analyzer.snapshotState(), storage);
            assertTrue(Files.exists(storage), "Expected analyzer state file to exist: " + storage);

            // Load analyzer; parsed trees are intentionally omitted by TreeSitterStateIO
            IAnalyzer loaded = Languages.CPP_TREESITTER.loadAnalyzer(project);
            assertTrue(loaded instanceof CppAnalyzer, "Loaded analyzer is not CppAnalyzer");
            CppAnalyzer loadedCpp = (CppAnalyzer) loaded;

            // After deserialization, treeOf(...) should be null (not persisted)
            assertNull(loadedCpp.treeOf(cppFile), "Expected no parse tree after deserialization");

            // Modify the C++ file on disk
            Files.writeString(
                    project.getRoot().resolve("main.cpp"),
                    """
                        int add(int a, int b) { return a + b + 1; }
                        int main() { return add(1, 2); }
                        """);

            // Trigger an update; this should rebuild the missing parse tree on demand without exceptions
            Set<ProjectFile> changed = new HashSet<>();
            changed.add(cppFile);
            IAnalyzer updated = loadedCpp.update(changed);
            assertTrue(updated instanceof CppAnalyzer, "Updated analyzer is not CppAnalyzer");
            CppAnalyzer updatedCpp = (CppAnalyzer) updated;

            // Verify treeOf(...) now returns a non-null parse tree
            var rebuiltTree = updatedCpp.treeOf(cppFile);
            assertNotNull(rebuiltTree, "treeOf should return a non-null TSTree after update");

            // Also validate we can still get skeletons for the modified file
            assertFalse(updatedCpp.getSkeletons(cppFile).isEmpty(), "Expected C++ skeletons after update");
        }
    }
}
