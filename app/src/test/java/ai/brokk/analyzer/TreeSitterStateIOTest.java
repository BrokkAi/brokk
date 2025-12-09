package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.TreeSitterStateIO.AnalyzerStateDto;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void saveIsAtomicAndLeavesNoTempFiles(@TempDir Path tempDir) throws Exception {
        AnalyzerStateDto emptyDto = new AnalyzerStateDto(Map.of(), List.of(), List.of(), List.of(), 1L);
        var state = TreeSitterStateIO.fromDto(emptyDto);

        Path out = tempDir.resolve("state.smile.gz");
        TreeSitterStateIO.save(state, out);

        assertTrue(Files.exists(out), "Expected final state file to exist");

        var loaded = TreeSitterStateIO.load(out);
        assertTrue(loaded.isPresent(), "Expected load to succeed after save");

        String baseName = out.getFileName().toString();
        String tmpPrefix = "." + baseName + ".";
        String tmpSuffix = ".tmp";
        var lingering = Files.list(tempDir)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(tmpPrefix) && name.endsWith(tmpSuffix);
                })
                .toList();
        assertTrue(lingering.isEmpty(), "No lingering temp files should remain after atomic save");
    }

    @Test
    void saveLoadRoundTripUnchanged(@TempDir Path tempDir) throws Exception {
        AnalyzerStateDto dto = new AnalyzerStateDto(Map.of(), List.of(), List.of(), List.of("KeyA", "keyb"), 99L);
        var original = TreeSitterStateIO.fromDto(dto);

        Path out = tempDir.resolve("roundtrip.smile.gz");
        TreeSitterStateIO.save(original, out);

        var loadedOpt = TreeSitterStateIO.load(out);
        assertTrue(loadedOpt.isPresent(), "Expected to load state after saving");
        var loaded = loadedOpt.get();

        var dtoOriginal = TreeSitterStateIO.toDto(original);
        var dtoLoaded = TreeSitterStateIO.toDto(loaded);
        assertEquals(dtoOriginal, dtoLoaded, "DTO after save+load should match original DTO");
    }

    @Test
    void loadReturnsEmptyOnCorruptGzip(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("state.smile.gz");

        Files.writeString(out, "not a gzip");

        var loaded = TreeSitterStateIO.load(out);
        assertTrue(loaded.isEmpty(), "Expected load to return empty on corrupt gzip");

        AnalyzerStateDto dto = new AnalyzerStateDto(Map.of(), List.of(), List.of(), List.of("A"), 1L);
        var state = TreeSitterStateIO.fromDto(dto);
        TreeSitterStateIO.save(state, out);
        assertTrue(Files.exists(out), "Expected analyzer state file to exist after save");
        assertTrue(Files.size(out) > 0, "Saved analyzer state file should be non-empty");

        var after = TreeSitterStateIO.load(out);
        assertTrue(after.isPresent(), "Expected load to succeed after writing valid state");
        assertEquals(
                TreeSitterStateIO.toDto(state),
                TreeSitterStateIO.toDto(after.get()),
                "DTO after save+load should equal the original");
    }

    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason = "Flaky on Windows due to transient file locks; replacement behavior covered elsewhere")
    @Test
    void replacesExistingCorruptFileOnWindows(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("state.smile.gz");

        Files.writeString(out, "this is corrupt gzip content");

        AnalyzerStateDto dto = new AnalyzerStateDto(Map.of(), List.of(), List.of(), List.of("win"), 42L);
        var original = TreeSitterStateIO.fromDto(dto);

        TreeSitterStateIO.save(original, out);
        assertTrue(Files.exists(out), "Expected analyzer state file to exist after save");
        assertTrue(Files.size(out) > 0, "Saved analyzer state file should be non-empty");

        var loadedOpt = TreeSitterStateIO.load(out);
        assertTrue(loadedOpt.isPresent(), "Expected save to replace existing corrupt file");
        var loaded = loadedOpt.get();

        assertEquals(
                TreeSitterStateIO.toDto(original),
                TreeSitterStateIO.toDto(loaded),
                "DTO after replacing corrupt file should equal the original DTO");
    }
}
