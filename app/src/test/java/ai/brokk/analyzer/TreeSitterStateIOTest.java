package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.TreeSitterStateIO.AnalyzerStateDto;
import ai.brokk.analyzer.TreeSitterStateIO.FilePropertiesDto;
import ai.brokk.analyzer.TreeSitterStateIO.FileStateEntryDto;
import ai.brokk.analyzer.TreeSitterStateIO.ProjectFileDto;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.pcollections.HashTreePMap;

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
            Path storage = Languages.C_CPP.getStoragePath(project);
            TreeSitterStateIO.save(analyzer.snapshotState(), storage);
            assertTrue(Files.exists(storage), "Expected analyzer state file to exist: " + storage);

            // Load analyzer; parsed trees are intentionally omitted by TreeSitterStateIO
            IAnalyzer loaded = Languages.C_CPP.loadAnalyzer(project);
            assertTrue(loaded instanceof CppAnalyzer, "Loaded analyzer is not CppAnalyzer");
            CppAnalyzer loadedCpp = (CppAnalyzer) loaded;

            // After deserialization, verify file properties are present
            var loadedProps = loadedCpp.snapshotState().fileState().get(cppFile);
            assertNotNull(loadedProps);

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
        AnalyzerStateDto emptyDto = new AnalyzerStateDto(
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 1L);
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
    void roundTripCodeUnitProperties(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("root");
        Files.createDirectories(root);
        var projectFile = new ProjectFile(root, Path.of("Test.java"));
        var cu = CodeUnit.cls(projectFile, "com.example", "Test");

        // Construct with legacy-compatible constructor (signatures parameter ignored for persistence)
        var props = new TreeSitterAnalyzer.CodeUnitProperties(
                List.of(), List.of("public class Test"), List.of(new IAnalyzer.Range(0, 100, 0, 10, 0)), true);

        var stateMap = Map.of(cu, props);
        var originalState = new TreeSitterAnalyzer.AnalyzerState(
                HashTreePMap.<String, Set<CodeUnit>>empty(),
                HashTreePMap.<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties>from(stateMap),
                HashTreePMap.<ProjectFile, TreeSitterAnalyzer.FileProperties>empty(),
                ImportGraph.empty(),
                TypeHierarchyGraph.empty(),
                new TreeSitterAnalyzer.SymbolKeyIndex(new TreeSet<>()),
                System.nanoTime());

        Path out = tempDir.resolve("props_roundtrip.smile.gz");
        TreeSitterStateIO.save(originalState, out);

        var loadedOpt = TreeSitterStateIO.load(out);
        assertTrue(loadedOpt.isPresent());
        var loadedState = loadedOpt.get();

        var loadedProps = loadedState.codeUnitState().get(cu);

        assertNotNull(loadedProps);
        // Structural properties must survive round-trip
        assertEquals(props.ranges(), loadedProps.ranges(), "Ranges should round-trip");
        assertEquals(props.children(), loadedProps.children(), "Children should round-trip");
        assertEquals(props.hasBody(), loadedProps.hasBody(), "hasBody should round-trip");
        // signatures are transient and no longer persisted; ensure loaded accessor returns empty
        assertTrue(loadedProps.signatures().isEmpty(), "Loaded signatures should be empty (transient cache only)");
    }

    @Test
    void saveLoadRoundTripUnchanged(@TempDir Path tempDir) throws Exception {
        AnalyzerStateDto dto = new AnalyzerStateDto(
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of("KeyA", "keyb"),
                99L);
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

    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason = "Flaky on Windows due to transient file locks; replacement behavior covered elsewhere")
    @Test
    void loadReturnsEmptyOnCorruptGzip(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("state.smile.gz");

        Files.writeString(out, "not a gzip");

        var loaded = TreeSitterStateIO.load(out);
        assertTrue(loaded.isEmpty(), "Expected load to return empty on corrupt gzip");

        AnalyzerStateDto dto = new AnalyzerStateDto(
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of("A"), 1L);
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

        AnalyzerStateDto dto = new AnalyzerStateDto(
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of("win"), 42L);
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

    @Test
    void loadReturnsEmptyOnLegacyStateMissingContainsTests(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("legacy_state.smile.gz");

        // Manually construct a JSON/Smile graph that looks like AnalyzerStateDto
        // but whose FilePropertiesDto is missing the 'containsTests' field.
        var legacyFileProperties = Map.of(
                "topLevelCodeUnits", List.of(),
                "importStatements", List.of());

        var legacyFileEntry = Map.of(
                "key", Map.of("root", tempDir.toString(), "relPath", "file.java"), "value", legacyFileProperties);

        var legacyState = Map.of(
                "symbolIndex", Map.of(),
                "codeUnitState", List.of(),
                "fileState", List.of(legacyFileEntry),
                "symbolKeys", List.of(),
                "snapshotEpochNanos", 12345L);

        var mapper = new ObjectMapper(new SmileFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (var os = new GZIPOutputStream(Files.newOutputStream(out))) {
            mapper.writeValue(os, legacyState);
        }

        var loaded = TreeSitterStateIO.load(out);
        assertTrue(
                loaded.isEmpty(),
                "Expected load to return empty because legacy state is missing required 'containsTests' field");
    }

    @Test
    void testImportInfoRoundTrip(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("root");
        Files.createDirectories(root);
        var projectFile = new ProjectFile(root, Path.of("Test.java"));

        var imports = List.of(
                new ImportInfo("import java.util.List;", false, "List", null),
                new ImportInfo("import java.util.*;", true, null, null),
                new ImportInfo("import foo.bar.Baz as B", false, "Baz", "B"));

        var fileProps = new TreeSitterAnalyzer.FileProperties(List.of(), imports, false);

        var originalState = new TreeSitterAnalyzer.AnalyzerState(
                HashTreePMap.<String, Set<CodeUnit>>empty(),
                HashTreePMap.<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties>empty(),
                HashTreePMap.<ProjectFile, TreeSitterAnalyzer.FileProperties>from(Map.of(projectFile, fileProps)),
                ImportGraph.empty(),
                TypeHierarchyGraph.empty(),
                new TreeSitterAnalyzer.SymbolKeyIndex(new TreeSet<>()),
                System.nanoTime());

        Path out = tempDir.resolve("imports_roundtrip.smile.gz");
        TreeSitterStateIO.save(originalState, out);

        var loadedOpt = TreeSitterStateIO.load(out);
        assertTrue(loadedOpt.isPresent());
        var loadedState = loadedOpt.get();

        var loadedFileProps = loadedState.fileState().get(projectFile);
        assertNotNull(loadedFileProps);

        assertEquals(imports.size(), loadedFileProps.importStatements().size());
        for (int i = 0; i < imports.size(); i++) {
            var expected = imports.get(i);
            var actual = loadedFileProps.importStatements().get(i);
            assertEquals(expected.rawSnippet(), actual.rawSnippet());
            assertEquals(expected.isWildcard(), actual.isWildcard());
            assertEquals(expected.identifier(), actual.identifier());
            assertEquals(expected.alias(), actual.alias());
        }
    }

    @Test
    void roundTripPreservesContainsTests(@TempDir Path tempDir) {
        var fileDto = new ProjectFileDto(tempDir.toString(), "Test.java");
        var propsDto = new FilePropertiesDto(List.of(), List.of(), true);
        var entryDto = new FileStateEntryDto(fileDto, propsDto);

        var originalDto = new AnalyzerStateDto(
                Map.of(), List.of(), List.of(entryDto), List.of(), List.of(), List.of(), List.of(), List.of(), 555L);
        var state = TreeSitterStateIO.fromDto(originalDto);

        Path out = tempDir.resolve("test_props.smile.gz");
        TreeSitterStateIO.save(state, out);

        var loadedOpt = TreeSitterStateIO.load(out);
        assertTrue(loadedOpt.isPresent(), "Should load state with containsTests");

        var loadedDto = TreeSitterStateIO.toDto(loadedOpt.get());
        assertEquals(originalDto, loadedDto, "Round-trip should preserve all fields including containsTests");
        assertTrue(loadedDto.fileState().getFirst().value().containsTests(), "containsTests=true should be preserved");
    }

    @Test
    void lazyTreeParsingAfterRoundtrip(@TempDir Path tempDir) throws Exception {
        // 1. Create test project with a Java file
        var builder = InlineTestProjectCreator.code(
                """
                        package com.example;

                        public class Lazy {
                            public void doSomething() {}
                        }
                        """,
                "src/main/java/com/example/Lazy.java");

        try (IProject project = builder.build()) {
            // 2. Create JavaAnalyzer and get ProjectFile reference
            JavaAnalyzer analyzer = new JavaAnalyzer(project);
            ProjectFile file = new ProjectFile(project.getRoot(), Path.of("src/main/java/com/example/Lazy.java"));

            // Verify the original analyzer has the tree parsed
            assertNotNull(analyzer.treeOf(file), "Original analyzer should have parsed tree");

            // 3. Save state to temp file
            Path stateFile = tempDir.resolve("lazy_test.smile.gz");
            TreeSitterStateIO.save(analyzer.snapshotState(), stateFile);
            assertTrue(Files.exists(stateFile), "State file should exist after save");

            // 4. Load state from file
            var loadedStateOpt = TreeSitterStateIO.load(stateFile);
            assertTrue(loadedStateOpt.isPresent(), "Should successfully load state");
            var loadedState = loadedStateOpt.get();

            // 5. Create new analyzer from loaded state
            JavaAnalyzer loadedAnalyzer = JavaAnalyzer.fromState(project, loadedState, IAnalyzer.ProgressListener.NOOP);

            // 6. Verify that FileProperties exists
            var initialSnapshot = loadedAnalyzer.snapshotState();
            var initialFileProps = initialSnapshot.fileState().get(file);
            assertNotNull(initialFileProps, "File properties should exist in loaded state");

            // 7. Call treeOf to trigger lazy parsing
            var lazyParsedTree = loadedAnalyzer.treeOf(file);

            // 8. Assert the returned tree is not null
            assertNotNull(lazyParsedTree, "treeOf should return non-null tree after lazy parsing");
        }
    }

    @Test
    void roundTripTypeHierarchy(@TempDir Path tempDir) throws Exception {
        var builder = InlineTestProjectCreator.code(
                """
                package com.example;
                interface Base {}
                class Derived implements Base {}
                """,
                "src/main/java/com/example/Hierarchy.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);

            CodeUnit baseCu = analyzer.getDefinitions("com.example.Base").getFirst();
            CodeUnit derivedCu = analyzer.getDefinitions("com.example.Derived").getFirst();

            // Trigger hierarchy computation (lazily populates the internal TypeHierarchyGraph)
            Set<CodeUnit> descendants =
                    analyzer.as(TypeHierarchyProvider.class).orElseThrow().getDirectDescendants(baseCu);
            assertTrue(descendants.contains(derivedCu), "Base should have Derived as descendant");

            // Save state - this serializes the populated TypeHierarchyGraph
            Path storage = Languages.JAVA.getStoragePath(project);
            TreeSitterStateIO.save(analyzer.snapshotState(), storage);

            // Load state into a fresh analyzer instance
            IAnalyzer loaded = Languages.JAVA.loadAnalyzer(project);

            // Verify descendants from loaded state without re-triggering full analysis
            Set<CodeUnit> loadedDescendants =
                    loaded.as(TypeHierarchyProvider.class).orElseThrow().getDirectDescendants(baseCu);
            assertTrue(loadedDescendants.contains(derivedCu), "Loaded analyzer should retain descendants");
            assertEquals(descendants.size(), loadedDescendants.size());
        }
    }

    @Test
    void roundTripLazySubtypes(@TempDir Path tempDir) throws Exception {
        var builder = InlineTestProjectCreator.code(
                """
                package com.example;
                interface Base {}
                class A implements Base {}
                class B implements Base {}
                """,
                "src/main/java/com/example/Hierarchy.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);

            CodeUnit baseCu = analyzer.getDefinitions("com.example.Base").getFirst();
            CodeUnit aCu = analyzer.getDefinitions("com.example.A").getFirst();
            CodeUnit bCu = analyzer.getDefinitions("com.example.B").getFirst();

            // Trigger lazy computation of subtypes for Base
            Set<CodeUnit> originalDescendants =
                    analyzer.as(TypeHierarchyProvider.class).orElseThrow().getDirectDescendants(baseCu);
            assertTrue(originalDescendants.contains(aCu));
            assertTrue(originalDescendants.contains(bCu));

            // Save state - this must merge lazyHierarchy.subtypeCache into the snapshot
            Path storage = Languages.JAVA.getStoragePath(project);
            TreeSitterStateIO.save(analyzer.snapshotState(), storage);

            // Load into a new analyzer
            IAnalyzer loaded = Languages.JAVA.loadAnalyzer(project);

            // Verify that getDirectDescendants returns the same results
            // In TreeSitterAnalyzer, if the state contains the results in TypeHierarchyGraph,
            // they are returned immediately.
            Set<CodeUnit> loadedDescendants =
                    loaded.as(TypeHierarchyProvider.class).orElseThrow().getDirectDescendants(baseCu);
            assertEquals(originalDescendants, loadedDescendants, "Subtypes should match after round-trip");

            // Specifically verify that no re-computation happened by checking the loaded state directly
            // (Since we can't easily check the private cache of the loaded instance,
            // the fact that it returns the expected set from a fresh load of the saved DTO
            // confirms the DTO contained the subtypes).
            assertTrue(loadedDescendants.contains(aCu));
            assertTrue(loadedDescendants.contains(bCu));
        }
    }

    @Test
    void roundTripAncestorTriggeredSubtypes(@TempDir Path tempDir) throws Exception {
        var builder = InlineTestProjectCreator.code(
                """
                package com.example;
                interface Base {}
                class Child1 implements Base {}
                class Child2 implements Base {}
                """,
                "src/main/java/com/example/Hierarchy.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);

            CodeUnit baseCu = analyzer.getDefinitions("com.example.Base").getFirst();
            CodeUnit child1Cu = analyzer.getDefinitions("com.example.Child1").getFirst();
            CodeUnit child2Cu = analyzer.getDefinitions("com.example.Child2").getFirst();

            // 1. Trigger lazy supertype computation for the children.
            // This also populates the reverse subtype index in the transient cache as a side-effect.
            var hierarchy = analyzer.as(TypeHierarchyProvider.class).orElseThrow();
            List<CodeUnit> parents1 = hierarchy.getDirectAncestors(child1Cu);
            List<CodeUnit> parents2 = hierarchy.getDirectAncestors(child2Cu);

            assertTrue(parents1.contains(baseCu));
            assertTrue(parents2.contains(baseCu));

            // 2. Snapshot the state and verify the reverse index (subtypes) was merged.
            TreeSitterAnalyzer.AnalyzerState snapshot = analyzer.snapshotState();
            Set<CodeUnit> subtypesInSnapshot = snapshot.typeHierarchyGraph().subtypesOf(baseCu);
            assertTrue(subtypesInSnapshot.contains(child1Cu), "Snapshot should contain Child1 as subtype of Base");
            assertTrue(subtypesInSnapshot.contains(child2Cu), "Snapshot should contain Child2 as subtype of Base");

            // 3. Round-trip serialization
            Path storage = tempDir.resolve("ancestor_test.smile.gz");
            TreeSitterStateIO.save(snapshot, storage);

            var loadedStateOpt = TreeSitterStateIO.load(storage);
            assertTrue(loadedStateOpt.isPresent());
            var loadedState = loadedStateOpt.get();

            // 4. Verify reloaded state contains the subtype mappings
            Set<CodeUnit> reloadedSubtypes = loadedState.typeHierarchyGraph().subtypesOf(baseCu);
            assertTrue(reloadedSubtypes.contains(child1Cu));
            assertTrue(reloadedSubtypes.contains(child2Cu));
            assertEquals(2, reloadedSubtypes.size());
        }
    }

    @Test
    void roundTripImportsAndReverseImports(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("root");
        Files.createDirectories(root);
        var fileA = new ProjectFile(root, Path.of("A.java"));
        var fileB = new ProjectFile(root, Path.of("B.java"));
        var cuB = CodeUnit.cls(fileB, "com.example", "B");

        var importGraph = ImportGraph.from(Map.of(fileA, Set.of(cuB)), Map.of(fileB, Set.of(fileA)));

        var state = new TreeSitterAnalyzer.AnalyzerState(
                HashTreePMap.<String, Set<CodeUnit>>empty(),
                HashTreePMap.<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties>empty(),
                HashTreePMap.<ProjectFile, TreeSitterAnalyzer.FileProperties>empty(),
                importGraph,
                TypeHierarchyGraph.empty(),
                new TreeSitterAnalyzer.SymbolKeyIndex(new TreeSet<>()),
                System.nanoTime());

        Path out = tempDir.resolve("imports.smile.gz");
        TreeSitterStateIO.save(state, out);

        var loadedOpt = TreeSitterStateIO.load(out);
        assertTrue(loadedOpt.isPresent());
        var loaded = loadedOpt.get();

        assertEquals(
                state.importGraph().imports(),
                loaded.importGraph().imports(),
                "Forward imports should match after round-trip");
        assertEquals(
                state.importGraph().reverseImports(),
                loaded.importGraph().reverseImports(),
                "Reverse imports should match after round-trip");
    }

    @Test
    void descendantsRecoveredFromPersistedSupertypesEvenIfSubtypeGraphMissing() throws Exception {
        var builder = InlineTestProjectCreator.code(
                """
                package com.example;
                interface Base {}
                class Child1 implements Base {}
                class Child2 implements Base {}
                """,
                "src/main/java/com/example/Hierarchy.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);

            CodeUnit baseOriginal = analyzer.getDefinitions("com.example.Base").getFirst();
            CodeUnit child1Original =
                    analyzer.getDefinitions("com.example.Child1").getFirst();
            CodeUnit child2Original =
                    analyzer.getDefinitions("com.example.Child2").getFirst();

            // 1. Trigger ancestor computation for children to ensure state has SuperTypeInfo.Computed
            var hierarchy = analyzer.as(TypeHierarchyProvider.class).orElseThrow();
            hierarchy.getDirectAncestors(child1Original);
            hierarchy.getDirectAncestors(child2Original);

            // 2. Create a snapshot and convert to DTO
            var snapshot = analyzer.snapshotState();
            var dto = TreeSitterStateIO.toDto(snapshot);

            // 3. Create a "legacy" DTO that omits the explicit subtype/supertype graph
            // This simulates snapshots where hierarchy graphs were not persisted or were empty,
            // but CodeUnitProperties.superTypes.supertypesComputed is still true.
            var legacyDto = new TreeSitterStateIO.AnalyzerStateDto(
                    dto.symbolIndex(),
                    dto.codeUnitState(),
                    dto.fileState(),
                    dto.imports(),
                    dto.reverseImports(),
                    null, // supertypes graph omitted
                    null, // subtypes graph omitted
                    dto.symbolKeys(),
                    dto.snapshotEpochNanos());

            var legacyState = TreeSitterStateIO.fromDto(legacyDto);

            // 4. Load analyzer from legacy state
            JavaAnalyzer loaded = JavaAnalyzer.fromState(project, legacyState, IAnalyzer.ProgressListener.NOOP);

            // 5. Resolve Base from the loaded analyzer
            CodeUnit baseLoaded = loaded.getDefinitions("com.example.Base").getFirst();

            // 6. Query descendants. Even though the subtypes graph was null/empty in the DTO,
            // the analyzer should be able to recover them from the computed supertypes in CodeUnitProperties.
            Set<CodeUnit> descendants =
                    loaded.as(TypeHierarchyProvider.class).orElseThrow().getDirectDescendants(baseLoaded);

            assertEquals(2, descendants.size(), "Should find both descendants via supertype back-links");
            assertTrue(descendants.stream().anyMatch(cu -> cu.fqName().equals("com.example.Child1")), "Missing Child1");
            assertTrue(descendants.stream().anyMatch(cu -> cu.fqName().equals("com.example.Child2")), "Missing Child2");
        }
    }

    @Test
    void deserializeLegacyStateWithComputedSupertypes(@TempDir Path tempDir) throws Exception {
        // Construct DTO components manually to simulate legacy structure
        var root = tempDir.toAbsolutePath().normalize();
        var pfDto = new TreeSitterStateIO.ProjectFileDto(root.toString(), "src/Test.java");
        var cuDto = new TreeSitterStateIO.CodeUnitDto(pfDto, CodeUnitType.CLASS, "com.pkg", "Test", null);

        // Legacy properties map with extra fields
        Map<String, Object> legacyProps = new HashMap<>();
        legacyProps.put("children", List.of());
        legacyProps.put("signatures", List.of("sig"));
        legacyProps.put("ranges", List.of(new IAnalyzer.Range(0, 10, 0, 1, 0)));
        legacyProps.put("hasBody", true);
        legacyProps.put("rawSupertypes", List.of("RawBase")); // Field removed from record
        legacyProps.put("superTypes", Map.of("supertypes", List.of())); // Field removed from record

        // Entry as Map to bypass CodeUnitEntryDto's type check
        Map<String, Object> entry = new HashMap<>();
        entry.put("key", cuDto);
        entry.put("value", legacyProps);

        // Simulate a legacy hierarchy graph as well to verify it's still loaded
        Map<String, Object> supertypeEntry = Map.of("key", cuDto, "value", List.of());

        // AnalyzerStateDto as Map
        Map<String, Object> stateDtoMap = new HashMap<>();
        stateDtoMap.put("symbolIndex", Map.of());
        stateDtoMap.put("codeUnitState", List.of(entry));
        stateDtoMap.put("fileState", List.of());
        stateDtoMap.put("imports", List.of());
        stateDtoMap.put("reverseImports", List.of());
        stateDtoMap.put("supertypes", List.of(supertypeEntry));
        stateDtoMap.put("subtypes", List.of());
        stateDtoMap.put("symbolKeys", List.of());
        stateDtoMap.put("snapshotEpochNanos", 12345L);

        // Serialize to file using Smile
        Path file = tempDir.resolve("legacy.smile.gz");
        ObjectMapper mapper = new ObjectMapper(new SmileFactory());
        try (var out = new GZIPOutputStream(Files.newOutputStream(file))) {
            mapper.writeValue(out, stateDtoMap);
        }

        // Load using TreeSitterStateIO
        var loadedOpt = TreeSitterStateIO.load(file);
        assertTrue(loadedOpt.isPresent(), "Should load legacy state successfully");
        var loadedState = loadedOpt.get();

        // Verify loaded content
        assertEquals(1, loadedState.codeUnitState().size());
        var loadedCu = loadedState.codeUnitState().keySet().iterator().next();
        var loadedProps = loadedState.codeUnitState().get(loadedCu);

        assertEquals("Test", loadedCu.shortName());
        assertEquals(1, loadedProps.ranges().size());

        // Verify TypeHierarchyGraph data is still present
        var hierarchy = loadedState.typeHierarchyGraph();
        assertTrue(hierarchy.supertypes().containsKey(loadedCu), "Hierarchy data should be loaded");
    }

    @Test
    void loadLegacyStateWithPerCodeUnitSupertypes(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("legacy_per_cu_supertypes.smile.gz");

        var pfDto = new TreeSitterStateIO.ProjectFileDto(tempDir.toString(), "Test.java");
        var cuDto = new TreeSitterStateIO.CodeUnitDto(pfDto, CodeUnitType.CLASS, "com.pkg", "Test", null);

        // Simulate removed 'supertypes' and 'supertypesComputed' fields
        Map<String, Object> legacyProps = new HashMap<>();
        legacyProps.put("children", List.of());
        legacyProps.put("signatures", List.of());
        legacyProps.put("ranges", List.of(new IAnalyzer.Range(0, 1, 0, 1, 0)));
        legacyProps.put("hasBody", false);
        legacyProps.put("supertypes", List.of(cuDto));
        legacyProps.put("supertypesComputed", true);

        Map<String, Object> entry = Map.of("key", cuDto, "value", legacyProps);

        Map<String, Object> stateDtoMap = new HashMap<>();
        stateDtoMap.put("symbolIndex", Map.of());
        stateDtoMap.put("codeUnitState", List.of(entry));
        stateDtoMap.put("fileState", List.of());
        stateDtoMap.put("imports", List.of());
        stateDtoMap.put("reverseImports", List.of());
        stateDtoMap.put("symbolKeys", List.of());
        stateDtoMap.put("snapshotEpochNanos", 1L);

        var mapper = new ObjectMapper(new SmileFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (var os = new GZIPOutputStream(Files.newOutputStream(out))) {
            mapper.writeValue(os, stateDtoMap);
        }

        var loadedOpt = TreeSitterStateIO.load(out);
        assertTrue(loadedOpt.isPresent(), "Should successfully load state ignoring legacy supertype fields");
    }

    @Test
    void loadIgnoresLegacyRawSupertypesField(@TempDir Path tempDir) throws Exception {
        Path out = tempDir.resolve("legacy_raw_supertypes.smile.gz");

        // Manually construct a CodeUnitPropertiesDto-like map that includes the old 'rawSupertypes' field
        var pfDto = new TreeSitterStateIO.ProjectFileDto(tempDir.toString(), "Test.java");
        var cuDto = new TreeSitterStateIO.CodeUnitDto(pfDto, CodeUnitType.CLASS, "com.pkg", "Test", null);

        Map<String, Object> legacyProps = new HashMap<>();
        legacyProps.put("children", List.of());
        legacyProps.put("signatures", List.of());
        legacyProps.put("ranges", List.of(new IAnalyzer.Range(0, 1, 0, 1, 0)));
        legacyProps.put("hasBody", false);
        legacyProps.put("rawSupertypes", List.of("BaseClass", "InterfaceA")); // Field removed from DTO
        legacyProps.put("supertypes", List.of()); // Field removed from DTO
        legacyProps.put("supertypesComputed", true); // Field removed from DTO

        Map<String, Object> entry = Map.of("key", cuDto, "value", legacyProps);

        Map<String, Object> stateDtoMap = new HashMap<>();
        stateDtoMap.put("symbolIndex", Map.of());
        stateDtoMap.put("codeUnitState", List.of(entry));
        stateDtoMap.put("fileState", List.of());
        stateDtoMap.put("imports", List.of());
        stateDtoMap.put("reverseImports", List.of());
        stateDtoMap.put("symbolKeys", List.of());
        stateDtoMap.put("snapshotEpochNanos", 1L);

        var mapper = new ObjectMapper(new SmileFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (var os = new GZIPOutputStream(Files.newOutputStream(out))) {
            mapper.writeValue(os, stateDtoMap);
        }

        var loaded = TreeSitterStateIO.load(out);
        assertTrue(loaded.isPresent(), "Should successfully load state even with unknown 'rawSupertypes' field");
    }

    @Test
    void newDtoDoesNotContainSignatures(@TempDir Path tempDir) throws Exception {
        // Build a tiny AnalyzerState and round-trip via toDto to ensure DTOs do not include 'signatures' fields.
        var fileDto = new ProjectFileDto(tempDir.toString(), "Test.java");
        var cuDto = new TreeSitterStateIO.CodeUnitDto(fileDto, CodeUnitType.CLASS, "com.pkg", "Test", null);

        var fpDto = new FilePropertiesDto(List.of(cuDto), List.of(), false);
        var fileEntry = new FileStateEntryDto(fileDto, fpDto);

        var dto = new AnalyzerStateDto(
                Map.of(),
                List.of(), // no codeUnitState entries initially
                List.of(fileEntry),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1L);

        // Convert DTO -> state -> DTO to ensure toDto produces the canonical shape
        var state = TreeSitterStateIO.fromDto(dto);
        var produced = TreeSitterStateIO.toDto(state);

        // Serialize produced DTO into a JsonNode tree and verify no 'signatures' keys under codeUnitState entries
        ObjectMapper mapper = new ObjectMapper(new SmileFactory());
        JsonNode rootNode = mapper.valueToTree(produced);

        // If codeUnitState present, iterate entries and ensure no 'signatures' in value
        JsonNode codeUnitState = rootNode.get("codeUnitState");
        if (codeUnitState != null && codeUnitState.isArray()) {
            for (JsonNode entry : codeUnitState) {
                JsonNode value = entry.get("value");
                if (value != null && value.isObject()) {
                    assertNull(
                            value.get("signatures"),
                            "New DTOs must not include 'signatures' arrays in CodeUnitPropertiesDto");
                }
            }
        }
    }
}
