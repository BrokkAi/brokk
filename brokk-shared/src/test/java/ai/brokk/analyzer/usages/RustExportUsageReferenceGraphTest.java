package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
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

    @Test
    void typedReceiverResolvesInstanceMethod() throws Exception {
        String service =
                """
                pub struct Foo;
                impl Foo {
                    pub fn bar(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run(x: Foo) {
                    let y: Foo = x;
                    y.bar();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");
            CodeUnit target = member(analyzer, serviceFile, "Foo", "bar");

            var result = find(analyzer, serviceFile, "Foo", target, consumerFile);

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void constructorAndStructLiteralReceiversResolveInstanceMethod() throws Exception {
        String service =
                """
                pub struct Foo;
                impl Foo {
                    pub fn new() -> Foo { Foo }
                    pub fn bar(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run() {
                    let a = Foo::new();
                    a.bar();
                    let b = Foo {};
                    b.bar();
                    let c = a;
                    c.bar();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");
            CodeUnit target = member(analyzer, serviceFile, "Foo", "bar");

            var result = find(analyzer, serviceFile, "Foo", target, consumerFile);

            assertEquals(3, result.hits().size());
        }
    }

    @Test
    void associatedMethodAndConstResolveWithoutReceiverInference() throws Exception {
        String service =
                """
                pub struct Foo;
                impl Foo {
                    pub const CONST: usize = 1;
                    pub fn make() -> Foo { Foo }
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run() {
                    let _ = Foo::make();
                    let _ = Foo::CONST;
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");
            CodeUnit make = member(analyzer, serviceFile, "Foo", "make");
            CodeUnit constant = member(analyzer, serviceFile, "Foo", "CONST");

            assertEquals(
                    1,
                    find(analyzer, serviceFile, "Foo", make, consumerFile)
                            .hits()
                            .size());
            assertEquals(
                    1,
                    find(analyzer, serviceFile, "Foo", constant, consumerFile)
                            .hits()
                            .size());
        }
    }

    @Test
    void duplicateOwnerNamesAcrossModulesDoNotCrossMatch() throws Exception {
        String service =
                """
                pub struct Foo;
                impl Foo {
                    pub fn bar(&self) {}
                }
                """;
        String other =
                """
                pub struct Foo;
                impl Foo {
                    pub fn bar(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run() {
                    let x: Foo = Foo {};
                    x.bar();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(other, "src/other.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile otherFile = projectFile(project.getAllFiles(), "src/other.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1,
                    find(analyzer, serviceFile, "Foo", member(analyzer, serviceFile, "Foo", "bar"), consumerFile)
                            .hits()
                            .size());
            assertTrue(find(analyzer, otherFile, "Foo", member(analyzer, otherFile, "Foo", "bar"), consumerFile)
                    .hits()
                    .isEmpty());
        }
    }

    @Test
    void traitMethodResolvesExplicitTraitPathAndProvenReceiver() throws Exception {
        String service =
                """
                pub struct Foo;
                pub trait Worker {
                    fn work(&self);
                }
                impl Worker for Foo {
                    fn work(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::{Foo, Worker};

                fn run() {
                    let x: Foo = Foo {};
                    Worker::work(&x);
                    x.work();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");
            CodeUnit target = member(analyzer, serviceFile, "Worker", "work");

            var result = find(analyzer, serviceFile, "Worker", target, consumerFile);

            assertEquals(2, result.hits().size(), result.hits().toString());
        }
    }

    @Test
    void traitReceiverRequiresProvenImplAndReceiverType() throws Exception {
        String service =
                """
                pub struct Foo;
                pub trait Worker {
                    fn work(&self);
                }
                pub trait Other {
                    fn work(&self);
                }
                impl Worker for Foo {
                    fn work(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn known() {
                    let x: Foo = Foo {};
                    x.work();
                }

                fn unknown(x: impl std::fmt::Debug) {
                    x.work();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1,
                    find(analyzer, serviceFile, "Worker", member(analyzer, serviceFile, "Worker", "work"), consumerFile)
                            .hits()
                            .size());
            assertTrue(
                    find(analyzer, serviceFile, "Other", member(analyzer, serviceFile, "Other", "work"), consumerFile)
                            .hits()
                            .isEmpty());
        }
    }

    @Test
    void functionParameterTypeSeedsReceiver() throws Exception {
        String service =
                """
                pub struct Foo;
                impl Foo {
                    pub fn bar(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run(x: Foo) {
                    x.bar();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1,
                    find(analyzer, serviceFile, "Foo", member(analyzer, serviceFile, "Foo", "bar"), consumerFile)
                            .hits()
                            .size());
        }
    }

    @Test
    void simpleTypeAliasSeedsReceiver() throws Exception {
        String service =
                """
                pub struct Foo;
                impl Foo {
                    pub fn bar(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                type Alias = Foo;

                fn run(value: Alias) {
                    value.bar();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1,
                    find(analyzer, serviceFile, "Foo", member(analyzer, serviceFile, "Foo", "bar"), consumerFile)
                            .hits()
                            .size());
        }
    }

    @Test
    void nonConcreteParameterTypesDoNotSeedReceivers() throws Exception {
        String service =
                """
                pub struct Foo;
                pub trait Worker {
                    fn work(&self);
                }
                impl Worker for Foo {
                    fn work(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Worker;

                fn generic<T: Worker>(x: T) {
                    x.work();
                }

                fn opaque(x: impl Worker) {
                    x.work();
                }

                fn dynamic(x: &dyn Worker) {
                    x.work();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertTrue(
                    find(analyzer, serviceFile, "Worker", member(analyzer, serviceFile, "Worker", "work"), consumerFile)
                            .hits()
                            .isEmpty());
        }
    }

    @Test
    void enumVariantsResolveAsAssociatedFields() throws Exception {
        String service =
                """
                pub enum Foo {
                    Variant,
                    TupleVariant(usize),
                    StructVariant { value: usize },
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run() {
                    let _ = Foo::Variant;
                    let _ = Foo::TupleVariant(1);
                    let _ = Foo::StructVariant { value: 1 };
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1,
                    find(analyzer, serviceFile, "Foo", member(analyzer, serviceFile, "Foo", "Variant"), consumerFile)
                            .hits()
                            .size());
            assertEquals(
                    1,
                    find(
                                    analyzer,
                                    serviceFile,
                                    "Foo",
                                    member(analyzer, serviceFile, "Foo", "TupleVariant"),
                                    consumerFile)
                            .hits()
                            .size());
            assertEquals(
                    1,
                    find(
                                    analyzer,
                                    serviceFile,
                                    "Foo",
                                    member(analyzer, serviceFile, "Foo", "StructVariant"),
                                    consumerFile)
                            .hits()
                            .size());
        }
    }

    @Test
    void associatedTypeResolvesAsStaticAssociatedField() throws Exception {
        String service =
                """
                pub struct Foo;
                impl Foo {
                    pub type AssocType = usize;
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run(_: Foo::AssocType) {}
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");
            CodeUnit assocType =
                    CodeUnit.field(serviceFile, "", "Foo.AssocType").withSynthetic(true);

            assertEquals(
                    1,
                    find(analyzer, serviceFile, "Foo", assocType, consumerFile)
                            .hits()
                            .size());
        }
    }

    @Test
    void boundedGlobImportResolvesKnownLocalExportsOnly() throws Exception {
        String service = """
                pub struct Foo;
                struct Hidden;
                """;
        String consumer =
                """
                use crate::service::*;

                fn run() {
                    let _ = Foo {};
                    let _ = Hidden {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1, find(analyzer, serviceFile, "Foo", consumerFile).hits().size());
            assertTrue(
                    find(analyzer, serviceFile, "Hidden", consumerFile).hits().isEmpty());
        }
    }

    @Test
    void boundedGlobReexportResolvesKnownLocalExports() throws Exception {
        String service = "pub struct Foo;\n";
        String index = "pub use crate::service::*;\n";
        String consumer =
                """
                use crate::index::Foo;

                fn run() {
                    let _ = Foo {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(index, "src/index.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1, find(analyzer, serviceFile, "Foo", consumerFile).hits().size());
        }
    }

    @Test
    void unresolvedGlobReexportRecordsExternalFrontier() throws Exception {
        String index = "pub use external_crate::*;\n";

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
    void publicModuleDeclarationsAndInlineModulesResolve() throws Exception {
        String service = "pub struct FileBacked;\n";
        String lib =
                """
                pub mod service;
                pub mod inline {
                    pub struct Inline;
                }
                """;
        String consumer =
                """
                use crate::service::FileBacked;
                use crate::inline::Inline;

                fn run() {
                    let _ = FileBacked {};
                    let _ = Inline {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(lib, "src/lib.rs")
                .addFileContents(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile libFile = projectFile(project.getAllFiles(), "src/lib.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1,
                    find(analyzer, serviceFile, "FileBacked", consumerFile)
                            .hits()
                            .size());
            assertEquals(
                    1, find(analyzer, libFile, "Inline", consumerFile).hits().size());
        }
    }

    private static ReferenceGraphResult find(
            RustAnalyzer analyzer, ProjectFile definingFile, String exportName, ProjectFile candidate)
            throws InterruptedException {
        return find(analyzer, definingFile, exportName, null, candidate);
    }

    private static ReferenceGraphResult find(
            RustAnalyzer analyzer, ProjectFile definingFile, String exportName, CodeUnit target, ProjectFile candidate)
            throws InterruptedException {
        return ExportUsageReferenceGraphEngine.findExportUsages(
                definingFile,
                exportName,
                target,
                new RustExportUsageGraphAdapter(analyzer),
                ExportUsageReferenceGraphEngine.Limits.defaults(),
                Set.of(candidate));
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
