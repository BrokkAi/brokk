package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    private static CodeUnit target(RustAnalyzer analyzer, ProjectFile file, String identifier) {
        return analyzer.getAllDeclarations().stream()
                .filter(cu -> cu.source().equals(file))
                .filter(cu -> cu.identifier().equals(identifier))
                .findFirst()
                .orElseThrow();
    }
}
