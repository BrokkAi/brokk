package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExportUsageGraphAdapterTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final ProjectFile A = new ProjectFile(ROOT, "a.fake");
    private static final ProjectFile B = new ProjectFile(ROOT, "b.fake");
    private static final ProjectFile C = new ProjectFile(ROOT, "c.fake");
    private static final CodeUnit FOO = CodeUnit.fn(A, "", "foo");
    private static final CodeUnit ENCLOSING_B = CodeUnit.module(B, "", "_module_");
    private static final IAnalyzer.Range RANGE = new IAnalyzer.Range(0, 3, 0, 0, 0);

    @Test
    void adapterGraphFindsProvenImportUsage() throws Exception {
        var adapter = new FakeAdapter(
                Map.of(
                        A,
                        new ExportIndex(
                                Map.of("foo", new ExportIndex.LocalExport("foo")), List.of(), Set.of(), Set.of())),
                Map.of(
                        B,
                        new ImportBinder(Map.of(
                                "foo", new ImportBinder.ImportBinding("./a", ImportBinder.ImportKind.NAMED, "foo")))),
                Map.of(B, Set.of(candidate("foo"))),
                Map.of(A, Set.of(B)),
                Map.of(new ModuleKey(B, "./a"), ExportUsageGraphLanguageAdapter.ResolutionOutcome.resolved(A)),
                Map.of("foo", Set.of(FOO)));

        var result = JsTsExportUsageReferenceGraph.findExportUsages(
                A, "foo", null, adapter, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

        assertEquals(1, result.hits().size());
        assertEquals(B, result.hits().iterator().next().file());
    }

    @Test
    void candidateFilesRestrictAdapterGraph() throws Exception {
        var adapter = new FakeAdapter(
                Map.of(
                        A,
                        new ExportIndex(
                                Map.of("foo", new ExportIndex.LocalExport("foo")), List.of(), Set.of(), Set.of())),
                Map.of(
                        B,
                        new ImportBinder(Map.of(
                                "foo", new ImportBinder.ImportBinding("./a", ImportBinder.ImportKind.NAMED, "foo")))),
                Map.of(B, Set.of(candidate("foo"))),
                Map.of(A, Set.of(B)),
                Map.of(new ModuleKey(B, "./a"), ExportUsageGraphLanguageAdapter.ResolutionOutcome.resolved(A)),
                Map.of("foo", Set.of(FOO)));

        var result = JsTsExportUsageReferenceGraph.findExportUsages(
                A, "foo", null, adapter, JsTsExportUsageReferenceGraph.Limits.defaults(), Set.of(C));

        assertTrue(result.hits().isEmpty());
    }

    @Test
    void externalImportsBecomeFrontierWithoutFalseHits() throws Exception {
        var adapter = new FakeAdapter(
                Map.of(
                        A,
                        new ExportIndex(
                                Map.of("foo", new ExportIndex.LocalExport("foo")), List.of(), Set.of(), Set.of())),
                Map.of(
                        B,
                        new ImportBinder(Map.of(
                                "foo",
                                new ImportBinder.ImportBinding("external-pkg", ImportBinder.ImportKind.NAMED, "foo")))),
                Map.of(B, Set.of(candidate("foo"))),
                Map.of(A, Set.of(B)),
                Map.of(
                        new ModuleKey(B, "external-pkg"),
                        ExportUsageGraphLanguageAdapter.ResolutionOutcome.external("external-pkg")),
                Map.of("foo", Set.of(FOO)));

        var result = JsTsExportUsageReferenceGraph.findExportUsages(
                A, "foo", null, adapter, JsTsExportUsageReferenceGraph.Limits.defaults(), null);

        assertTrue(result.hits().isEmpty());
        assertEquals(Set.of("external-pkg"), result.externalFrontierSpecifiers());
    }

    private static ReferenceCandidate candidate(String identifier) {
        return new ReferenceCandidate(
                identifier, null, null, false, ReferenceKind.STATIC_REFERENCE, RANGE, ENCLOSING_B);
    }

    private record ModuleKey(ProjectFile importingFile, String moduleSpecifier) {}

    private record FakeAdapter(
            Map<ProjectFile, ExportIndex> exports,
            Map<ProjectFile, ImportBinder> imports,
            Map<ProjectFile, Set<ReferenceCandidate>> candidates,
            Map<ProjectFile, Set<ProjectFile>> referencing,
            Map<ModuleKey, ExportUsageGraphLanguageAdapter.ResolutionOutcome> resolutions,
            Map<String, Set<CodeUnit>> definitions)
            implements ExportUsageGraphLanguageAdapter {

        @Override
        public ExportIndex exportIndexOf(ProjectFile file) {
            return exports.getOrDefault(file, ExportIndex.empty());
        }

        @Override
        public ImportBinder importBinderOf(ProjectFile file) {
            return imports.getOrDefault(file, ImportBinder.empty());
        }

        @Override
        public Set<ReferenceCandidate> usageCandidatesOf(ProjectFile file, ImportBinder binder) {
            return candidates.getOrDefault(file, Set.of());
        }

        @Override
        public Set<CodeUnit> definitionsOf(String localName) {
            return definitions.getOrDefault(localName, Set.of());
        }

        @Override
        public ResolutionOutcome resolveModule(ProjectFile importingFile, String moduleSpecifier) {
            return resolutions.getOrDefault(new ModuleKey(importingFile, moduleSpecifier), ResolutionOutcome.empty());
        }

        @Override
        public Set<ProjectFile> referencingFilesOf(ProjectFile file) {
            return referencing.getOrDefault(file, Set.of());
        }
    }
}
