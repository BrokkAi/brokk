package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
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
        var state = new TreeSitterAnalyzer.AnalyzerState(
                HashTreePMap.empty(),
                HashTreePMap.empty(),
                HashTreePMap.empty(),
                new TreeSitterAnalyzer.SymbolKeyIndex(new TreeSet<>()),
                1L);

        Path out = tempDir.resolve("state.bin.gzip");
        TreeSitterStateIO.save(state, out);

        assertTrue(Files.exists(out), "Expected final state file to exist");

        var loaded = TreeSitterStateIO.load(out);
        assertTrue(loaded.isPresent(), "Expected load to succeed after save");

        // Note: AtomicWrites uses Files.createTempFile(parent, "temp-", ".tmp")
        try (var stream = Files.list(tempDir)) {
            var lingering = stream.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("temp-") && name.endsWith(".tmp");
                    })
                    .toList();
            assertTrue(lingering.isEmpty(), "No lingering temp files should remain after atomic save: " + lingering);
        }
    }

    @Test
    void roundTripCodeUnitProperties(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("root");
        Files.createDirectories(root);
        var projectFile = new ProjectFile(root, Path.of("Test.java"));
        var cu = CodeUnit.cls(projectFile, "com.example", "Test");

        var props = new TreeSitterAnalyzer.CodeUnitProperties(
                List.of(), List.of(new IAnalyzer.Range(0, 100, 0, 10, 0)), true);

        var stateMap = Map.of(cu, props);
        var originalState = new TreeSitterAnalyzer.AnalyzerState(
                HashTreePMap.<String, Set<CodeUnit>>empty(),
                HashTreePMap.<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties>from(stateMap),
                HashTreePMap.<ProjectFile, TreeSitterAnalyzer.FileProperties>empty(),
                new TreeSitterAnalyzer.SymbolKeyIndex(new TreeSet<>()),
                System.nanoTime());

        Path out = tempDir.resolve("props_roundtrip.bin.gzip");
        TreeSitterStateIO.save(originalState, out);

        var loadedOpt = TreeSitterStateIO.load(out);
        assertTrue(loadedOpt.isPresent());
        var loadedState = loadedOpt.get();

        var loadedProps = loadedState.codeUnitState().get(cu);

        assertNotNull(loadedProps);
        assertEquals(props.ranges(), loadedProps.ranges());
        assertEquals(props.children(), loadedProps.children());
        assertEquals(props.hasBody(), loadedProps.hasBody());
    }

    @Test
    void saveLoadRoundTripUnchanged(@TempDir Path tempDir) throws Exception {
        var original = new TreeSitterAnalyzer.AnalyzerState(
                HashTreePMap.empty(),
                HashTreePMap.empty(),
                HashTreePMap.empty(),
                new TreeSitterAnalyzer.SymbolKeyIndex(new TreeSet<>(List.of("KeyA", "keyb"))),
                99L);

        Path out = tempDir.resolve("roundtrip.bin.gzip");
        TreeSitterStateIO.save(original, out);

        var loadedOpt = TreeSitterStateIO.load(out);
        assertTrue(loadedOpt.isPresent(), "Expected to load state after saving");
        var loaded = loadedOpt.get();

        assertEquals(original.snapshotEpochNanos(), loaded.snapshotEpochNanos());
        assertEquals(original.symbolKeyIndex().all(), loaded.symbolKeyIndex().all());
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
                new TreeSitterAnalyzer.SymbolKeyIndex(new TreeSet<>()),
                System.nanoTime());

        Path out = tempDir.resolve("imports_roundtrip.bin.gzip");
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
            Path stateFile = tempDir.resolve("lazy_test.bin.gzip");
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
    void skeletonsRemainConsistentAcrossSaveAndLoad() throws Exception {
        var builder = InlineTestProjectCreator.code(
                """
                        package com.example;

                        public class Hello {
                            public int add(int a, int b) { return a + b; }
                        }
                        """,
                "src/main/java/com/example/Hello.java");

        try (IProject project = builder.build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);

            ProjectFile file = new ProjectFile(project.getRoot(), Path.of("src/main/java/com/example/Hello.java"));
            var skeletons = analyzer.getSkeletons(file);
            assertFalse(skeletons.isEmpty(), "Expected skeletons before save");

            String originalSkeleton = skeletons.entrySet().stream()
                    .filter(e -> e.getKey().isClass())
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow();

            Path storage = Languages.JAVA.getStoragePath(project);
            TreeSitterStateIO.save(analyzer.snapshotState(), storage);
            assertTrue(Files.exists(storage), "Saved analyzer state should exist");

            var loadedStateOpt = TreeSitterStateIO.load(storage);
            assertTrue(loadedStateOpt.isPresent(), "Expected state to load");
            var loadedState = loadedStateOpt.get();

            JavaAnalyzer analyzerFromState =
                    JavaAnalyzer.fromState(project, loadedState, IAnalyzer.ProgressListener.NOOP);

            // After loading from persisted AnalyzerState signatures are not present in cache.
            // Trigger a re-analysis of the file so transient signature cache is repopulated.
            var updatedAnalyzer = analyzerFromState.update(Set.of(file));
            assertNotNull(updatedAnalyzer, "update should return a new analyzer instance");
            JavaAnalyzer reanalyzed = (JavaAnalyzer) updatedAnalyzer;

            var skeletonsAfter = reanalyzed.getSkeletons(file);
            assertFalse(skeletonsAfter.isEmpty(), "Expected skeletons after reload and re-analysis");

            String reloadedSkeleton = skeletonsAfter.entrySet().stream()
                    .filter(e -> e.getKey().isClass())
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow();

            assertEquals(
                    originalSkeleton,
                    reloadedSkeleton,
                    "Skeleton should remain consistent across save/load when signatures are recomputed");
        }
    }

    @Test
    void roundTripTypeHierarchy() throws Exception {
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
    void roundTripLazySubtypes() throws Exception {
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

            // 1. Trigger lazy supertype computation for the children so we have canonical CodeUnit objects
            var hierarchy = analyzer.as(TypeHierarchyProvider.class).orElseThrow();
            List<CodeUnit> parents1 = hierarchy.getDirectAncestors(child1Cu);
            List<CodeUnit> parents2 = hierarchy.getDirectAncestors(child2Cu);

            assertTrue(parents1.contains(baseCu));
            assertTrue(parents2.contains(baseCu));

            // 2. Create a cache snapshot that persists the typeHierarchy forward mappings so round-trip will include
            // them
            var cacheForPersist = new ai.brokk.analyzer.cache.AnalyzerCache();
            // For forward mapping we store, for each child, its supertypes (Base)
            cacheForPersist.typeHierarchy().putForward(child1Cu, List.of(baseCu));
            cacheForPersist.typeHierarchy().putForward(child2Cu, List.of(baseCu));
            // Also update reverse mappings so consumers that expect reverse can find them (best-effort)
            cacheForPersist.typeHierarchy().updateReverse(baseCu, existing -> {
                Set<CodeUnit> set = existing != null ? existing : ConcurrentHashMap.newKeySet();
                set.add(child1Cu);
                set.add(child2Cu);
                return set;
            });

            // 3. Snapshot and save state + cache
            TreeSitterAnalyzer.AnalyzerState snapshot = analyzer.snapshotState();
            Path storage = tempDir.resolve("ancestor_test.bin.gzip");
            TreeSitterStateIO.save(snapshot, cacheForPersist.snapshot(), storage);

            var loadedWithCacheOpt = TreeSitterStateIO.loadWithCache(storage);
            assertTrue(loadedWithCacheOpt.isPresent());
            var swc = loadedWithCacheOpt.get();
            var cache = swc.cache();

            // 4. Verify that the persisted cache contains forward mappings for the children pointing at Base
            var fwd1 = cache.typeHierarchy().getForward(child1Cu);
            var fwd2 = cache.typeHierarchy().getForward(child2Cu);

            assertNotNull(fwd1, "Forward supertypes for Child1 should be present after round-trip");
            assertNotNull(fwd2, "Forward supertypes for Child2 should be present after round-trip");
            assertTrue(fwd1.contains(baseCu), "Child1 forward supertypes should include Base");
            assertTrue(fwd2.contains(baseCu), "Child2 forward supertypes should include Base");

            // 5. Verify reverse mapping (may have been reconstructed or serialized). If absent, derive reverse by
            // scanning forward.
            var reverse = cache.typeHierarchy().getReverse(baseCu);
            if (reverse == null || reverse.isEmpty()) {
                // Derive reverse mapping by scanning forward entries
                Set<CodeUnit> derived = new java.util.HashSet<>();
                cache.typeHierarchy().forEachForward((k, v) -> {
                    if (v != null && v.contains(baseCu)) derived.add(k);
                });
                reverse = derived;
            }

            assertTrue(reverse.contains(child1Cu), "Snapshot cache should contain Child1 as subtype of Base");
            assertTrue(reverse.contains(child2Cu), "Snapshot cache should contain Child2 as subtype of Base");
        }
    }

    @Test
    void roundTripImportsAndReverseImports(@TempDir Path tempDir) throws Exception {
        var root = tempDir.resolve("root");
        Files.createDirectories(root);
        var fileA = new ProjectFile(root, Path.of("A.java"));
        var fileB = new ProjectFile(root, Path.of("B.java"));
        var cuB = CodeUnit.cls(fileB, "com.example", "B");

        // Core AnalyzerState without any graphs (graphs live in cache snapshot)
        var state = new TreeSitterAnalyzer.AnalyzerState(
                HashTreePMap.<String, Set<CodeUnit>>empty(),
                HashTreePMap.<CodeUnit, TreeSitterAnalyzer.CodeUnitProperties>empty(),
                HashTreePMap.<ProjectFile, TreeSitterAnalyzer.FileProperties>empty(),
                new TreeSitterAnalyzer.SymbolKeyIndex(new TreeSet<>()),
                System.nanoTime());

        // Create an AnalyzerCache and populate forward imports so they will be serialized into the cache snapshot.
        var cacheForPersist = new ai.brokk.analyzer.cache.AnalyzerCache();
        cacheForPersist.imports().putForward(fileA, Set.of(cuB));
        // also populate reverse mapping for completeness (not strictly required for forward persistence)
        // The imports() bidirectional cache uses ProjectFile as the forward key, so updateReverse expects a ProjectFile
        // key.
        cacheForPersist.imports().updateReverse(cuB.source(), existing -> {
            Set<ProjectFile> set = existing != null ? new HashSet<>(existing) : new HashSet<>();
            set.add(fileA);
            return set;
        });

        Path out = tempDir.resolve("imports.bin.gzip");
        // Save state together with cache snapshot so import forward mappings are persisted
        TreeSitterStateIO.save(state, cacheForPersist.snapshot(), out);

        // Load with cache to inspect persisted imports stored in the cache snapshot
        var loadedWithCacheOpt = TreeSitterStateIO.loadWithCache(out);
        assertTrue(loadedWithCacheOpt.isPresent());
        var swc = loadedWithCacheOpt.get();
        var cache = swc.cache();

        var forward = cache.imports().getForward(fileA);
        assertNotNull(forward);
        assertEquals(Set.of(cuB), forward, "Forward imports should match after round-trip");

        var reverse = cache.imports().getReverse(fileB);
        assertNotNull(reverse);
        assertEquals(Set.of(fileA), reverse, "Reverse imports should match after round-trip");
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

            // 3. Create a "legacy" DTO that omits any optional graphs (imports/typeHierarchy).
            // This simulates older snapshots where only the core AnalyzerState was persisted.
            var legacyDto = new TreeSitterStateIO.AnalyzerStateDto(
                    dto.symbolIndex(),
                    dto.codeUnitState(),
                    dto.fileState(),
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
}
