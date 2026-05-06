package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RustExportUsageReferenceGraphTest extends AbstractUsageReferenceGraphTest {

    @Test
    void crateImportResolvesExportedStructUsage() throws Exception {
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
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            var result = find(analyzer, serviceFile, "Service", consumerFile);

            assertEquals(1, result.hits().size());
            assertTrue(endsWithPath(result.hits().iterator().next().file(), "src/main.rs"));
        }
    }

    @Test
    void aliasedImportResolvesExportedStructUsage() throws Exception {
        String service = "pub struct Service;\n";
        String consumer =
                """
                use crate::service::Service as S;

                fn run() {
                    let _ = S::new();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            var result = find(analyzer, serviceFile, "Service", consumerFile);

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void groupedImportResolvesExportedStructUsage() throws Exception {
        String service = """
                pub struct Service;
                pub struct Helper;
                """;
        String consumer =
                """
                use crate::service::{Service, Helper};

                fn run() {
                    let _ = Service::new();
                    let _ = Helper::new();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            var serviceResult = find(analyzer, serviceFile, "Service", consumerFile);
            var helperResult = find(analyzer, serviceFile, "Helper", consumerFile);

            assertEquals(1, serviceResult.hits().size());
            assertEquals(1, helperResult.hits().size());
        }
    }

    @Test
    void selfImportResolvesModuleQualifiedReference() throws Exception {
        String service = "pub fn factory() {}\n";
        String consumer =
                """
                use crate::service::{self};

                fn run() {
                    service::factory();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            var result = find(analyzer, serviceFile, "factory", consumerFile);

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void publicReexportAliasResolvesUsage() throws Exception {
        String service = "pub struct Service;\n";
        String index = "pub use crate::service::Service as PublicService;\n";
        String consumer =
                """
                use crate::index::PublicService;

                fn run() {
                    let _ = PublicService::new();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(index, "src/index.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            var result = find(analyzer, serviceFile, "Service", consumerFile);

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void moduleLayoutsAndRelativePathsResolve() throws Exception {
        String service = "pub struct Service;\n";
        String nested =
                """
                use super::service::Service;

                fn run() {
                    let _ = Service::new();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/pkg/service.rs")
                .addFileContents(nested, "src/pkg/nested/mod.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/pkg/service.rs");
            ProjectFile nestedFile = projectFile(project.getAllFiles(), "src/pkg/nested/mod.rs");

            var result = find(analyzer, serviceFile, "Service", nestedFile);

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void typeArgumentsCountAsUsagesOfArgumentTypes() throws Exception {
        String service = "pub struct Foo;\n";
        String consumer =
                """
                use crate::service::Foo;
                use std::collections::HashMap;

                struct Holder {
                    a: Vec<Foo>,
                    b: Option<Foo>,
                    c: HashMap<String, Foo>,
                    d: Result<Vec<Foo>, Error>,
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            var result = find(analyzer, serviceFile, "Foo", consumerFile);

            assertEquals(4, result.hits().size());
        }
    }

    @Test
    void unresolvedPublicReexportRecordsExternalFrontier() throws Exception {
        String index = "pub use external_crate::Foo;\n";

        try (var project = InlineTestProjectCreator.code(index, "src/index.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile indexFile = projectFile(project.getAllFiles(), "src/index.rs");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    indexFile,
                    "Foo",
                    null,
                    new RustExportUsageGraphAdapter(analyzer),
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(indexFile));

            assertTrue(result.hits().isEmpty());
            assertTrue(result.externalFrontierSpecifiers().contains("external_crate"));
        }
    }

    @Test
    void localDefinitionShadowsImportedName() throws Exception {
        String service = "pub struct Service;\n";
        String consumer =
                """
                use crate::service::Service;

                struct Service;

                fn run() {
                    let _ = Service::new();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            var result = find(analyzer, serviceFile, "Service", consumerFile);

            assertTrue(result.hits().isEmpty());
        }
    }

    @Test
    void privateItemsDoNotSeedGraphExports() throws Exception {
        String service = "struct Service;\n";

        try (var project =
                InlineTestProjectCreator.code(service, "src/service.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");

            assertFalse(analyzer.exportIndexOf(serviceFile).exportsByName().containsKey("Service"));
        }
    }

    private static ReferenceGraphResult find(
            RustAnalyzer analyzer, ProjectFile definingFile, String exportName, ProjectFile candidate)
            throws InterruptedException {
        return ExportUsageReferenceGraphEngine.findExportUsages(
                definingFile,
                exportName,
                null,
                new RustExportUsageGraphAdapter(analyzer),
                ExportUsageReferenceGraphEngine.Limits.defaults(),
                Set.of(candidate));
    }
}
