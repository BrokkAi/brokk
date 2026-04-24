package ai.brokk.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.IAnalyzer.Range;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitDistance;
import ai.brokk.mcpserver.StandaloneCodeIntelligence;
import ai.brokk.project.CoreProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchToolsTest {

    @TempDir
    Path tempDir;

    private CoreProject project;

    @AfterEach
    void tearDown() {
        if (project != null) {
            project.close();
        }
    }

    @Test
    void findFilenames_DoesNotAppendRelatedContent() throws Exception {
        Path projectRoot = initRepo();
        commitTrackedFiles(
                projectRoot,
                Map.of("A.java", "class A {}", "B.java", "class B {}"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add A and B together");

        project = new CoreProject(projectRoot);
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project)));

        String result = tools.findFilenames(List.of("A\\.java"), 10);

        assertTrue(result.contains("A.java"), "Should still include the matching filename");
        assertFalse(result.contains("## Related Content"), "Should not include related content for non-search tools");
    }

    @Test
    void searchSymbols_AppendsRelatedContent() throws Exception {
        Path projectRoot = initRepo();
        commitTrackedFiles(
                projectRoot,
                Map.of(
                        "A.java",
                        """
                        class A {}
                        """.stripIndent(),
                        "B.java",
                        """
                        class B {}
                        """.stripIndent()),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add A and B together");

        project = new CoreProject(projectRoot);
        ProjectFile aFile = new ProjectFile(projectRoot, "A.java");
        ProjectFile bFile = new ProjectFile(projectRoot, "B.java");
        CodeUnit aClass = CodeUnit.cls(aFile, "", "A");
        CodeUnit bClass = CodeUnit.cls(bFile, "", "B");
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public Set<CodeUnit> searchDefinitions(String pattern) {
                return "A".equals(pattern) ? Set.of(aClass) : Set.of();
            }

            @Override
            public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                SequencedSet<CodeUnit> results = new LinkedHashSet<>();
                if ("A".equals(fqName)) {
                    results.add(aClass);
                } else if ("B".equals(fqName)) {
                    results.add(bClass);
                }
                return results;
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.searchSymbols(List.of("A"), false, 200);
        String relatedSection = relatedContentSection(result);

        assertTrue(result.contains("## Related Content"), "Should include related content header");
        assertTrue(relatedSection.contains("B.java"), "Should include a related file");
        assertFalse(relatedSection.contains("A.java"), "Should not echo the seed file");
    }

    @Test
    void searchSymbols_RendersDisplaySignatures() throws Exception {
        Path projectRoot = initRepo();
        Path filePath = projectRoot.resolve("src/main/java/com/example/A.java");
        Files.createDirectories(filePath.getParent());
        Files.writeString(
                filePath,
                """
                class A extends Base {
                    public void bar(int x, int y) {}
                }
                """
                        .stripIndent());

        project = new CoreProject(projectRoot);
        ProjectFile aFile = new ProjectFile(projectRoot, "src/main/java/com/example/A.java");
        CodeUnit aClass = CodeUnit.cls(aFile, "com.example", "A");
        CodeUnit aMethod = CodeUnit.fn(aFile, "com.example", "A.bar");
        Map<CodeUnit, List<Range>> ranges = Map.of(
                aClass, List.of(new Range(0, 0, 0, 30, 0)),
                aMethod, List.of(new Range(0, 0, 1, 39, 0)));
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public Set<CodeUnit> searchDefinitions(String pattern) {
                return ".*A.*".equals(pattern) ? Set.of(aClass, aMethod) : Set.of();
            }

            @Override
            public List<String> getDisplaySignatures(CodeUnit codeUnit) {
                if (codeUnit.equals(aClass)) {
                    return List.of("class A extends Base");
                }
                if (codeUnit.equals(aMethod)) {
                    return List.of("public void bar(int x, int y)");
                }
                return super.getDisplaySignatures(codeUnit);
            }

            @Override
            public List<Range> rangesOf(CodeUnit codeUnit) {
                return ranges.getOrDefault(codeUnit, List.of());
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.searchSymbols(List.of(".*A.*"), false, 200);

        assertTrue(result.contains("- 1: class A extends Base"), "Should render class signature. Result: " + result);
        assertTrue(
                result.contains("- 2: public void bar(int x, int y)"),
                "Should render method signature. Result: " + result);
        assertFalse(result.contains("com.example.A.bar"), "Should not render raw method FQN. Result: " + result);
    }

    @Test
    void searchSymbols_PreservesOverloadsWhenDisplaySignaturesCollide() throws Exception {
        Path projectRoot = initRepo();
        Path filePath = projectRoot.resolve("src/main/java/com/example/A.java");
        Files.createDirectories(filePath.getParent());
        Files.writeString(
                filePath,
                """
                class A {
                    public void bar(int value) {}
                    public void bar(String value) {}
                }
                """
                        .stripIndent());

        project = new CoreProject(projectRoot);
        ProjectFile aFile = new ProjectFile(projectRoot, "src/main/java/com/example/A.java");
        CodeUnit intOverload =
                new CodeUnit(aFile, ai.brokk.analyzer.CodeUnitType.FUNCTION, "com.example", "A.bar", "(int)");
        CodeUnit stringOverload =
                new CodeUnit(aFile, ai.brokk.analyzer.CodeUnitType.FUNCTION, "com.example", "A.bar", "(String)");
        Map<CodeUnit, List<Range>> ranges = Map.of(
                intOverload, List.of(new Range(0, 0, 1, 33, 0)),
                stringOverload, List.of(new Range(0, 0, 2, 36, 0)));
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public Set<CodeUnit> searchDefinitions(String pattern) {
                return "A".equals(pattern) ? Set.of(intOverload, stringOverload) : Set.of();
            }

            @Override
            public List<String> getDisplaySignatures(CodeUnit codeUnit) {
                if (codeUnit.equals(intOverload) || codeUnit.equals(stringOverload)) {
                    return List.of("public void bar(T value)");
                }
                return super.getDisplaySignatures(codeUnit);
            }

            @Override
            public List<Range> rangesOf(CodeUnit codeUnit) {
                return ranges.getOrDefault(codeUnit, List.of());
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.searchSymbols(List.of("A"), false, 200);

        assertTrue(
                result.contains("- 2: public void bar(T value)"),
                "Should keep the first overload when display signatures collide. Result: " + result);
        assertTrue(
                result.contains("- 3: public void bar(T value)"),
                "Should keep the second overload when display signatures collide. Result: " + result);
    }

    @Test
    void searchSymbols_TracksResearchTokensIncludingRelatedContent() throws Exception {
        Path projectRoot = initRepo();
        commitTrackedFiles(
                projectRoot,
                Map.of("A.java", "class A {}", "B.java", "class B {}"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add A and B together");

        project = new CoreProject(projectRoot);
        ProjectFile aFile = new ProjectFile(projectRoot, "A.java");
        CodeUnit aClass = CodeUnit.cls(aFile, "", "A");
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public Set<CodeUnit> searchDefinitions(String pattern) {
                return "A".equals(pattern) ? Set.of(aClass) : Set.of();
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        assertEquals(0L, tools.getAndClearResearchTokens(), "Counter should start empty");

        String result = tools.searchSymbols(List.of("A"), false, 10);
        long countedTokens = tools.getAndClearResearchTokens();

        assertTrue(result.contains("## Related Content"), "Should include related content header");
        assertTrue(countedTokens > 0, "Final output should be counted as research tokens");
        assertEquals(0L, tools.getAndClearResearchTokens(), "Counter should reset after reading");
    }

    @Test
    void searchSymbols_UsesAlphabeticalTruncationWhenGitPriorityDiffers() throws Exception {
        Path projectRoot = initRepo();
        commitTrackedFiles(
                projectRoot,
                Map.of("a-low.java", "class A {}\n"),
                Instant.parse("2020-01-01T00:00:00Z"),
                "Add A");
        commitTrackedFiles(
                projectRoot,
                Map.of("z-high.java", "class Z {}\n"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add Z");
        commitTrackedFiles(
                projectRoot,
                Map.of("z-high.java", "class Z { int value; }\n"),
                Instant.parse("2025-02-01T00:00:00Z"),
                "Update Z");

        project = new CoreProject(projectRoot);
        ProjectFile aLow = new ProjectFile(projectRoot, "a-low.java");
        ProjectFile zHigh = new ProjectFile(projectRoot, "z-high.java");
        assertEquals(
                zHigh,
                GitDistance.sortByImportance(List.of(aLow, zHigh), project.getRepo()).getFirst(),
                "Test setup should give z-high.java a higher Git rank");

        CodeUnit aClass = CodeUnit.cls(aLow, "", "A");
        CodeUnit zClass = CodeUnit.cls(zHigh, "", "Z");
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public Set<CodeUnit> searchDefinitions(String pattern) {
                return ".*".equals(pattern) ? Set.of(aClass, zClass) : Set.of();
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.searchSymbols(List.of(".*"), false, 1);
        String mainSection = mainResultSection(result);

        assertTrue(
                mainSection.contains("<file path=\"a-low.java\""),
                "Alphabetically first file should be retained when limit is hit");
        assertFalse(
                mainSection.contains("<file path=\"z-high.java\""),
                "Later files should be truncated even if Git ranks them higher");
    }

    @Test
    void findFilesContaining_UsesAlphabeticalTruncationWhenGitPriorityDiffers() throws Exception {
        Path projectRoot = initRepo();
        commitTrackedFiles(
                projectRoot,
                Map.of("a-low.txt", "MATCH low\n"),
                Instant.parse("2020-01-01T00:00:00Z"),
                "Add low");
        commitTrackedFiles(
                projectRoot,
                Map.of("z-high.txt", "MATCH high\n"),
                Instant.parse("2025-01-01T00:00:00Z"),
                "Add high");
        commitTrackedFiles(
                projectRoot,
                Map.of("z-high.txt", "MATCH high again\n"),
                Instant.parse("2025-02-01T00:00:00Z"),
                "Update high");

        project = new CoreProject(projectRoot);
        ProjectFile aLow = new ProjectFile(projectRoot, "a-low.txt");
        ProjectFile zHigh = new ProjectFile(projectRoot, "z-high.txt");
        assertEquals(
                zHigh,
                GitDistance.sortByImportance(List.of(aLow, zHigh), project.getRepo()).getFirst(),
                "Test setup should give z-high.txt a higher Git rank");

        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project)));

        String result = tools.findFilesContaining(List.of("MATCH"), 1);

        assertTrue(result.contains("a-low.txt"), "Alphabetically first match should be retained when limit is hit");
        assertFalse(result.contains("z-high.txt"), "Later matches should be truncated even if Git ranks them higher");
    }

    @Test
    void getClassSources_resolvesUniqueNonFqName() throws Exception {
        Path projectRoot = initRepo();
        Path filePath = projectRoot.resolve("src/main/java/com/example/Foo.java");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, "package com.example;\nclass Foo {}\n");

        project = new CoreProject(projectRoot);
        ProjectFile fooFile = new ProjectFile(projectRoot, "src/main/java/com/example/Foo.java");
        CodeUnit fooClass = CodeUnit.cls(fooFile, "com.example", "Foo");
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public List<CodeUnit> getAllDeclarations() {
                return List.of(fooClass);
            }

            @Override
            public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                SequencedSet<CodeUnit> results = new LinkedHashSet<>();
                if ("com.example.Foo".equals(fqName)) {
                    results.add(fooClass);
                }
                return results;
            }

            @Override
            public Optional<String> getSource(CodeUnit codeUnit, boolean includeComments) {
                return codeUnit.equals(fooClass) ? Optional.of("class Foo {}") : Optional.empty();
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.getClassSources(List.of("Foo"));

        assertTrue(result.contains("class Foo {}"), "Should resolve a unique simple class name. Result: " + result);
    }

    @Test
    void getClassSources_listsAmbiguousNonFqMatches() throws Exception {
        Path projectRoot = initRepo();
        project = new CoreProject(projectRoot);
        ProjectFile firstFile = new ProjectFile(projectRoot, "src/main/java/com/example/Foo.java");
        ProjectFile secondFile = new ProjectFile(projectRoot, "src/main/java/org/example/Foo.java");
        CodeUnit firstFoo = CodeUnit.cls(firstFile, "com.example", "Foo");
        CodeUnit secondFoo = CodeUnit.cls(secondFile, "org.example", "Foo");
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public List<CodeUnit> getAllDeclarations() {
                return List.of(firstFoo, secondFoo);
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.getClassSources(List.of("Foo"));

        assertTrue(result.contains("Ambiguous class match for 'Foo'"), result);
        assertTrue(result.contains("com.example.Foo"), result);
        assertTrue(result.contains("org.example.Foo"), result);
    }

    @Test
    void getMethodSources_resolvesUniqueNonFqName() throws Exception {
        Path projectRoot = initRepo();
        Path filePath = projectRoot.resolve("src/main/java/com/example/Foo.java");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, "package com.example;\nclass Foo { int inc(int x) { return x + 1; } }\n");

        project = new CoreProject(projectRoot);
        ProjectFile fooFile = new ProjectFile(projectRoot, "src/main/java/com/example/Foo.java");
        CodeUnit fooClass = CodeUnit.cls(fooFile, "com.example", "Foo");
        CodeUnit incMethod = CodeUnit.fn(fooFile, "com.example", "Foo.inc");
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public List<CodeUnit> getAllDeclarations() {
                return List.of(fooClass);
            }

            @Override
            public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
                return classUnit.equals(fooClass) ? List.of(incMethod) : List.of();
            }

            @Override
            public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                SequencedSet<CodeUnit> results = new LinkedHashSet<>();
                if ("com.example.Foo".equals(fqName)) {
                    results.add(fooClass);
                } else if ("com.example.Foo.inc".equals(fqName)) {
                    results.add(incMethod);
                }
                return results;
            }

            @Override
            public Set<String> getSources(CodeUnit codeUnit, boolean includeComments) {
                return codeUnit.equals(incMethod) ? Set.of("int inc(int x) { return x + 1; }") : Set.of();
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.getMethodSources(List.of("inc"));

        assertTrue(
                result.contains("int inc(int x) { return x + 1; }"),
                "Should resolve a unique simple method name. Result: " + result);
    }

    @Test
    void getMethodSources_listsAmbiguousNonFqMatches() throws Exception {
        Path projectRoot = initRepo();
        project = new CoreProject(projectRoot);
        ProjectFile firstFile = new ProjectFile(projectRoot, "src/main/java/com/example/Foo.java");
        ProjectFile secondFile = new ProjectFile(projectRoot, "src/main/java/org/example/Bar.java");
        CodeUnit fooClass = CodeUnit.cls(firstFile, "com.example", "Foo");
        CodeUnit barClass = CodeUnit.cls(secondFile, "org.example", "Bar");
        CodeUnit firstMethod = CodeUnit.fn(firstFile, "com.example", "Foo.inc");
        CodeUnit secondMethod = CodeUnit.fn(secondFile, "org.example", "Bar.inc");
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public List<CodeUnit> getAllDeclarations() {
                return List.of(fooClass, barClass);
            }

            @Override
            public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
                if (classUnit.equals(fooClass)) {
                    return List.of(firstMethod);
                }
                if (classUnit.equals(barClass)) {
                    return List.of(secondMethod);
                }
                return List.of();
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.getMethodSources(List.of("inc"));

        assertTrue(result.contains("Ambiguous method match for 'inc'"), result);
        assertTrue(result.contains("com.example.Foo.inc"), result);
        assertTrue(result.contains("org.example.Bar.inc"), result);
    }

    @Test
    void getMethodSources_scansFallbackDeclarationsOncePerCall() throws Exception {
        Path projectRoot = initRepo();
        project = new CoreProject(projectRoot);
        ProjectFile firstFile = new ProjectFile(projectRoot, "src/main/java/com/example/Foo.java");
        ProjectFile secondFile = new ProjectFile(projectRoot, "src/main/java/org/example/Bar.java");
        CodeUnit fooClass = CodeUnit.cls(firstFile, "com.example", "Foo");
        CodeUnit barClass = CodeUnit.cls(secondFile, "org.example", "Bar");
        CodeUnit firstMethod = CodeUnit.fn(firstFile, "com.example", "Foo.inc");
        CodeUnit secondMethod = CodeUnit.fn(secondFile, "org.example", "Bar.dec");
        AtomicInteger allDeclarationsCalls = new AtomicInteger();
        AtomicInteger memberLookupCalls = new AtomicInteger();
        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public List<CodeUnit> getAllDeclarations() {
                allDeclarationsCalls.incrementAndGet();
                return List.of(fooClass, barClass);
            }

            @Override
            public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
                memberLookupCalls.incrementAndGet();
                if (classUnit.equals(fooClass)) {
                    return List.of(firstMethod);
                }
                if (classUnit.equals(barClass)) {
                    return List.of(secondMethod);
                }
                return List.of();
            }

            @Override
            public Set<String> getSources(CodeUnit codeUnit, boolean includeComments) {
                if (codeUnit.equals(firstMethod)) {
                    return Set.of("int inc(int x) { return x + 1; }");
                }
                if (codeUnit.equals(secondMethod)) {
                    return Set.of("int dec(int x) { return x - 1; }");
                }
                return Set.of();
            }
        };
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.getMethodSources(List.of("inc", "dec"));

        assertTrue(result.contains("int inc(int x) { return x + 1; }"), result);
        assertTrue(result.contains("int dec(int x) { return x - 1; }"), result);
        assertEquals(1, allDeclarationsCalls.get(), "Fallback declaration scan should happen once per call");
        assertEquals(2, memberLookupCalls.get(), "Member lookup should happen once per top-level class");
    }

    @Test
    void searchFileContents_SearchTypeClassifiesAnalyzedFiles() throws Exception {
        Path projectRoot = initRepo();
        Path filePath = projectRoot.resolve("src/main/java/com/example/Foo.java");
        Files.createDirectories(filePath.getParent());
        Files.writeString(
                filePath,
                """
                package com.example;
                import java.util.List;
                class Foo {
                    String before = "before";
                    List<String> values;
                    Foo useFoo(Foo other) {
                        Foo local = other;
                        return local;
                    }
                    String after = "after";
                }
                """
                        .stripIndent());
        try (Git git = Git.open(projectRoot.toFile())) {
            git.add().addFilepattern("src/main/java/com/example/Foo.java").call();
        }

        project = new CoreProject(projectRoot);
        ProjectFile projectFile = new ProjectFile(projectRoot, "src/main/java/com/example/Foo.java");
        CodeUnit cls = CodeUnit.cls(projectFile, "com.example", "Foo");
        CodeUnit field = CodeUnit.field(projectFile, "com.example", "Foo.values");
        CodeUnit method = CodeUnit.fn(projectFile, "com.example", "Foo.useFoo");

        Map<CodeUnit, List<Range>> ranges = new HashMap<>();
        ranges.put(cls, List.of(new Range(0, 0, 2, 10, 0)));
        ranges.put(field, List.of(new Range(0, 0, 4, 4, 0)));
        ranges.put(method, List.of(new Range(0, 0, 5, 8, 0)));

        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public Set<ProjectFile> getAnalyzedFiles() {
                return Set.of(projectFile);
            }

            @Override
            public Set<CodeUnit> getDeclarations(ProjectFile file) {
                return file.equals(projectFile) ? Set.of(cls, field, method) : Set.of();
            }

            @Override
            public List<Range> rangesOf(CodeUnit codeUnit) {
                return ranges.getOrDefault(codeUnit, List.of());
            }

            @Override
            public List<String> importStatementsOf(ProjectFile file) {
                return file.equals(projectFile) ? List.of("import java.util.List;") : List.of();
            }

            @Override
            public SequencedSet<CodeUnit> getDefinitions(String fqName) {
                return new LinkedHashSet<>();
            }

            @Override
            public java.util.Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, int startLine, int endLine) {
                if (!file.equals(projectFile)) {
                    return java.util.Optional.empty();
                }
                return ranges.entrySet().stream()
                        .filter(entry -> entry.getValue().stream()
                                .anyMatch(range -> startLine >= range.startLine() && endLine <= range.endLine()))
                        .min(Comparator.comparingInt(entry -> entry.getValue().stream()
                                .filter(range -> startLine >= range.startLine() && endLine <= range.endLine())
                                .mapToInt(range -> range.endLine() - range.startLine())
                                .min()
                                .orElse(Integer.MAX_VALUE)))
                        .map(Map.Entry::getKey);
            }
        };

        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String declarations =
                tools.searchFileContents(List.of("Foo", "List"), "**/*.java", "declarations", false, false, 0, 20);
        assertTrue(declarations.contains("[DECLARATIONS]"));
        assertTrue(declarations.contains("3: class Foo {"));
        assertTrue(declarations.contains("5:     List<String> values;"));
        assertTrue(declarations.contains("6:     Foo useFoo(Foo other) {"));
        assertFalse(declarations.contains("2: import java.util.List;"));
        assertFalse(declarations.contains("7:         Foo local = other;"));

        String usages = tools.searchFileContents(List.of("Foo"), "**/*.java", "usages", false, false, 2, 20);
        assertTrue(usages.contains("[USAGES]"));
        assertTrue(usages.contains("Foo::useFoo [6..9]"));
        assertTrue(usages.contains("6:     Foo useFoo(Foo other) {"));
        assertTrue(usages.contains("7:         Foo local = other;"));
        assertTrue(usages.contains("8:         return local;"));
        assertTrue(usages.contains("9:     }"));
        assertFalse(usages.contains("3: class Foo {"));
        assertFalse(usages.contains("5:     List<String> values;"));
        assertFalse(usages.contains("10:     String after = \"after\";"));

        String all = tools.searchFileContents(List.of("Foo", "List"), "**/*.java", "all", false, false, 0, 20);
        assertTrue(all.contains("<matches>"));
        assertTrue(all.contains("<related>"));
        assertTrue(all.contains("Foo::useFoo [6..9]"));
        assertTrue(all.contains("2: import java.util.List;"));
    }

    @Test
    void searchFileContents_UnanalyzedFilesIgnoreSearchTypeFilter() throws Exception {
        Path projectRoot = initRepo();
        Path filePath = projectRoot.resolve("notes.txt");
        Files.writeString(filePath, "import Foo\nplain Foo\n");
        try (Git git = Git.open(projectRoot.toFile())) {
            git.add().addFilepattern("notes.txt").call();
        }

        project = new CoreProject(projectRoot);
        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, new DisabledAnalyzer(project)));

        String result = tools.searchFileContents(List.of("Foo"), "*.txt", "declarations", false, false, 0, 20);
        assertTrue(result.contains("<matches>"));
        assertTrue(result.contains("1: import Foo"));
        assertTrue(result.contains("2: plain Foo"));
        assertFalse(result.contains("<related>"));
    }

    @Test
    void getSummaries_BoundsConcurrentSkeletonComputation() throws Exception {
        Path projectRoot = initRepo();
        int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
        int fileCount = parallelism + 3;
        List<String> targets = new java.util.ArrayList<>();

        for (int i = 0; i < fileCount; i++) {
            String relativePath = "src/main/java/pkg/C%d.java".formatted(i);
            Path file = projectRoot.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "class C%d {}".formatted(i));
            targets.add(relativePath);
        }

        project = new CoreProject(projectRoot);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch firstBatchReady = new CountDownLatch(parallelism);
        CountDownLatch releaseFirstBatch = new CountDownLatch(1);

        IAnalyzer analyzer = new DisabledAnalyzer(project) {
            @Override
            public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
                int active = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(active, Math::max);

                if (active <= parallelism) {
                    firstBatchReady.countDown();
                    if (firstBatchReady.getCount() == 0) {
                        releaseFirstBatch.countDown();
                    }
                }

                try {
                    assertTrue(
                            releaseFirstBatch.await(5, TimeUnit.SECONDS),
                            "Timed out waiting for the first getSummaries batch");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } finally {
                    inFlight.decrementAndGet();
                }

                String shortName = file.absPath().getFileName().toString().replace(".java", "");
                return Map.of(CodeUnit.cls(file, "pkg", shortName), "class %s {}".formatted(shortName));
            }
        };

        SearchTools tools = new SearchTools(new StandaloneCodeIntelligence(project, analyzer));

        String result = tools.getSummaries(targets);

        assertTrue(result.contains("class C0"), "Should include computed skeletons");
        assertEquals(parallelism, maxInFlight.get(), "getSummaries should cap concurrent getSkeletons calls");
    }

    private Path initRepo() throws Exception {
        Path projectRoot = tempDir.resolve("repo");
        Files.createDirectories(projectRoot);
        try (Git git = Git.init().setDirectory(projectRoot.toFile()).call()) {
            Files.writeString(projectRoot.resolve("README.md"), "# Test");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("Test", "test@test.com")
                    .setSign(false)
                    .call();
        }
        return projectRoot;
    }

    private static void commitTrackedFiles(
            Path projectRoot, Map<String, String> filesByPath, Instant instant, String message) throws Exception {
        try (Git git = Git.open(projectRoot.toFile())) {
            var ident = new PersonIdent("Test User", "test@example.com", instant, ZoneId.of("UTC"));
            for (var entry : filesByPath.entrySet()) {
                Path file = projectRoot.resolve(entry.getKey());
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, entry.getValue());
                git.add().addFilepattern(entry.getKey().replace('\\', '/')).call();
            }
            git.commit()
                    .setMessage(message)
                    .setAuthor(ident)
                    .setCommitter(ident)
                    .setSign(false)
                    .call();
        }
    }

    private static String relatedContentSection(String text) {
        int relatedContentIdx = text.indexOf("\n\n## Related Content\n");
        return relatedContentIdx >= 0 ? text.substring(relatedContentIdx) : "";
    }

    private static String mainResultSection(String text) {
        int relatedContentIdx = text.indexOf("\n\n## Related Content\n");
        return relatedContentIdx >= 0 ? text.substring(0, relatedContentIdx) : text;
    }
}
