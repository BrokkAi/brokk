package ai.brokk.analyzer.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.agents.BuildAgent.BuildDetails;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.ITestProject;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.BuildTools;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class JavaScriptBuildTest {

    @Test
    void testJavaScriptFileTemplateInterpolation() throws Exception {
        String code = """
            import { test, expect } from '@playwright/test';
            
            test('basic test', async ({ page }) => {
              await page.goto('https://playwright.dev/');
            });
            """;

        // JS/TS files are analyzed as modules
        try (ITestProject project = InlineTestProjectCreator.code(code, "tests/logic.test.js").build()) {
            ProjectFile testFile = project.getAllFiles().stream()
                    .filter(f -> f.getFileName().equals("logic.test.js"))
                    .findFirst()
                    .orElseThrow();

            TestContextManager cm = new TestContextManager(
                    project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());

            // Template using {{#files}}
            BuildDetails detailsFiles = new BuildDetails(
                    "npm run build",
                    true,
                    "npm test",
                    true,
                    "npx playwright test {{#files}}{{value}} {{/files}}",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of());

            String commandFiles = BuildTools.getBuildLintSomeCommand(cm, detailsFiles, List.of(testFile));
            assertEquals("npx playwright test tests/logic.test.js", commandFiles.trim());

            // Template using {{#classes}} (which should resolve to the module name in JS)
            BuildDetails detailsClasses = new BuildDetails(
                    "npm run build",
                    true,
                    "npm test",
                    true,
                    "jest --findRelatedTests {{#classes}}{{value}} {{/classes}}",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of());

            String commandClasses = BuildTools.getBuildLintSomeCommand(cm, detailsClasses, List.of(testFile));
            assertEquals("jest --findRelatedTests logic.test.js", commandClasses.trim());
        }
    }

    @Test
    void testMultipleModulesTemplateInterpolation() throws Exception {
        try (var project = InlineTestProjectCreator.empty()
                .addFileContents("import { test } from '@playwright/test'; test('a', () => {})", "a.test.js")
                .addFileContents("import { test } from '@playwright/test'; test('b', () => {})", "b.test.js")
                .build()) {
            TestContextManager cm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());
            BuildDetails details = new BuildDetails(
                    "",
                    true,
                    "",
                    true,
                    "jest {{#classes}}{{value}} {{/classes}}",
                    true,
                    Set.of(),
                    java.util.Collections.emptyMap(),
                    null,
                    "",
                    List.of());

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.copyOf(project.getAllFiles()));
            assertEquals("jest a.test.js b.test.js", command.trim());
        }
    }
}
