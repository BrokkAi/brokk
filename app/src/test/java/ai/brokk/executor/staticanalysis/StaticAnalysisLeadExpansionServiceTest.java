package ai.brokk.executor.staticanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CapabilityProvider;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ImportAnalysisProvider;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TypeHierarchyProvider;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticAnalysisLeadExpansionServiceTest {
    @Test
    void expandLeads_findsUsageConnectedFiles(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/User.java", "package p; class User { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "completed",
                response.state(),
                response.events().getLast().outcome().message());
        assertFalse(response.seeds().isEmpty());
        var seed = response.seeds().getFirst();
        assertEquals("src/main/java/p/User.java", seed.file());
        assertEquals("usage_expansion", seed.selection().kind());
        assertEquals(
                List.of("reportExceptionHandlingSmells", "reportCommentDensityForFiles", "computeCognitiveComplexity"),
                seed.suggestedTools());
        assertEquals("usage_connectivity", seed.selection().signals().getFirst().kind());
    }

    @Test
    void expandLeads_ordersEqualScoreCandidatesByNormalizedPath(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/ZUser.java", "package p; class ZUser { Target target; }");
        javaFile(root, "src/main/java/p/AUser.java", "package p; class AUser { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "completed",
                response.state(),
                response.events().getLast().outcome().message());
        assertEquals(
                List.of("src/main/java/p/AUser.java", "src/main/java/p/ZUser.java"),
                response.seeds().stream()
                        .map(StaticAnalysisSeedDtos.SeedRecord::file)
                        .toList());
        assertEquals(1, response.seeds().getFirst().rank());
        assertEquals("usage_expansion", response.seeds().getFirst().selection().kind());
    }

    @Test
    void expandLeads_suggestsTestAssertionToolForTestFiles(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        var testFile =
                javaFile(root, "src/test/java/p/TargetTest.java", "package p; class TargetTest { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        analyzer.setContainsTests(testFile, true);
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "completed",
                response.state(),
                response.events().getLast().outcome().message());
        var seed = response.seeds().getFirst();
        assertEquals("src/test/java/p/TargetTest.java", seed.file());
        assertEquals(
                List.of(
                        "reportExceptionHandlingSmells",
                        "reportCommentDensityForFiles",
                        "computeCognitiveComplexity",
                        "reportTestAssertionSmells"),
                seed.suggestedTools());
    }

    @Test
    void expandLeads_dedupesKnownFiles(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/User.java", "package p; class User { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java", "src/main/java/p/User.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "skipped",
                response.state(),
                response.events().getLast().outcome().message());
        assertTrue(response.seeds().isEmpty());
    }

    @Test
    void expandLeads_normalizesKnownFileSeparators(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/User.java", "package p; class User { Target target; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src\\main\\java\\p\\Target.java", "src\\main\\java\\p\\User.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "skipped",
                response.state(),
                response.events().getLast().outcome().message());
        assertTrue(response.seeds().isEmpty());
    }

    @Test
    void expandLeads_skipsFilesWithoutLiteralFallbackMatches(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/User.java", "package p; class User { String value = \"missing\"; }");
        var analyzer = new TestAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "skipped",
                response.state(),
                response.events().getLast().outcome().message());
        assertTrue(response.seeds().isEmpty());
    }

    @Test
    void expandLeads_textFallbackCapsCandidateFilesWithDeterministicBoundedSelection(@TempDir Path root)
            throws Exception {
        var target = javaFile(root, "src/main/java/z/Target.java", "package z; public class Target {}");
        var user = javaFile(root, "src/main/java/z/User.java", "package z; class User { Target target; }");
        var files = new ArrayList<ProjectFile>();
        files.add(target);
        files.add(user);
        for (int i = 0; i < 248; i++) {
            files.add(javaFile(
                    root, "src/main/java/z/Filler%03d.java".formatted(i), "package z; class Filler%d {}".formatted(i)));
        }
        var lexicallyEarlierOutsideCap =
                javaFile(root, "src/main/java/a/Outside.java", "package a; class Outside { Target target; }");
        files.add(lexicallyEarlierOutsideCap);
        var analyzer = new FallbackOrderedFilesAnalyzer(files);
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "z", "Target", null, false));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/z/Target.java"),
                List.of("src/main/java/z/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "completed",
                response.state(),
                response.events().getLast().outcome().message());
        assertEquals(
                List.of("src/main/java/a/Outside.java"),
                response.seeds().stream()
                        .map(StaticAnalysisSeedDtos.SeedRecord::file)
                        .toList());
    }

    @Test
    void literalOccurrenceCount_countsAdjacentRepeatedMatches() {
        assertEquals(2, StaticAnalysisLeadExpansionService.literalOccurrenceCount("TargetTarget", "Target"));
    }

    @Test
    void literalOccurrenceCount_countsRegexShapedNeedleLiterally() {
        assertEquals(1, StaticAnalysisLeadExpansionService.literalOccurrenceCount("A.B AxB", "A.B"));
    }

    @Test
    void literalOccurrenceCount_returnsZeroForMissingAndEmptyNeedle() {
        assertEquals(0, StaticAnalysisLeadExpansionService.literalOccurrenceCount("Target", "Missing"));
        assertEquals(0, StaticAnalysisLeadExpansionService.literalOccurrenceCount("Target", ""));
    }

    @Test
    void expandLeads_reusesCachedDeclarationTraversalForDuplicateFrontierFile(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/User.java", "package p; class User { Target target; }");
        var targetClass = new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false);
        var targetMethod = new CodeUnit(target, CodeUnitType.FUNCTION, "p.Target", "run", "()", false);
        var analyzer = new CountingAnalyzer();
        analyzer.addDeclaration(targetClass);
        analyzer.setDirectChildren(targetClass, List.of(targetMethod));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java"),
                List.of("src/main/java/p/Target.java", "src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "completed",
                response.state(),
                response.events().getLast().outcome().message());
        assertFalse(response.seeds().isEmpty());
        assertEquals(1, analyzer.topLevelDeclarationCalls(target));
        assertEquals(1, analyzer.directChildrenCalls(targetClass));
    }

    @Test
    void expandLeads_limitsSymbolsWithoutSortingEveryDeclaration(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(root, "src/main/java/p/User.java", "package p; class User { A00 target; }");
        var analyzer = new CountingAnalyzer();
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false));
        analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "A00", null, false));
        for (int i = 0; i < 16; i++) {
            analyzer.addDeclaration(new CodeUnit(target, CodeUnitType.CLASS, "p", "Z%02d".formatted(i), null, false));
        }
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "completed",
                response.state(),
                response.events().getLast().outcome().message());
        assertEquals(
                List.of("src/main/java/p/User.java"),
                response.seeds().stream()
                        .map(StaticAnalysisSeedDtos.SeedRecord::file)
                        .toList());
    }

    @Test
    void expandLeads_preservesDistinctDeclarationsWithSameFqName(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; public class Target {}");
        javaFile(
                root,
                "src/main/java/p/User.java",
                "package p; class User { int x = Target.run(1) + Target.run(2, 3); }");
        var analyzer = new CountingAnalyzer();
        var targetClass = new CodeUnit(target, CodeUnitType.CLASS, "p", "Target", null, false);
        analyzer.addDeclaration(targetClass);
        analyzer.setDirectChildren(
                targetClass,
                List.of(
                        new CodeUnit(target, CodeUnitType.FUNCTION, "p.Target", "run", "(int)", false),
                        new CodeUnit(target, CodeUnitType.FUNCTION, "p.Target", "run", "(int,int)", false)));
        var service = service(root, analyzer);

        var response = service.expandLeads(new StaticAnalysisSeedDtos.NormalizedLeadExpansionRequest(
                "scan-1",
                List.of("src/main/java/p/Target.java"),
                List.of("src/main/java/p/Target.java"),
                5,
                15_000,
                false));

        assertEquals(
                "completed",
                response.state(),
                response.events().getLast().outcome().message());
        assertEquals(
                List.of("src/main/java/p/User.java"),
                response.seeds().stream()
                        .map(StaticAnalysisSeedDtos.SeedRecord::file)
                        .toList());
    }

    @Test
    void expandLeads_preservesStableOrderWhenSameFqNameExceedsCap(@TempDir Path root) throws Exception {
        var target = javaFile(root, "src/main/java/p/Target.java", "package p; class Target {}");
        var declarations = new ArrayList<CodeUnit>();
        for (int i = 0; i < 8; i++) {
            declarations.add(new CodeUnit(target, CodeUnitType.FUNCTION, "p", "run", "(z%d)".formatted(i), false));
        }
        var lexicallyEarlierLateDeclaration = new CodeUnit(target, CodeUnitType.FUNCTION, "p", "run", "(a)", false);
        declarations.add(lexicallyEarlierLateDeclaration);

        var selected = StaticAnalysisLeadExpansionService.firstAlphabeticalSymbols(declarations, 8);

        assertEquals(8, selected.size());
        assertEquals(declarations.subList(0, 8), selected);
        assertFalse(selected.contains(lexicallyEarlierLateDeclaration));
    }

    private static StaticAnalysisLeadExpansionService service(Path root, TestAnalyzer analyzer) {
        return new StaticAnalysisLeadExpansionService(
                new TestContextManager(new TestProject(root, Languages.JAVA), new TestConsoleIO(), Set.of(), analyzer));
    }

    private static ProjectFile javaFile(Path root, String relPath, String source) throws Exception {
        var file = new ProjectFile(root, relPath);
        Files.createDirectories(file.absPath().getParent());
        Files.writeString(file.absPath(), source);
        return file;
    }

    private static final class CountingAnalyzer extends TestAnalyzer {
        private final Map<ProjectFile, Integer> topLevelDeclarationCalls = new HashMap<>();
        private final Map<CodeUnit, Integer> directChildrenCalls = new HashMap<>();
        private final Map<CodeUnit, List<CodeUnit>> directChildren = new HashMap<>();

        @Override
        public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
            topLevelDeclarationCalls.merge(file, 1, Integer::sum);
            return super.getTopLevelDeclarations(file);
        }

        @Override
        public List<CodeUnit> getDirectChildren(CodeUnit cu) {
            directChildrenCalls.merge(cu, 1, Integer::sum);
            return directChildren.getOrDefault(cu, List.of());
        }

        private void setDirectChildren(CodeUnit cu, List<CodeUnit> children) {
            directChildren.put(cu, List.copyOf(children));
        }

        private int topLevelDeclarationCalls(ProjectFile file) {
            return topLevelDeclarationCalls.getOrDefault(file, 0);
        }

        private int directChildrenCalls(CodeUnit cu) {
            return directChildrenCalls.getOrDefault(cu, 0);
        }
    }

    private static final class FallbackOrderedFilesAnalyzer extends TestAnalyzer {
        private final List<ProjectFile> files;

        private FallbackOrderedFilesAnalyzer(List<ProjectFile> files) {
            this.files = List.copyOf(files);
        }

        @Override
        public Set<ProjectFile> getAnalyzedFiles() {
            return new LinkedHashSet<>(files);
        }

        @Override
        public <T extends CapabilityProvider> Optional<T> as(Class<T> capability) {
            if (capability == TypeHierarchyProvider.class || capability == ImportAnalysisProvider.class) {
                throw new RuntimeException("force fallback");
            }
            return super.as(capability);
        }
    }
}
