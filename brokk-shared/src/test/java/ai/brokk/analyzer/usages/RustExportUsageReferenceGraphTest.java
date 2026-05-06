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
    void functionParameterTypesCountAsUsages() throws Exception {
        String service = "pub struct SearchSymbolsParams;\n";
        String consumer =
                """
                use crate::service::SearchSymbolsParams;

                pub fn search_symbols(
                    analyzer: &dyn IAnalyzer,
                    params: SearchSymbolsParams,
                ) {
                    let _ = analyzer;
                    let _ = params;
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/searchtools.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile searchtoolsFile = projectFile(project.getAllFiles(), "src/searchtools.rs");

            var result = find(analyzer, serviceFile, "SearchSymbolsParams", searchtoolsFile);

            assertEquals(1, result.hits().size());
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
    void privateInherentAssociatedItemsDoNotResolveAsMemberHits() throws Exception {
        String service =
                """
                pub struct Foo;
                impl Foo {
                    fn private(&self) {}
                    const PRIVATE: usize = 1;
                    type Private = usize;
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run() {
                    let x: Foo = Foo {};
                    x.private();
                    let _ = Foo::PRIVATE;
                    let _: Foo::Private;
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertTrue(find(analyzer, serviceFile, "Foo", CodeUnit.fn(serviceFile, "", "Foo.private"), consumerFile)
                    .hits()
                    .isEmpty());
            assertTrue(find(analyzer, serviceFile, "Foo", CodeUnit.field(serviceFile, "", "Foo.PRIVATE"), consumerFile)
                    .hits()
                    .isEmpty());
            assertTrue(find(analyzer, serviceFile, "Foo", CodeUnit.field(serviceFile, "", "Foo.Private"), consumerFile)
                    .hits()
                    .isEmpty());
        }
    }

    @Test
    void publicAssociatedItemOnPrivateOwnerDoesNotSeedGraph() throws Exception {
        String service =
                """
                struct Foo;
                impl Foo {
                    pub fn public(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run(x: Foo) {
                    x.public();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertFalse(analyzer.exportIndexOf(serviceFile).exportsByName().containsKey("Foo"));
            assertTrue(find(analyzer, serviceFile, "Foo", CodeUnit.fn(serviceFile, "", "Foo.public"), consumerFile)
                    .hits()
                    .isEmpty());
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
    void crossFileTraitImplResolvesTraitOwnerFile() throws Exception {
        String traits =
                """
                pub trait Worker {
                    fn work(&self);
                }
                """;
        String other =
                """
                pub trait Worker {
                    fn work(&self);
                }
                """;
        String service =
                """
                use crate::traits::Worker;

                pub struct Foo;
                impl Worker for Foo {
                    fn work(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run(x: Foo) {
                    x.work();
                }
                """;

        try (var project = InlineTestProjectCreator.code(traits, "src/traits.rs")
                .addFileContents(other, "src/other.rs")
                .addFileContents(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile traitsFile = projectFile(project.getAllFiles(), "src/traits.rs");
            ProjectFile otherFile = projectFile(project.getAllFiles(), "src/other.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1,
                    find(analyzer, traitsFile, "Worker", member(analyzer, traitsFile, "Worker", "work"), consumerFile)
                            .hits()
                            .size());
            assertTrue(find(analyzer, otherFile, "Worker", member(analyzer, otherFile, "Worker", "work"), consumerFile)
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
    void selfLikeAssociatedConstructorChainSeedsReceiver() throws Exception {
        String service =
                """
                pub struct ChangeDelta;
                pub struct ProjectChangeWatcher;
                impl ProjectChangeWatcher {
                    pub fn start() -> Result<Self, String> {
                        todo!()
                    }
                    pub fn other() -> ChangeDelta {
                        todo!()
                    }
                    pub fn take_changed_files(&self) -> ChangeDelta {
                        todo!()
                    }
                }
                """;
        String consumer =
                """
                use crate::service::ProjectChangeWatcher;

                fn run() {
                    let watcher = ProjectChangeWatcher::start().unwrap();
                    watcher.take_changed_files();
                }

                fn unrelated() {
                    let delta = ProjectChangeWatcher::other();
                    delta.take_changed_files();
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
                    find(
                                    analyzer,
                                    serviceFile,
                                    "ProjectChangeWatcher",
                                    member(analyzer, serviceFile, "ProjectChangeWatcher", "take_changed_files"),
                                    consumerFile)
                            .hits()
                            .size());
        }
    }

    @Test
    void selfFieldAsRefLetElseSeedsReceiverFromStructFieldType() throws Exception {
        String service =
                """
                pub struct ChangeDelta;
                pub struct ProjectChangeWatcher;
                impl ProjectChangeWatcher {
                    pub fn take_changed_files(&self) -> ChangeDelta {
                        todo!()
                    }
                }

                pub struct SearchToolsService {
                    watcher: Option<ProjectChangeWatcher>,
                }
                impl SearchToolsService {
                    pub fn apply_watcher_delta(&mut self) {
                        let Some(watcher) = self.watcher.as_ref() else {
                            return;
                        };
                        watcher.take_changed_files();
                    }
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(service, "src/service.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");

            assertEquals(
                    1,
                    find(
                                    analyzer,
                                    serviceFile,
                                    "ProjectChangeWatcher",
                                    member(analyzer, serviceFile, "ProjectChangeWatcher", "take_changed_files"),
                                    serviceFile)
                            .hits()
                            .size());
        }
    }

    @Test
    void wrappedPatternDestructuringDoesNotSeedReceiverFacts() throws Exception {
        String service =
                """
                pub struct ProjectChangeWatcher;
                impl ProjectChangeWatcher {
                    pub fn take_changed_files(&self) {}
                }

                pub struct Other;
                impl Other {
                    pub fn take_changed_files(&self) {}
                }

                pub struct SearchToolsService {
                    watcher: Option<(ProjectChangeWatcher, Other)>,
                }
                impl SearchToolsService {
                    pub fn apply_watcher_delta(&mut self) {
                        let Some((watcher, other)) = self.watcher.as_ref() else {
                            return;
                        };
                        watcher.take_changed_files();
                        other.take_changed_files();
                    }
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(service, "src/service.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");

            assertTrue(find(
                            analyzer,
                            serviceFile,
                            "ProjectChangeWatcher",
                            member(analyzer, serviceFile, "ProjectChangeWatcher", "take_changed_files"),
                            serviceFile)
                    .hits()
                    .isEmpty());
        }
    }

    @Test
    void destructuringPatternsDoNotSeedReceiverFacts() throws Exception {
        String service =
                """
                pub struct Foo;
                impl Foo {
                    pub fn foo_method(&self) {}
                }

                pub struct Bar;
                impl Bar {
                    pub fn foo_method(&self) {}
                }
                """;
        String consumer =
                """
                use crate::service::{Bar, Foo};

                fn tuple_parameter((foo, _bar): (Foo, Bar)) {
                    foo.foo_method();
                }

                fn tuple_let(pair: (Foo, Bar)) {
                    let (foo, _bar): (Foo, Bar) = pair;
                    foo.foo_method();
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertTrue(
                    find(analyzer, serviceFile, "Bar", member(analyzer, serviceFile, "Bar", "foo_method"), consumerFile)
                            .hits()
                            .isEmpty());
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
    void barrelReexportFromPrivateModuleResolvesSelectedPublicItem() throws Exception {
        String service = "pub struct Foo;\n";
        String lib = """
                mod service;
                pub use service::Foo;
                """;
        String consumer =
                """
                use crate::Foo;

                fn run() {
                    let _ = Foo {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(lib, "src/lib.rs")
                .addFileContents(service, "src/service.rs")
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
    void chainedAndGroupedAliasedBarrelReexportsResolve() throws Exception {
        String service = """
                pub struct Foo;
                pub struct Bar;
                """;
        String first = """
                pub use crate::service::{Foo, Bar as PublicBar};
                """;
        String second =
                """
                pub use crate::first::Foo;
                pub use crate::first::PublicBar;
                """;
        String consumer =
                """
                use crate::second::{Foo, PublicBar};

                fn run() {
                    let _ = Foo {};
                    let _ = PublicBar {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(first, "src/first.rs")
                .addFileContents(second, "src/second.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1, find(analyzer, serviceFile, "Foo", consumerFile).hits().size());
            assertEquals(
                    1, find(analyzer, serviceFile, "Bar", consumerFile).hits().size());
        }
    }

    @Test
    void privateItemBehindBarrelReexportDoesNotResolve() throws Exception {
        String service = "struct Hidden;\n";
        String lib = """
                mod service;
                pub use service::Hidden;
                """;
        String consumer =
                """
                use crate::Hidden;

                fn run() {
                    let _ = Hidden {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(lib, "src/lib.rs")
                .addFileContents(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile libFile = projectFile(project.getAllFiles(), "src/lib.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertTrue(find(analyzer, libFile, "Hidden", consumerFile).hits().isEmpty());
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
                    1,
                    find(analyzer, libFile, "inline::Inline", consumerFile)
                            .hits()
                            .size());
            assertTrue(find(analyzer, libFile, "Inline", consumerFile).hits().isEmpty());
        }
    }

    @Test
    void privateInlineModuleIsNotExternallyResolvable() throws Exception {
        String lib =
                """
                mod service {
                    pub struct Foo;
                }
                """;
        String consumer =
                """
                use crate::service::Foo;

                fn run() {
                    let _ = Foo {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(lib, "src/lib.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile libFile = projectFile(project.getAllFiles(), "src/lib.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertTrue(
                    find(analyzer, libFile, "service::Foo", consumerFile).hits().isEmpty());
        }
    }

    @Test
    void privateInlineModuleCanBeExplicitlyReexported() throws Exception {
        String lib =
                """
                mod service {
                    pub struct Foo;
                }
                pub use service::Foo;
                """;
        String consumer =
                """
                use crate::Foo;

                fn run() {
                    let _ = Foo {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(lib, "src/lib.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile libFile = projectFile(project.getAllFiles(), "src/lib.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(1, find(analyzer, libFile, "Foo", consumerFile).hits().size());
        }
    }

    @Test
    void pubSelfDoesNotSeedExternalGraphExports() throws Exception {
        String service = "pub(self) struct Hidden;\n";
        String consumer =
                """
                use crate::service::Hidden;

                fn run() {
                    let _ = Hidden {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertTrue(
                    find(analyzer, serviceFile, "Hidden", consumerFile).hits().isEmpty());
        }
    }

    @Test
    void pubCrateRemainsLocallyGraphVisible() throws Exception {
        String service = "pub(crate) struct Local;\n";
        String consumer =
                """
                use crate::service::Local;

                fn run() {
                    let _ = Local {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(service, "src/service.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "src/service.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            assertEquals(
                    1, find(analyzer, serviceFile, "Local", consumerFile).hits().size());
        }
    }

    @Test
    void inlineModuleExportsOnlyPublicContents() throws Exception {
        String lib =
                """
                pub mod service {
                    pub struct Foo;
                    struct Hidden;
                }
                """;
        String consumer =
                """
                use crate::service::{Foo, Hidden};

                fn run() {
                    let _ = Foo {};
                    let _ = Hidden {};
                }
                """;

        try (var project = InlineTestProjectCreator.code(lib, "src/lib.rs")
                .addFileContents(consumer, "src/main.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile libFile = projectFile(project.getAllFiles(), "src/lib.rs");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "src/main.rs");

            var first = find(analyzer, libFile, "service::Foo", consumerFile);
            var second = find(analyzer, libFile, "service::Foo", consumerFile);

            assertEquals(1, first.hits().size());
            assertEquals(first.hits(), second.hits());
            assertTrue(find(analyzer, libFile, "Hidden", consumerFile).hits().isEmpty());
        }
    }

    @Test
    void privateSameFileFunctionCallResolves() throws Exception {
        String searchtools =
                """
                fn summarize_symbol_targets(targets: Vec<String>) -> SummaryResult {
                    SummaryResult {}
                }

                pub fn get_summaries(params: SummariesParams) -> SummaryResult {
                    let targets = strip_params(params.targets);
                    summarize_symbol_targets(targets)
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(searchtools, "src/searchtools.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile searchtoolsFile = projectFile(project.getAllFiles(), "src/searchtools.rs");
            CodeUnit target = target(analyzer, searchtoolsFile, "summarize_symbol_targets");

            var result = find(analyzer, searchtoolsFile, "summarize_symbol_targets", target, searchtoolsFile);

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void sameFilePublicStructTypeAndLiteralReferencesResolve() throws Exception {
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

            var result = find(analyzer, summaryFile, "RenderedSummary", target, summaryFile);

            assertEquals(3, result.hits().size());
        }
    }

    @Test
    void privateSameFileFunctionCallInsideClosureResolves() throws Exception {
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

            var result = find(analyzer, summaryFile, "summarize_input", target, summaryFile);

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void privateSameFileFunctionWithoutCallProducesNoHit() throws Exception {
        String searchtools =
                """
                fn summarize_symbol_targets(targets: Vec<String>) -> SummaryResult {
                    SummaryResult {}
                }

                pub fn get_summaries(params: SummariesParams) -> SummaryResult {
                    summarize_files(params.targets)
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(searchtools, "src/searchtools.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile searchtoolsFile = projectFile(project.getAllFiles(), "src/searchtools.rs");
            CodeUnit target = target(analyzer, searchtoolsFile, "summarize_symbol_targets");

            var result = find(analyzer, searchtoolsFile, "summarize_symbol_targets", target, searchtoolsFile);

            assertTrue(result.hits().isEmpty());
        }
    }

    @Test
    void localBindingShadowsPrivateSameFileFunction() throws Exception {
        String searchtools =
                """
                fn summarize_symbol_targets(targets: Vec<String>) -> SummaryResult {
                    SummaryResult {}
                }

                pub fn get_summaries(summarize_symbol_targets: fn(Vec<String>) -> SummaryResult) -> SummaryResult {
                    summarize_symbol_targets(Vec::new())
                }
                """;

        try (var project =
                InlineTestProjectCreator.code(searchtools, "src/searchtools.rs").build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile searchtoolsFile = projectFile(project.getAllFiles(), "src/searchtools.rs");
            CodeUnit target = target(analyzer, searchtoolsFile, "summarize_symbol_targets");

            var result = find(analyzer, searchtoolsFile, "summarize_symbol_targets", target, searchtoolsFile);

            assertTrue(result.hits().isEmpty());
        }
    }

    @Test
    void samePrivateFunctionNameInAnotherModuleDoesNotCrossMatch() throws Exception {
        String first =
                """
                fn summarize_symbol_targets(targets: Vec<String>) -> SummaryResult {
                    SummaryResult {}
                }
                """;
        String second =
                """
                fn summarize_symbol_targets(targets: Vec<String>) -> SummaryResult {
                    SummaryResult {}
                }

                pub fn get_summaries(params: SummariesParams) -> SummaryResult {
                    summarize_symbol_targets(params.targets)
                }
                """;

        try (var project = InlineTestProjectCreator.code(first, "src/a.rs")
                .addFileContents(second, "src/b.rs")
                .build()) {
            var analyzer = new RustAnalyzer(project);
            ProjectFile aFile = projectFile(project.getAllFiles(), "src/a.rs");
            ProjectFile bFile = projectFile(project.getAllFiles(), "src/b.rs");
            CodeUnit target = target(analyzer, aFile, "summarize_symbol_targets");

            var result = find(analyzer, aFile, "summarize_symbol_targets", target, bFile);

            assertTrue(result.hits().isEmpty());
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

    private static CodeUnit target(RustAnalyzer analyzer, ProjectFile file, String identifier) {
        return analyzer.getAllDeclarations().stream()
                .filter(cu -> cu.source().equals(file))
                .filter(cu -> cu.identifier().equals(identifier))
                .findFirst()
                .orElseThrow();
    }
}
