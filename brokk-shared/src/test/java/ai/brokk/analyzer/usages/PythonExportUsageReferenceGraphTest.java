package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class PythonExportUsageReferenceGraphTest extends AbstractUsageReferenceGraphTest {

    @Test
    void absoluteImportResolvesExportUsage() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String consumer =
                """
                from service import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
            assertTrue(endsWithPath(result.hits().iterator().next().file(), "consumer.py"));
        }
    }

    @Test
    void relativeImportResolvesExportUsage() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String consumer =
                """
                from .service import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(consumer, "pkg/consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "pkg/consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void packageBarrelResolvesThroughInitPy() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String init =
                """
                from .service import Service

                __all__ = ["Service"]
                """;
        String consumer =
                """
                from pkg import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(init, "pkg/__init__.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void nestedPackageBarrelResolvesThroughInitPyChain() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String internalInit =
                """
                from .service import Service

                __all__ = ["Service"]
                """;
        String pkgInit =
                """
                from .internal import Service

                __all__ = ["Service"]
                """;
        String consumer =
                """
                from pkg import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/internal/service.py")
                .addFileContents(internalInit, "pkg/internal/__init__.py")
                .addFileContents(pkgInit, "pkg/__init__.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/internal/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void importCycleTerminatesAndReportsOnlyProvenHits() throws Exception {
        String service =
                """
                from cycle_b import Other

                class Service:
                    pass
                """;
        String cycleB =
                """
                from service import Service

                class Other:
                    pass
                """;
        String consumer =
                """
                from cycle_b import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(cycleB, "cycle_b.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    new ExportUsageReferenceGraphEngine.Limits(50, 50, 10),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void localShadowingOfImportedNameDoesNotCountAsUsage() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String consumer =
                """
                from service import Service

                class Service:
                    pass

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(result.hits().isEmpty());
        }
    }

    @Test
    void importedClassStaticAccessResolvesMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    @staticmethod
                    def bar():
                        pass
                """;
        String consumer =
                """
                from service import Foo

                def run():
                    return Foo.bar()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(serviceFile))
                    .filter(cu -> cu.identifier().equals("bar"))
                    .findFirst()
                    .orElseThrow();

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Foo",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void typedLocalReceiverResolvesMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                def run():
                    x: Foo
                    x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer);
    }

    @Test
    void typedParameterReceiverResolvesMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                def run(x: Foo):
                    x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer);
    }

    @Test
    void typedInstanceAttributeReceiverResolvesMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                class Holder:
                    def __init__(self):
                        self.x: Foo

                    def run(self):
                        self.x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer);
    }

    @Test
    void constructedLocalReceiverResolvesMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                def run():
                    x = Foo()
                    x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer);
    }

    @Test
    void simpleAliasReceiverResolvesMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                def run():
                    x = Foo()
                    y = x
                    y.bar()
                """;

        assertSinglePythonMemberHit(service, consumer);
    }

    @Test
    void namespaceQualifiedAnnotationResolvesMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String init = """
                from .service import Foo
                """;
        String consumer =
                """
                import pkg as p

                def run():
                    x: p.Foo
                    x.bar()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(init, "pkg/__init__.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(serviceFile))
                    .filter(cu -> cu.identifier().equals("bar"))
                    .findFirst()
                    .orElseThrow();

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void optionalTypeArgumentResolvesReceiverMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from typing import Optional
                from service import Foo

                def run(x: Optional[Foo]):
                    x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer);
    }

    @Test
    void qualifiedOptionalTypeArgumentResolvesReceiverMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String init = """
                from .service import Foo
                """;
        String consumer =
                """
                import typing
                import pkg as p

                def run(x: typing.Optional[p.Foo]):
                    x.bar()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(init, "pkg/__init__.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = memberDeclaration(analyzer, serviceFile, "bar");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void listTypeArgumentDoesNotSeedContainerReceiver() throws Exception {
        assertContainerTypeArgumentCountsAsStaticUsageButDoesNotSeedReceiver("def run(xs: list[Foo]):\n    xs.bar()\n");
    }

    @Test
    void typingListTypeArgumentDoesNotSeedContainerReceiver() throws Exception {
        assertContainerTypeArgumentCountsAsStaticUsageButDoesNotSeedReceiver(
                """
                from typing import List

                def run(xs: List[Foo]):
                    xs.bar()
                """);
    }

    @Test
    void dictValueTypeArgumentDoesNotSeedContainerReceiver() throws Exception {
        assertContainerTypeArgumentCountsAsStaticUsageButDoesNotSeedReceiver(
                "def run(m: dict[str, Foo]):\n    m.bar()\n");
    }

    @Test
    void qualifiedListTypeArgumentDoesNotSeedContainerReceiver() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String init = """
                from .service import Foo
                """;
        String consumer =
                """
                from typing import List
                import pkg as p

                def run(xs: List[p.Foo]):
                    xs.bar()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(init, "pkg/__init__.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = memberDeclaration(analyzer, serviceFile, "bar");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Foo",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());

            var memberResult = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(memberResult.hits().isEmpty());
        }
    }

    @Disabled(
            "Element/value flow through generic containers is intentionally out of scope for the current Python usage graph")
    @Test
    void listElementSubscriptTypeArgumentResolvesFutureMemberUsage() throws Exception {
        assertSinglePythonMemberHit(
                """
                class Foo:
                    def bar(self):
                        pass
                """,
                """
                def run(xs: list[Foo]):
                    xs[0].bar()
                """);
    }

    @Disabled(
            "Element/value flow through generic containers is intentionally out of scope for the current Python usage graph")
    @Test
    void dictValueSubscriptTypeArgumentResolvesFutureMemberUsage() throws Exception {
        assertSinglePythonMemberHit(
                """
                class Foo:
                    def bar(self):
                        pass
                """,
                """
                def run(m: dict[str, Foo]):
                    m["key"].bar()
                """);
    }

    @Disabled(
            "Element/value flow through generic containers is intentionally out of scope for the current Python usage graph")
    @Test
    void iterableCallFlowTypeArgumentResolvesFutureMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String init = """
                from .service import Foo
                """;
        String consumer =
                """
                from typing import Iterable
                import pkg as p

                def run(xs: Iterable[p.Foo]):
                    next(iter(xs)).bar()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(init, "pkg/__init__.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = memberDeclaration(analyzer, serviceFile, "bar");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void unseededReceiverDoesNotCountAsMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer = """
                def run(x):
                    x.bar()
                """;

        assertNoPythonMemberHit(service, consumer);
    }

    @Test
    void unknownConstructorDoesNotCountAsMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                def run():
                    x = Unknown()
                    x.bar()
                """;

        assertNoPythonMemberHit(service, consumer);
    }

    @Test
    void localClassNameShadowBlocksImportedConstructorReceiver() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                def run():
                    Foo = object
                    x = Foo()
                    x.bar()
                """;

        assertNoPythonMemberHit(service, consumer);
    }

    @Test
    void unrelatedClassWithSameMemberDoesNotMatchReceiverTarget() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String other =
                """
                class Other:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from other import Other

                def run():
                    x = Other()
                    x.bar()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(other, "other.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(serviceFile))
                    .filter(cu -> cu.identifier().equals("bar"))
                    .findFirst()
                    .orElseThrow();

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(result.hits().isEmpty());
        }
    }

    @Test
    void ambiguousAnnotationBeyondCapDoesNotCountAsMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                class Bar:
                    def bar(self):
                        pass
                class Baz:
                    def bar(self):
                        pass
                class Qux:
                    def bar(self):
                        pass
                class Quux:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo, Bar, Baz, Qux, Quux

                def run():
                    x: Foo | Bar | Baz | Qux | Quux
                    x.bar()
                """;

        assertNoPythonMemberHit(service, consumer);
    }

    @Test
    void objectLiteralPropertyDoesNotCountAsMemberUsage() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                def run():
                    x = {"bar": 1}
                    x["bar"]
                """;

        assertNoPythonMemberHit(service, consumer);
    }

    @Test
    void functionLocalShadowDoesNotCountAsImportedUsage() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String consumer =
                """
                from service import Service

                def run():
                    Service = object
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(result.hits().isEmpty());
        }
    }

    @Test
    void receiverTypeFactsDoNotLeakAcrossFunctions() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                def typed(x: Foo):
                    pass

                def run(x):
                    x.bar()
                """;

        assertNoPythonMemberHit(service, consumer);
    }

    @Test
    void shadowingInOneFunctionDoesNotBlockSiblingReceiverInference() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                def shadow():
                    Foo = object

                def run(x: Foo):
                    x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer);
    }

    @Test
    void dottedNamespaceImportResolvesExportUsage() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String consumer =
                """
                import pkg.service

                def run():
                    return pkg.service.Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void dottedNamespaceAliasResolvesExportUsage() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String consumer =
                """
                import pkg.service as svc

                def run():
                    return svc.Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void fromPackageImportedSubmoduleQualifierResolvesExportUsage() throws Exception {
        String timestamps =
                """
                class MonotonicTimestampGenerator:
                    pass
                """;
        String init = "";
        String consumer =
                """
                from cassandra import timestamps

                def run():
                    return timestamps.MonotonicTimestampGenerator()
                """;

        try (var project = InlineTestProjectCreator.code(timestamps, "cassandra/timestamps.py")
                .addFileContents(init, "cassandra/__init__.py")
                .addFileContents(consumer, "tests/unit/test_timestamps.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile timestampsFile = projectFile(project.getAllFiles(), "cassandra/timestamps.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "tests/unit/test_timestamps.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    timestampsFile,
                    "MonotonicTimestampGenerator",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
            assertTrue(endsWithPath(result.hits().iterator().next().file(), "tests/unit/test_timestamps.py"));
        }
    }

    @Test
    void relativeSamePackageImportedSubmoduleQualifierResolvesExportUsage() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String consumer =
                """
                from . import service

                def run():
                    return service.Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents("", "pkg/__init__.py")
                .addFileContents(consumer, "pkg/consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "pkg/consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void relativeParentImportedSubmoduleQualifierResolvesExportUsage() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String consumer =
                """
                from .. import service

                def run():
                    return service.Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents("", "pkg/__init__.py")
                .addFileContents("", "pkg/tests/__init__.py")
                .addFileContents(consumer, "pkg/tests/consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "pkg/tests/consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void staticWildcardBarrelResolvesThroughAll() throws Exception {
        String service =
                """
                __all__ = ["Service"]

                class Service:
                    pass
                """;
        String init = """
                from .service import *
                """;
        String consumer =
                """
                from pkg import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(init, "pkg/__init__.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void inheritedBaseMemberCountsForSubclassReceiver() throws Exception {
        String service =
                """
                class Base:
                    def bar(self):
                        pass

                class Child(Base):
                    pass
                """;
        String consumer =
                """
                from service import Child

                def run(x: Child):
                    x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer, "Base", "bar");
    }

    @Test
    void overridingSubclassMemberCountsForBaseMemberQuery() throws Exception {
        String service =
                """
                class Base:
                    def bar(self):
                        pass

                class Child(Base):
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Child

                def run(x: Child):
                    x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer, "Base", "bar");
    }

    @Test
    void multiLevelInheritedMemberCountsForGrandchildReceiver() throws Exception {
        String service =
                """
                class Base:
                    def bar(self):
                        pass

                class Child(Base):
                    pass

                class GrandChild(Child):
                    pass
                """;
        String consumer =
                """
                from service import GrandChild

                def run(x: GrandChild):
                    x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer, "Base", "bar");
    }

    @Test
    void crossFileInheritedMemberCountsForSubclassReceiver() throws Exception {
        String base =
                """
                class Base:
                    def bar(self):
                        pass
                """;
        String child =
                """
                from base import Base

                class Child(Base):
                    pass
                """;
        String consumer =
                """
                from child import Child

                def run(x: Child):
                    x.bar()
                """;

        try (var project = InlineTestProjectCreator.code(base, "base.py")
                .addFileContents(child, "child.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile baseFile = projectFile(project.getAllFiles(), "base.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = memberDeclaration(analyzer, baseFile, "bar");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    baseFile,
                    "Base",
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void multipleInheritanceMemberCountsWhenOneParentProvidesMember() throws Exception {
        String service =
                """
                class Left:
                    pass

                class Right:
                    def bar(self):
                        pass

                class Child(Left, Right):
                    pass
                """;
        String consumer =
                """
                from service import Child

                def run(x: Child):
                    x.bar()
                """;

        assertSinglePythonMemberHit(service, consumer, "Right", "bar");
    }

    @Test
    void subclassReceiverDoesNotCountForDifferentBaseMemberName() throws Exception {
        String service =
                """
                class Base:
                    def baz(self):
                        pass

                class Child(Base):
                    pass
                """;
        String consumer =
                """
                from service import Child

                def run(x: Child):
                    x.bar()
                """;

        assertNoPythonMemberHit(service, consumer, "Base", "baz");
    }

    @Test
    void unresolvedSuperclassDoesNotCreateMemberHierarchyHit() throws Exception {
        String service =
                """
                class Base:
                    def bar(self):
                        pass

                class Child(UnknownBase):
                    pass
                """;
        String consumer =
                """
                from service import Child

                def run(x: Child):
                    x.bar()
                """;

        assertNoPythonMemberHit(service, consumer, "Base", "bar");
    }

    @Test
    void sameNameFromSiblingModuleDoesNotMatchTarget() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String other = """
                class Service:
                    pass
                """;
        String consumer =
                """
                from other import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(other, "other.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(result.hits().isEmpty());
        }
    }

    @Test
    void externalImportRecordsFrontierWithoutHit() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String consumer =
                """
                from external_pkg import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(result.hits().isEmpty());
            assertEquals(Set.of("external_pkg"), result.externalFrontierSpecifiers());
        }
    }

    @Test
    void sameFileTupleReferenceResolvesTopLevelFunctionUsage() throws Exception {
        String service =
                """
                def _try_gevent_import():
                    pass

                def _try_asyncore_import():
                    pass

                conn_fns = (_try_gevent_import, _try_asyncore_import)
                """;

        try (var project =
                InlineTestProjectCreator.code(service, "cassandra/cluster.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "cassandra/cluster.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "_try_asyncore_import",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(serviceFile));

            assertEquals(1, result.hits().size());
            assertEquals(7, result.hits().iterator().next().range().startLine() + 1);
        }
    }

    @Test
    void sameFileStaticClassMethodResolvesUsage() throws Exception {
        String service =
                """
                class CloudConfig:
                    @classmethod
                    def from_dict(cls, data):
                        return cls()

                def parse_cloud_config(data):
                    config = CloudConfig.from_dict(data)
                    return config
                """;

        try (var project = InlineTestProjectCreator.code(service, "cassandra/datastax/cloud/__init__.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "cassandra/datastax/cloud/__init__.py");
            var member = memberDeclaration(analyzer, serviceFile, "from_dict");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "CloudConfig",
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(serviceFile));

            assertEquals(1, result.hits().size());
            assertEquals(7, result.hits().iterator().next().range().startLine() + 1);
        }
    }

    @Test
    void sameFileStaticClassMethodCountsAsClassUsage() throws Exception {
        String service =
                """
                class CloudConfig:
                    @classmethod
                    def from_dict(cls, data):
                        return cls()

                def parse_cloud_config(data):
                    config = CloudConfig.from_dict(data)
                    return config
                """;

        try (var project = InlineTestProjectCreator.code(service, "cassandra/datastax/cloud/__init__.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "cassandra/datastax/cloud/__init__.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "CloudConfig",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(serviceFile));

            assertEquals(1, result.hits().size());
            assertEquals(7, result.hits().iterator().next().range().startLine() + 1);
        }
    }

    @Test
    void crossFilePartiallyQualifiedClassMethodCountsAsClassUsage() throws Exception {
        String cloud =
                """
                class CloudConfig:
                    @classmethod
                    def from_dict(cls, data):
                        return cls()
                """;
        String init = "";
        String consumer =
                """
                import pkg.cloud as cloud

                def parse_cloud_config(data):
                    config = cloud.CloudConfig.from_dict(data)
                    return config
                """;

        try (var project = InlineTestProjectCreator.code(cloud, "pkg/cloud.py")
                .addFileContents(init, "pkg/__init__.py")
                .addFileContents(consumer, "tests/test_cloud.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile cloudFile = projectFile(project.getAllFiles(), "pkg/cloud.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "tests/test_cloud.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    cloudFile,
                    "CloudConfig",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
            assertTrue(endsWithPath(result.hits().iterator().next().file(), "tests/test_cloud.py"));
        }
    }

    @Test
    void sameNamedUnrelatedClassMethodDoesNotCountAsClassUsage() throws Exception {
        String cloud =
                """
                class CloudConfig:
                    @classmethod
                    def from_dict(cls, data):
                        return cls()
                """;
        String unrelated =
                """
                class CloudConfig:
                    @classmethod
                    def from_dict(cls, data):
                        return cls()

                def parse_cloud_config(data):
                    config = CloudConfig.from_dict(data)
                    return config
                """;
        String consumer =
                """
                from pkg.cloud import CloudConfig

                def parse_cloud_config(data):
                    config = CloudConfig.from_dict(data)
                    return config
                """;

        try (var project = InlineTestProjectCreator.code(cloud, "pkg/cloud.py")
                .addFileContents(unrelated, "other/cloud.py")
                .addFileContents(consumer, "tests/test_cloud.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile cloudFile = projectFile(project.getAllFiles(), "pkg/cloud.py");
            ProjectFile unrelatedFile = projectFile(project.getAllFiles(), "other/cloud.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "tests/test_cloud.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    cloudFile,
                    "CloudConfig",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(unrelatedFile, consumerFile));

            assertEquals(1, result.hits().size());
            assertFalse(endsWithPath(result.hits().iterator().next().file(), "other/cloud.py"));
            assertTrue(endsWithPath(result.hits().iterator().next().file(), "tests/test_cloud.py"));
        }
    }

    @Test
    void cachedDefinitionsByIdentifierFindsBareTopLevelFunction() throws Exception {
        String service = """
                def _try_asyncore_import():
                    pass
                """;

        try (var project =
                InlineTestProjectCreator.code(service, "cassandra/cluster.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "cassandra/cluster.py");

            Set<CodeUnit> definitions = adapter.definitionsOf(serviceFile, "_try_asyncore_import");

            assertEquals(1, definitions.size());
            assertTrue(definitions.stream().allMatch(cu -> cu.source().equals(serviceFile)));
        }
    }

    @Test
    void cachedDefinitionsByIdentifierFindsMemberIdentifierFallback() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");

            Set<CodeUnit> definitions = adapter.definitionsOf(serviceFile, "bar");

            assertEquals(1, definitions.size());
            CodeUnit definition = definitions.iterator().next();
            assertEquals(serviceFile, definition.source());
            assertEquals("bar", definition.identifier());
        }
    }

    @Test
    void cachedExactMemberResolvesOnlyWithinSourceFile() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String other =
                """
                class Foo:
                    def bar(self):
                        pass
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(other, "other.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile otherFile = projectFile(project.getAllFiles(), "other.py");

            CodeUnit serviceMember = adapter.exactMember(serviceFile, "Foo", "bar", true);
            CodeUnit otherMember = adapter.exactMember(otherFile, "Foo", "bar", true);

            assertEquals(serviceFile, serviceMember.source());
            assertEquals(otherFile, otherMember.source());
        }
    }

    @Test
    void pythonUsageGraphCachesInvalidateChangedFilesOnUpdate() throws Exception {
        String service = """
                class Service:
                    pass
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");

            assertEquals(1, analyzer.definitionsByIdentifier("Service").size());

            Files.writeString(
                    serviceFile.absPath(),
                    """
                    class Renamed:
                        pass
                    """,
                    StandardCharsets.UTF_8);
            var updated = (PythonAnalyzer) analyzer.update(Set.of(serviceFile));

            assertTrue(updated.definitionsByIdentifier("Service").isEmpty());
            assertEquals(1, updated.definitionsByIdentifier("Renamed").size());
        }
    }

    @Test
    void exportResolutionCacheInvalidatesWhenReexportTargetChanges() throws Exception {
        String service = """
                class Service:
                    pass
                """;
        String init = """
                from .service import Service
                """;
        String consumer =
                """
                from pkg import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(service, "pkg/service.py")
                .addFileContents(init, "pkg/__init__.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "pkg/service.py");
            ProjectFile initFile = projectFile(project.getAllFiles(), "pkg/__init__.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var initial = ExportUsageReferenceGraphEngine.findExportUsages(
                    initFile,
                    "Service",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));
            assertEquals(1, initial.hits().size());

            Files.writeString(
                    serviceFile.absPath(),
                    """
                    class Renamed:
                        pass
                    """,
                    StandardCharsets.UTF_8);
            var updated = (PythonAnalyzer) analyzer.update(Set.of(serviceFile));

            var afterUpdate = ExportUsageReferenceGraphEngine.findExportUsages(
                    initFile,
                    "Service",
                    null,
                    new PythonExportUsageGraphAdapter(updated),
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(afterUpdate.hits().isEmpty());
        }
    }

    @Test
    void selfAttributeTypeFactsDoNotLeakAcrossClasses() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                class A:
                    def __init__(self):
                        self.x: Foo = Foo()

                class B:
                    def run(self):
                        self.x.bar()
                """;

        assertNoPythonMemberHit(service, consumer);
    }

    @Test
    void localParameterShadowsExportedClassAttributeCandidate() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer =
                """
                from service import Foo

                def run(Foo):
                    Foo.bar()
                """;

        assertNoPythonMemberHit(service, consumer);
    }

    @Test
    void defaultArgumentCallCountsAsUsageInsteadOfParameterShadow() throws Exception {
        String service = """
                class Widget:
                    pass
                """;
        String consumer =
                """
                from service import Widget

                def run(x=Widget()):
                    pass
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Widget",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void importBinderHonorsSourceOrderWhenImportOverridesClass() throws Exception {
        String service = """
                class Widget:
                    pass
                """;
        String consumer =
                """
                class Widget:
                    pass

                from service import Widget

                def run():
                    return Widget()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Widget",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    @Test
    void importBinderHonorsSourceOrderWhenClassOverridesImport() throws Exception {
        String service = """
                class Widget:
                    pass
                """;
        String consumer =
                """
                from service import Widget

                class Widget:
                    pass

                def run():
                    return Widget()
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Widget",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(result.hits().isEmpty());
        }
    }

    @Test
    void pythonGraphSuccessWithNoHitsDoesNotFallbackToRegex() throws Exception {
        String service = """
                class Widget:
                    pass
                """;
        String consumer =
                """
                # Widget appears only in a comment.
                note = "Widget appears only in a string"
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            CodeUnit target = analyzer.getTopLevelDeclarations(serviceFile).stream()
                    .filter(cu -> cu.identifier().equals("Widget"))
                    .findFirst()
                    .orElseThrow();

            UsageAnalyzer graph = new PythonExportUsageGraphStrategy(analyzer);
            var result =
                    UsageAnalyzerSelector.findUsages(graph, analyzer, java.util.List.of(target), Set.of(consumerFile));

            if (result instanceof FuzzyResult.Success success) {
                assertTrue(success.hits().isEmpty());
            } else {
                throw new AssertionError("Expected successful graph result");
            }
        }
    }

    @Test
    void deepAttributeExpressionDoesNotOverflow() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String chain = "root." + String.join(".", java.util.Collections.nCopies(300, "child")) + ".bar()";
        String consumer =
                """
                from service import Foo

                def run(root):
                """
                        + "    " + chain + "\n";

        assertNoPythonMemberHit(service, consumer);
    }

    private static void assertSinglePythonMemberHit(String service, String consumer) throws Exception {
        assertSinglePythonMemberHit(service, consumer, "Foo", "bar");
    }

    private static void assertSinglePythonMemberHit(
            String service, String consumer, String exportName, String memberName) throws Exception {
        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = memberDeclaration(analyzer, serviceFile, memberName);

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    exportName,
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    private static void assertNoPythonMemberHit(String service, String consumer) throws Exception {
        assertNoPythonMemberHit(service, consumer, "Foo", "bar");
    }

    private static void assertContainerTypeArgumentCountsAsStaticUsageButDoesNotSeedReceiver(String consumerBody)
            throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;
        String consumer = """
                from service import Foo

                """ + consumerBody;

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = memberDeclaration(analyzer, serviceFile, "bar");

            var typeUsage = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Foo",
                    null,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));
            assertEquals(1, typeUsage.hits().size());

            var memberUsage = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));
            assertTrue(memberUsage.hits().isEmpty());
        }
    }

    private static void assertNoPythonMemberHit(String service, String consumer, String exportName, String memberName)
            throws Exception {
        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            var adapter = new PythonExportUsageGraphAdapter(analyzer);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            ProjectFile consumerFile = projectFile(project.getAllFiles(), "consumer.py");
            var member = memberDeclaration(analyzer, serviceFile, memberName);

            var result = ExportUsageReferenceGraphEngine.findExportUsages(
                    serviceFile,
                    exportName,
                    member,
                    adapter,
                    ExportUsageReferenceGraphEngine.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(result.hits().isEmpty());
        }
    }

    private static CodeUnit memberDeclaration(PythonAnalyzer analyzer, ProjectFile file, String memberName) {
        return analyzer.getAllDeclarations().stream()
                .filter(cu -> cu.source().equals(file))
                .filter(cu -> cu.identifier().equals(memberName))
                .findFirst()
                .orElseThrow();
    }
}
