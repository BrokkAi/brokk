package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RustExportUsageGraphStrategyTest extends AbstractUsageReferenceGraphTest {

    @Test
    void selectorUsesRustGraphForSeededPublicExport() throws Exception {
        String service = "pub struct Service;\n";
        String consumer =
                """
                use crate::service::Service;

                fn run() {
                    let _ = Service::new();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            CodeUnit target = target(analyzer, serviceFile, "Service");

            UsageAnalyzer usageAnalyzer = UsageAnalyzerSelector.forTarget(target, analyzer, project);

            assertInstanceOf(RustExportUsageGraphStrategy.class, usageAnalyzer);
        }
    }

    @Test
    void selectorFallsBackForPrivateRustTarget() throws Exception {
        String service = "struct Service;\n";

        try (var project =
                InlineTestProjectCreator.code(service, "src/service.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            CodeUnit target = target(analyzer, serviceFile, "Service");

            UsageAnalyzer usageAnalyzer = UsageAnalyzerSelector.forTarget(target, analyzer, project);

            assertInstanceOf(RegexUsageAnalyzer.class, usageAnalyzer);
        }
    }

    @Test
    void selectorUsesRustGraphForPrivateSameFileFunctionTarget() throws Exception {
        String searchtools =
                """
                fn summarize_symbol_targets() {}

                pub fn get_summaries() {
                    summarize_symbol_targets();
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(searchtools, "src/searchtools.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile searchtoolsFile = projectFile(project.getAllFiles(), "src/searchtools.rs");
            CodeUnit target = target(analyzer, searchtoolsFile, "summarize_symbol_targets");

            UsageAnalyzer usageAnalyzer = UsageAnalyzerSelector.forTarget(target, analyzer, project);

            assertInstanceOf(RustExportUsageGraphStrategy.class, usageAnalyzer);
        }
    }

    @Test
    void strategyRespectsCandidateFiles() throws Exception {
        String service = "pub struct Service;\n";
        String consumer =
                """
                use crate::service::Service;

                fn run() {
                    let _ = Service::new();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .addFileContents("fn unrelated() {}\n", "src/other.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile otherFile = projectFile(project.getAllFiles(), "src/other.rs");
            CodeUnit target = target(analyzer, serviceFile, "Service");

            FuzzyResult result =
                    new RustExportUsageGraphStrategy(analyzer).findUsages(List.of(target), Set.of(otherFile), 1000);

            assertEquals(
                    0,
                    ((FuzzyResult.Success) result).hitsByOverload().get(target).size());
        }
    }

    @Test
    void strategyFiltersNonRustCallerCandidatesWithoutWideningEmptySets() throws Exception {
        String service = "pub struct Service;\n";
        String consumer =
                """
                use crate::service::Service;

                fn run() {
                    let _ = Service {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .addFileContents("# notes\n", "README.md")
                .addFileContents("[package]\nname = \"demo\"\n", "Cargo.toml")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile readmeFile = projectFile(project.getAllFiles(), "README.md");
            CodeUnit target = target(analyzer, serviceFile, "Service");

            FuzzyResult broadResult =
                    new RustExportUsageGraphStrategy(analyzer).findUsages(List.of(target), project.getAllFiles(), 1000);
            FuzzyResult nonRustOnlyResult =
                    new RustExportUsageGraphStrategy(analyzer).findUsages(List.of(target), Set.of(readmeFile), 1000);

            assertEquals(
                    1,
                    ((FuzzyResult.Success) broadResult)
                            .hitsByOverload()
                            .get(target)
                            .size());
            assertEquals(
                    0,
                    ((FuzzyResult.Success) nonRustOnlyResult)
                            .hitsByOverload()
                            .get(target)
                            .size());
        }
    }

    @Test
    void strategyReturnsTooManyCallsitesWhenHitsExceedLimit() throws Exception {
        String service = "pub struct Service;\n";
        String first =
                """
                use crate::service::Service;
                fn first() { let _ = Service::new(); }
                """;
        String second =
                """
                use crate::service::Service;
                fn second() { let _ = Service::new(); }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(first, "src/first.rs")
                .addFileContents(second, "src/second.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            CodeUnit target = target(analyzer, serviceFile, "Service");

            FuzzyResult result =
                    new RustExportUsageGraphStrategy(analyzer).findUsages(List.of(target), project.getAllFiles(), 1);

            assertInstanceOf(FuzzyResult.TooManyCallsites.class, result);
            assertEquals(1, ((FuzzyResult.TooManyCallsites) result).limit());
        }
    }

    @Test
    void selectorUsesRustGraphForMemberOfPublicExport() throws Exception {
        String service =
                """
                pub struct Service;
                impl Service {
                    pub fn run(&self) {}
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(service, "src/service.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            CodeUnit target = member(analyzer, serviceFile, "Service", "run");

            UsageAnalyzer usageAnalyzer = UsageAnalyzerSelector.forTarget(target, analyzer, project);

            assertInstanceOf(RustExportUsageGraphStrategy.class, usageAnalyzer);
        }
    }

    @Test
    void strategyFindsReceiverUsagesForMemberOfPublicExport() throws Exception {
        String service =
                """
                pub struct Service;
                impl Service {
                    pub fn run(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Service;

                fn main() {
                    let service: Service = Service {};
                    service.run();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            CodeUnit target = member(analyzer, serviceFile, "Service", "run");

            FuzzyResult result =
                    new RustExportUsageGraphStrategy(analyzer).findUsages(List.of(target), project.getAllFiles(), 1000);

            assertEquals(
                    1,
                    ((FuzzyResult.Success) result).hitsByOverload().get(target).size());
        }
    }

    @Test
    void exactMemberCacheReturnsConcreteMemberAcrossRepeatedLookups() throws Exception {
        String service =
                """
                pub struct Service;
                impl Service {
                    pub fn run(&self) {}
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(service, "src/service.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");

            CodeUnit first = analyzer.exactMember(serviceFile, "Service", "run", true);
            CodeUnit second = analyzer.exactMember(serviceFile, "Service", "run", true);

            assertEquals(first, second);
            assertFalse(first.isSynthetic());
        }
    }

    @Test
    void rustCandidateFunnelKeepsLikelyMemberFilesAndDropsUnrelatedFiles() throws Exception {
        String service =
                """
                pub struct Service;
                impl Service {
                    pub fn run(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Service;
                fn main() {
                    let service: Service = Service {};
                    service.run();
                }
                """;
        String unrelated =
                """
                fn unrelated() {
                    let value = 1;
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .addFileContents(unrelated, "src/other.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");
            ProjectFile otherFile = projectFile(project.getAllFiles(), "src/other.rs");
            CodeUnit target = member(analyzer, serviceFile, "Service", "run");

            Set<ProjectFile> candidates = analyzer.rustUsageCandidateFiles(Set.of("Service"), target);

            assertTrue(candidates.contains(consumerFile));
            assertFalse(candidates.contains(otherFile));
        }
    }

    @Test
    void rustUsageFactsCacheFeedsReferencesAndReceivers() throws Exception {
        String service =
                """
                pub struct Service;
                impl Service {
                    pub fn run(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Service;
                fn main() {
                    let service: Service = Service {};
                    service.run();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");
            ImportBinder binder = analyzer.importBinderOf(consumerFile);

            var references = analyzer.exportUsageCandidatesOf(consumerFile, binder);
            var receivers = analyzer.resolvedReceiverCandidatesOf(consumerFile, binder);
            var warmedReferences = analyzer.exportUsageCandidatesOf(consumerFile, binder);
            var warmedReceivers = analyzer.resolvedReceiverCandidatesOf(consumerFile, binder);

            assertEquals(references, warmedReferences);
            assertEquals(receivers, warmedReceivers);
            assertFalse(references.isEmpty());
            assertFalse(receivers.isEmpty());
        }
    }

    @Test
    void strategyFindsSameFileStructReferencesInReturnTypesAndLiterals() throws Exception {
        String summary =
                """
                pub struct RenderedSummary {
                    pub label: String,
                    pub text: String,
                }

                pub fn summarize_inputs(inputs: &[String]) -> Result<Vec<RenderedSummary>, String> {
                    inputs
                        .iter()
                        .map(|input| summarize_input(input))
                        .collect()
                }

                fn summarize_input(input: &str) -> Result<RenderedSummary, String> {
                    Ok(RenderedSummary {
                        label: input.to_string(),
                        text: input.to_string(),
                    })
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(summary, "src/summary.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile summaryFile = projectFile(project.getAllFiles(), "src/summary.rs");
            CodeUnit target = target(analyzer, summaryFile, "RenderedSummary");

            FuzzyResult result = new RustExportUsageGraphStrategy(analyzer).findUsages(List.of(target), Set.of(), 1000);

            assertEquals(
                    3,
                    ((FuzzyResult.Success) result).hitsByOverload().get(target).size());
        }
    }

    @Test
    void strategyFindsPrivateSameFileFunctionCallInsideClosure() throws Exception {
        String summary =
                """
                pub struct RenderedSummary;

                pub fn summarize_inputs(inputs: &[String]) -> Result<Vec<RenderedSummary>, String> {
                    inputs
                        .iter()
                        .map(|input| summarize_input(input))
                        .collect()
                }

                fn summarize_input(input: &str) -> Result<RenderedSummary, String> {
                    Ok(RenderedSummary)
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(summary, "src/summary.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile summaryFile = projectFile(project.getAllFiles(), "src/summary.rs");
            CodeUnit target = target(analyzer, summaryFile, "summarize_input");

            FuzzyResult result = new RustExportUsageGraphStrategy(analyzer).findUsages(List.of(target), Set.of(), 1000);

            assertEquals(
                    1,
                    ((FuzzyResult.Success) result).hitsByOverload().get(target).size());
        }
    }

    private static CodeUnit target(RustAnalyzer analyzer, ProjectFile file, String identifier) {
        return analyzer.getAllDeclarations().stream()
                .filter(cu -> cu.source().equals(file))
                .filter(cu -> cu.identifier().equals(identifier))
                .findFirst()
                .orElseThrow();
    }

    private static CodeUnit member(RustAnalyzer analyzer, ProjectFile file, String ownerName, String memberName) {
        CodeUnit exact = analyzer.exactMember(file, ownerName, memberName, true);
        if (exact != null) {
            return exact;
        }
        exact = analyzer.exactMember(file, ownerName, memberName, false);
        if (exact != null) {
            return exact;
        }
        return analyzer.getAllDeclarations().stream()
                .filter(cu -> cu.source().equals(file))
                .filter(cu -> cu.identifier().equals(memberName))
                .filter(cu -> cu.shortName().startsWith(ownerName + "."))
                .findFirst()
                .orElseThrow();
    }
}
