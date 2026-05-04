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
