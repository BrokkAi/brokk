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
}
