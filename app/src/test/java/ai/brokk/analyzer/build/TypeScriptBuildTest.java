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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class TypeScriptBuildTest {

    @Test
    void testTypeScriptFileTemplateInterpolation() throws Exception {
        String code = """
            import { test, expect } from '@playwright/test';
            
            test('ts test', async ({ page }) => {
              await page.goto('https://brokk.ai/');
            });
            """;

        // JS/TS files are analyzed as modules. 
        // We use .ts extension to ensure TypescriptAnalyzer is used via MultiAnalyzer
        try (ITestProject project = InlineTestProjectCreator.code(code, "tests/logic.test.ts").build()) {
            ProjectFile testFile = project.getAllFiles().stream()
                    .filter(f -> f.getFileName().equals("logic.test.ts"))
                    .findFirst()
                    .orElseThrow();

            TestContextManager cm = new TestContextManager(
                    project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());

            // Template using {{#files}}
            BuildDetails detailsFiles = new BuildDetails(
                    "npm run build",
                    "npm test",
                    "npx playwright test {{#files}}{{value}} {{/files}}",
                    Set.of());

            String commandFiles = BuildTools.getBuildLintSomeCommand(cm, detailsFiles, List.of(testFile));
            assertEquals("npx playwright test tests/logic.test.ts", commandFiles.trim());

            // Template using {{#classes}} 
            // In JsTsAnalyzer, modules are returned by testFilesToCodeUnits, 
            // so {{#classes}} should resolve to the module name (the filename).
            BuildDetails detailsClasses = new BuildDetails(
                    "npm run build",
                    "npm test",
                    "npx jest --findRelatedTests {{#classes}}{{value}} {{/classes}}",
                    Set.of());

            String commandClasses = BuildTools.getBuildLintSomeCommand(cm, detailsClasses, List.of(testFile));
            assertEquals("npx jest --findRelatedTests logic.test.ts", commandClasses.trim());
        }
    }

    @Test
    void testMultipleModulesTemplateInterpolation() throws Exception {
        try (var project = InlineTestProjectCreator.empty()
                .addFileContents("import { test } from '@playwright/test'; test('a', () => {})", "a.test.ts")
                .addFileContents("import { test } from '@playwright/test'; test('b', () => {})", "b.test.ts")
                .build()) {
            TestContextManager cm = new TestContextManager(project, new NoOpConsoleIO(), Set.of(), project.getAnalyzer());
            BuildDetails details = new BuildDetails("", "", "npx jest {{#classes}}{{value}} {{/classes}}", Set.of());

            String command = BuildTools.getBuildLintSomeCommand(cm, details, List.copyOf(project.getAllFiles()));
            assertEquals("npx jest a.test.ts b.test.ts", command.trim());
        }
    }
}
