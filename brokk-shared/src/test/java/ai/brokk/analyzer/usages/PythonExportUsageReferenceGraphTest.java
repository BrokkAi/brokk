package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
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
                    member,
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

            Set<CodeUnit> definitions = adapter.definitionsOf("_try_asyncore_import");

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

            Set<CodeUnit> definitions = adapter.definitionsOf("bar");

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
