package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    new JsTsExportUsageReferenceGraph.Limits(50, 50, 10),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Service",
                    null,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(result.hits().isEmpty());
            assertEquals(Set.of("external_pkg"), result.externalFrontierSpecifiers());
        }
    }

    private static void assertSinglePythonMemberHit(String service, String consumer) throws Exception {
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
                    Set.of(consumerFile));

            assertEquals(1, result.hits().size());
        }
    }

    private static void assertNoPythonMemberHit(String service, String consumer) throws Exception {
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

            var result = JsTsExportUsageReferenceGraph.findExportUsages(
                    serviceFile,
                    "Foo",
                    member,
                    adapter,
                    JsTsExportUsageReferenceGraph.Limits.defaults(),
                    Set.of(consumerFile));

            assertTrue(result.hits().isEmpty());
        }
    }
}
