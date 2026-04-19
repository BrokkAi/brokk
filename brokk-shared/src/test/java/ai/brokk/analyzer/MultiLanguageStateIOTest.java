package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.project.ICoreProject;
import ai.brokk.testutil.InlineCoreProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

public class MultiLanguageStateIOTest {

    @Test
    void roundTripMultiLanguageAnalyzerState() throws Exception {
        // Build a temp project with Java + Python sources; project cleans itself up when closed
        var builder = InlineCoreProject.code(
                """
                package com.example;
                public class Hello {
                    public int add(int a, int b) { return a + b; }
                }
                """,
                "src/main/java/com/example/Hello.java");

        builder.addFileContents(
                """
                class World:
                    def greet(self):
                        return "hi"
                """,
                "src/main/python/com/example/mod.py");

        // Configure languages: Java + Python
        Set<Language> langs = Set.of(Languages.JAVA, Languages.PYTHON);

        try (ICoreProject project = builder.build()) {
            // Build a MultiLanguage analyzer (load attempts persisted state; initial run falls back to full build)
            Language.MultiLanguage multiLang = new Language.MultiLanguage(langs);
            IAnalyzer analyzer = multiLang.loadAnalyzer(project);
            assertNotNull(analyzer, "Expected non-null analyzer");
            assertInstanceOf(MultiAnalyzer.class, analyzer, "Expected a MultiAnalyzer for multiple languages");

            MultiAnalyzer multi = (MultiAnalyzer) analyzer;
            Map<Language, IAnalyzer> delegates = multi.getDelegates();
            assertTrue(delegates.containsKey(Languages.JAVA), "Missing Java analyzer delegate");
            assertTrue(delegates.containsKey(Languages.PYTHON), "Missing Python analyzer delegate");

            // Extract pre-save FQN sets per language to compare after reload
            Map<Language, Set<String>> preSaveFqnsByLang = new HashMap<>();
            for (var e : delegates.entrySet()) {
                var lang = e.getKey();
                var del = e.getValue();
                var fqnSet = new LinkedHashSet<String>();
                for (var cu : del.getAllDeclarations()) {
                    fqnSet.add(cu.fqName());
                }
                preSaveFqnsByLang.put(lang, fqnSet);
                // Ensure each language has at least one declaration (class in both Java and Python test files)
                assertFalse(fqnSet.isEmpty(), "Expected non-empty declarations for " + lang.name());
            }

            // Persist each delegate using the language's own save hook (writes .brokk/{internalName}.bin)
            for (var e : delegates.entrySet()) {
                var lang = e.getKey();
                var del = e.getValue();
                lang.saveAnalyzer(del, project);
            }

            // Verify the per-language storage files exist
            Path javaBin = Languages.JAVA.getStoragePath(project);
            Path pyBin = Languages.PYTHON.getStoragePath(project);
            assertTrue(Files.exists(javaBin), "Expected Java analyzer state file to exist: " + javaBin);
            assertTrue(Files.exists(pyBin), "Expected Python analyzer state file to exist: " + pyBin);

            // Verify there is no wrapper-level single .bin.lz4 for MultiLanguage/MultiAnalyzer:
            // list *.bin.lz4 files in .brokk and ensure only java.bin.lz4 and python.bin.lz4 are present
            Path brokkDir = javaBin.getParent();
            assertNotNull(brokkDir, "Expected .brokk directory parent to be non-null");
            List<Path> binFiles;
            try (var list = Files.list(brokkDir)) {
                binFiles = list.filter(p -> p.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT)
                                .endsWith(".bin.lz4"))
                        .toList();
            }
            Set<String> binNames = new HashSet<>();
            for (Path p : binFiles) {
                binNames.add(p.getFileName().toString().toLowerCase(Locale.ROOT));
            }
            assertTrue(binNames.contains("java.bin.lz4"), "Missing java.bin.lz4 in .brokk");
            assertTrue(binNames.contains("python.bin.lz4"), "Missing python.bin.lz4 in .brokk");
            assertEquals(
                    2,
                    binNames.size(),
                    "Unexpected .bin.lz4 files present (wrapper should not create a single .bin.lz4)");

            // Reload analyzer from disk and validate delegates/equivalence
            IAnalyzer reloaded = multiLang.loadAnalyzer(project);
            assertNotNull(reloaded, "Reloaded analyzer should not be null");
            assertInstanceOf(MultiAnalyzer.class, reloaded, "Reloaded analyzer should be a MultiAnalyzer");

            MultiAnalyzer reMulti = (MultiAnalyzer) reloaded;
            Map<Language, IAnalyzer> reDelegates = reMulti.getDelegates();
            assertTrue(reDelegates.containsKey(Languages.JAVA), "Reloaded analyzer missing Java delegate");
            assertTrue(reDelegates.containsKey(Languages.PYTHON), "Reloaded analyzer missing Python delegate");

            // Compare per-language FQN sets to pre-save
            for (var lang : langs) {
                var pre = preSaveFqnsByLang.getOrDefault(lang, Set.of());
                var post = new LinkedHashSet<String>();
                for (var cu : reDelegates.get(lang).getAllDeclarations()) {
                    post.add(cu.fqName());
                }
                assertEquals(pre, post, "FQNs after reload should match original for " + lang.name());
            }
        }
    }
}
