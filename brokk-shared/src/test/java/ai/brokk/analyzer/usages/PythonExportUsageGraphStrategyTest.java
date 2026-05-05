package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import java.util.Map;
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

    @Test
    void pythonMemberSeedFallsBackFromIdentifierToOwnerExport() throws Exception {
        String service =
                """
                class Foo:
                    def bar(self):
                        pass
                """;

        try (var project = InlineTestProjectCreator.code(service, "service.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(serviceFile))
                    .filter(cu -> cu.identifier().equals("bar"))
                    .findFirst()
                    .orElseThrow();

            assertFalse(analyzer.exportIndexOf(serviceFile).exportsByName().containsKey(target.identifier()));
            assertTrue(new PythonExportUsageGraphStrategy(analyzer).canHandle(target));
        }
    }

    @Test
    void selectorUsesPythonGraphForMemberTarget() throws Exception {
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

        try (var project = InlineTestProjectCreator.code(service, "service.py")
                .addFileContents(consumer, "consumer.py")
                .build()) {
            var analyzer = new PythonAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(serviceFile))
                    .filter(cu -> cu.identifier().equals("bar"))
                    .findFirst()
                    .orElseThrow();

            UsageAnalyzer usageAnalyzer = UsageAnalyzerSelector.forTarget(target, analyzer, project);
            assertInstanceOf(PythonExportUsageGraphStrategy.class, usageAnalyzer);

            FuzzyResult result =
                    UsageAnalyzerSelector.findUsages(usageAnalyzer, analyzer, List.of(target), project.getAllFiles());
            assertTrue(result instanceof FuzzyResult.Success);
            var success = (FuzzyResult.Success) result;
            assertEquals(1, success.hitsByOverload().get(target).size());
        }
    }

    @Test
    void selectorUsesPythonGraphForPythonTargetWithMultiAnalyzer() throws Exception {
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
            var python = new PythonAnalyzer(project);
            var multi = new MultiAnalyzer(Map.of(Languages.PYTHON, python));
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            CodeUnit target = python.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(serviceFile))
                    .filter(cu -> cu.identifier().equals("Service"))
                    .findFirst()
                    .orElseThrow();

            UsageAnalyzer usageAnalyzer = UsageAnalyzerSelector.forTarget(target, multi, project);
            assertInstanceOf(PythonExportUsageGraphStrategy.class, usageAnalyzer);

            FuzzyResult result =
                    UsageAnalyzerSelector.findUsages(usageAnalyzer, multi, List.of(target), project.getAllFiles());
            assertTrue(result instanceof FuzzyResult.Success);
            var success = (FuzzyResult.Success) result;
            assertEquals(1, success.hitsByOverload().get(target).size());
            assertTrue(success.hitsByOverload().get(target).stream()
                    .anyMatch(hit -> endsWithPath(hit.file(), "consumer.py")));
        }
    }

    @Test
    void pythonGraphMissFallsBackToRegexForSameFileFunction() throws Exception {
        String source =
                """
                def helper():
                    pass

                def run():
                    return helper()
                """;

        try (var project = InlineTestProjectCreator.code(source, "service.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            ProjectFile serviceFile = projectFile(project.getAllFiles(), "service.py");
            CodeUnit target = analyzer.getAllDeclarations().stream()
                    .filter(cu -> cu.source().equals(serviceFile))
                    .filter(cu -> cu.identifier().equals("helper"))
                    .findFirst()
                    .orElseThrow();

            UsageAnalyzer usageAnalyzer = UsageAnalyzerSelector.forTarget(target, analyzer, project);
            assertInstanceOf(PythonExportUsageGraphStrategy.class, usageAnalyzer);

            FuzzyResult result =
                    UsageAnalyzerSelector.findUsages(usageAnalyzer, analyzer, List.of(target), project.getAllFiles());
            assertTrue(result instanceof FuzzyResult.Success);
            var success = (FuzzyResult.Success) result;
            assertFalse(success.hitsByOverload().get(target).isEmpty());
        }
    }
}
