package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import org.junit.jupiter.api.Test;

class PythonExportUsageGraphStrategyTest extends AbstractUsageReferenceGraphTest {

    @Test
    void selectorUsesPythonGraphForSeededExport() throws Exception {
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
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(serviceFile))
                    .filter(cu -> cu.identifier().equals("Service"))
                    .findFirst()
                    .orElseThrow();

            UsageAnalyzer usageAnalyzer = UsageAnalyzerSelector.forTarget(target, analyzer, project);
            assertInstanceOf(PythonExportUsageGraphStrategy.class, usageAnalyzer);

            FuzzyResult result =
                    UsageAnalyzerSelector.findUsages(usageAnalyzer, analyzer, List.of(target), project.getAllFiles());
            assertTrue(result instanceof FuzzyResult.Success);
            var success = (FuzzyResult.Success) result;
            assertEquals(1, success.hitsByOverload().get(target).size());
            assertTrue(success.hitsByOverload().get(target).stream()
                    .anyMatch(hit -> endsWithPath(hit.file(), "consumer.py")));
        }
    }

    @Test
    void selectorFallsBackForUnseededPythonTarget() throws Exception {
        String source = """
                def exported():
                    pass
                """;

        try (var project = InlineTestProjectCreator.code(source, "service.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            CodeUnit target = CodeUnit.fn(serviceFile, "", "missing");

            UsageAnalyzer usageAnalyzer = UsageAnalyzerSelector.forTarget(target, analyzer, project);

            assertInstanceOf(RegexUsageAnalyzer.class, usageAnalyzer);
        }
    }
}
