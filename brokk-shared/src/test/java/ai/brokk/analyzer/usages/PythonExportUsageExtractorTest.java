package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.testutil.InlineTestProjectCreator;
import org.junit.jupiter.api.Test;

class PythonExportUsageExtractorTest extends AbstractUsageReferenceGraphTest {

    @Test
    void exportIndexIncludesTopLevelDefinitionsAndStaticAllNames() throws Exception {
        String source =
                """
                __all__ = ["PublicAlias"]

                def top_function():
                    pass

                class TopClass:
                    pass

                def outer():
                    def nested_function():
                        pass
                """;

        try (var project = InlineTestProjectCreator.code(source, "module.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            ProjectFile file = projectFile(project.getAllFiles(), "module.py");

            ExportIndex index = analyzer.exportIndexOf(file);

            assertTrue(index.exportsByName().containsKey("top_function"));
            assertTrue(index.exportsByName().containsKey("TopClass"));
            assertTrue(index.exportsByName().containsKey("PublicAlias"));
            assertFalse(index.exportsByName().containsKey("nested_function"));
        }
    }

    @Test
    void importBinderCapturesAliasesRelativeImportsAndNamespaceImports() throws Exception {
        String source =
                """
                from pkg.service import Service as RenamedService
                from .local import helper
                import pkg.tools as tools
                import os
                from pkg.dynamic import *
                """;

        try (var project = InlineTestProjectCreator.code(source, "consumer.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            ProjectFile file = projectFile(project.getAllFiles(), "consumer.py");

            ImportBinder binder = analyzer.importBinderOf(file);

            assertTrue(binder.bindings().containsKey("RenamedService"));
            assertTrue(binder.bindings().containsKey("helper"));
            assertTrue(binder.bindings().containsKey("tools"));
            assertTrue(binder.bindings().containsKey("os"));
            assertFalse(binder.bindings().containsKey("*"));

            ImportBinder.ImportBinding renamed = binder.bindings().get("RenamedService");
            assertTrue("pkg.service".equals(renamed.moduleSpecifier()));
            assertTrue("Service".equals(renamed.importedName()));

            ImportBinder.ImportBinding relative = binder.bindings().get("helper");
            assertTrue(".local".equals(relative.moduleSpecifier()));
            assertTrue("helper".equals(relative.importedName()));

            ImportBinder.ImportBinding tools = binder.bindings().get("tools");
            assertTrue("pkg.tools".equals(tools.moduleSpecifier()));
            assertTrue(tools.importedName() == null);
        }
    }

    @Test
    void usageCandidatesIgnoreImportDeclarationsButCaptureBodyReferences() throws Exception {
        String source =
                """
                from pkg.service import Service

                def run():
                    return Service()
                """;

        try (var project = InlineTestProjectCreator.code(source, "consumer.py").build()) {
            var analyzer = new PythonAnalyzer(project);
            ProjectFile file = projectFile(project.getAllFiles(), "consumer.py");

            var candidates = analyzer.exportUsageCandidatesOf(file);

            assertTrue(candidates.stream()
                    .anyMatch(candidate -> candidate.identifier().equals("Service")));
            assertFalse(candidates.stream()
                    .anyMatch(candidate -> candidate.identifier().equals("pkg")
                            || candidate.identifier().equals("service")));
        }
    }
}
