package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

public class MultiLanguageStateIOTest {

    /**
     * Minimal multi-language test project providing the basics required by TreeSitterAnalyzer and Language loaders.
     */
    static final class TestProject implements IProject {
        private final Path root;
        private final Set<ProjectFile> files;
        private final Set<Language> languages;

        TestProject(Path root, Set<ProjectFile> files, Set<Language> languages) {
            this.root = root;
            this.files = files;
            this.languages = languages;
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

        @Override
        public Set<Language> getAnalyzerLanguages() {
            return languages;
        }
    }

    @Test
    void roundTripMultiLanguageAnalyzerState() throws Exception {
        Path root = Files.createTempDirectory("brokk-multi-proj");
        try {
            // --- Prepare Java source file ---
            Path javaDir = root.resolve("src").resolve("main").resolve("java").resolve("com").resolve("example");
            Files.createDirectories(javaDir);
            Path javaFilePath = javaDir.resolve("Hello.java");
            String javaSrc = """
                    package com.example;

                    public class Hello {
                        public int add(int a, int b) { return a + b; }
                    }
                    """;
            Files.writeString(javaFilePath, javaSrc);

            // --- Prepare Python source file with a class (to ensure non-empty getAllDeclarations) ---
            Path pyDir = root.resolve("src").resolve("main").resolve("python").resolve("com").resolve("example");
            Files.createDirectories(pyDir);
            Path pyFilePath = pyDir.resolve("mod.py");
            String pySrc = """
                    class World:
                        def greet(self):
                            return "hi"
                    """;
            Files.writeString(pyFilePath, pySrc);

            // Build project file set
            ProjectFile javaPf = new ProjectFile(root, root.relativize(javaFilePath));
            ProjectFile pyPf = new ProjectFile(root, root.relativize(pyFilePath));
            Set<ProjectFile> filesSet = Set.of(javaPf, pyPf);

            // Configure languages: Java + Python
            Set<Language> langs = Set.of(Languages.JAVA, Languages.PYTHON);

            IProject project = new TestProject(root, filesSet, langs);

            // Build a MultiLanguage analyzer (load attempts persisted state; initial run falls back to full build)
            Language.MultiLanguage multiLang = new Language.MultiLanguage(langs);
            IAnalyzer analyzer = multiLang.loadAnalyzer(project);
            assertNotNull(analyzer, "Expected non-null analyzer");
            assertTrue(analyzer instanceof MultiAnalyzer, "Expected a MultiAnalyzer for multiple languages");

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

            // Verify there is no wrapper-level single .bin for MultiLanguage/MultiAnalyzer:
            // list *.bin files in .brokk and ensure only java.bin and python.bin are present
            Path brokkDir = javaBin.getParent();
            assertNotNull(brokkDir, "Expected .brokk directory parent to be non-null");
            List<Path> binFiles;
            try (var list = Files.list(brokkDir)) {
                binFiles = list.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".bin"))
                               .toList();
            }
            Set<String> binNames = new HashSet<>();
            for (Path p : binFiles) {
                binNames.add(p.getFileName().toString().toLowerCase(Locale.ROOT));
            }
            assertTrue(binNames.contains("java.bin"), "Missing java.bin in .brokk");
            assertTrue(binNames.contains("python.bin"), "Missing python.bin in .brokk");
            assertEquals(2, binNames.size(), "Unexpected .bin files present (wrapper should not create a single .bin)");

            // Reload analyzer from disk and validate delegates/equivalence
            IAnalyzer reloaded = multiLang.loadAnalyzer(project);
            assertNotNull(reloaded, "Reloaded analyzer should not be null");
            assertTrue(reloaded instanceof MultiAnalyzer, "Reloaded analyzer should be a MultiAnalyzer");

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
