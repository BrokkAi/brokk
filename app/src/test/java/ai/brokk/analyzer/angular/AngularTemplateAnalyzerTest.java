package ai.brokk.analyzer.angular;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeContains;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ITemplateAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TemplateAnalysisResult;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AngularTemplateAnalyzerTest {

    @Test
    void testIsApplicable_WithAngularJson() {
        try (var project = InlineTestProjectCreator.empty()
                .addFileContents("{}", "angular.json")
                .build()) {

            AngularTemplateAnalyzer analyzer = new AngularTemplateAnalyzer();
            assertTrue(analyzer.isApplicable(project), "Should be applicable when angular.json is present");

            List<ITemplateAnalyzer> discovered =
                    Languages.discoverTemplateAnalyzers(project, Set.of(Languages.TYPESCRIPT));
            assertTrue(
                    discovered.stream().anyMatch(a -> a instanceof AngularTemplateAnalyzer),
                    "Should be discovered via Languages.discoverTemplateAnalyzers");
        }
    }

    @Test
    void testIsApplicable_WithAngularPackageJson() {
        String packageJson =
                """
                {
                  "dependencies": {
                    "@angular/core": "^17.0.0"
                  }
                }
                """;
        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(packageJson, "package.json")
                .build()) {

            AngularTemplateAnalyzer analyzer = new AngularTemplateAnalyzer();
            assertTrue(analyzer.isApplicable(project), "Should be applicable when package.json contains @angular/core");
        }
    }

    @Test
    void testIsApplicable_WithComponentHtml() {
        try (var project = InlineTestProjectCreator.empty()
                .addFileContents("<div></div>", "src/app/app.component.html")
                .build()) {

            AngularTemplateAnalyzer analyzer = new AngularTemplateAnalyzer();
            assertTrue(analyzer.isApplicable(project), "Should be applicable when .component.html files exist");
        }
    }

    @Test
    void testAnalyzeTemplate_ParsesHtml() {
        String html =
                """
                <div class="container">
                  <app-header></app-header>
                  <router-outlet></router-outlet>
                </div>
                """;
        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(html, "src/app/app.component.html")
                .build()) {

            AngularTemplateAnalyzer analyzer = new AngularTemplateAnalyzer();
            ProjectFile templateFile = project.getAllFiles().iterator().next();

            // Mock host class
            CodeUnit hostClass = CodeUnit.cls(templateFile, "app", "AppComponent");
            IAnalyzer mockHost = Languages.TYPESCRIPT.createAnalyzer(project, IAnalyzer.ProgressListener.NOOP);

            TemplateAnalysisResult result = analyzer.analyzeTemplate(mockHost, templateFile, hostClass);

            assertFalse(result.discoveredUnits().isEmpty(), "Should discover HTML units");
            assertTrue(
                    result.discoveredUnits().stream()
                            .anyMatch(cu -> cu.shortName().equals("div")),
                    "Should discover top-level 'div' element");
        }
    }

    @Test
    void testIsNotApplicable_PlainProject() {
        try (var project = InlineTestProjectCreator.empty()
                .addFileContents("public class Main {}", "Main.java")
                .addFileContents("console.log('hello');", "index.ts")
                .build()) {

            AngularTemplateAnalyzer analyzer = new AngularTemplateAnalyzer();
            assertFalse(analyzer.isApplicable(project), "Should NOT be applicable to a plain Java/TS project");

            List<ITemplateAnalyzer> discovered =
                    Languages.discoverTemplateAnalyzers(project, Set.of(Languages.JAVA, Languages.TYPESCRIPT));
            assertTrue(
                    discovered.stream().noneMatch(a -> a instanceof AngularTemplateAnalyzer),
                    "Should NOT be discovered in a plain project");
        }
    }

    @Test
    void testIntegration_GetSources_ReturnsMergedTsAndHtml() {
        String tsCode =
                """
                import { Component } from '@angular/core';

                @Component({
                  selector: 'app-root',
                  templateUrl: './app.component.html'
                })
                export class AppComponent {}
                """;
        String htmlCode = "<h1>Hello Angular</h1>";

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(tsCode, "src/app/app.component.ts")
                .addFileContents(htmlCode, "src/app/app.component.html")
                .addFileContents("{}", "angular.json")
                .build()) {

            // Explicitly create a MultiAnalyzer with Angular support for this integration test
            IAnalyzer tsAnalyzer = Languages.TYPESCRIPT.createAnalyzer(project, IAnalyzer.ProgressListener.NOOP);
            AngularTemplateAnalyzer angularTemplateAnalyzer = new AngularTemplateAnalyzer();
            MultiAnalyzer multi =
                    new MultiAnalyzer(Map.of(Languages.TYPESCRIPT, tsAnalyzer), List.of(angularTemplateAnalyzer));

            // Ensure the AngularTemplateAnalyzer has received the metadata signals
            // MultiAnalyzer.snapshotState() triggers signal emission in the current implementation.
            // This is required to link the TypeScript component to its template.
            multi.snapshotState();

            // Find the TypeScript CodeUnit via the MultiAnalyzer to ensure instance consistency.
            // Use robust lookup as TypescriptAnalyzer might prefix with directory-based package name.
            CodeUnit appComponent = multi.getAllDeclarations().stream()
                    .filter(cu -> cu.isClass() && cu.fqName().endsWith("AppComponent"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "AppComponent not found. Available declarations: " + multi.getAllDeclarations()));

            // Debug check for attributes
            var state = multi.snapshotState();
            var props = state.codeUnitState().get(appComponent);
            assertTrue(
                    props != null && props.attributes().containsKey("angular.component"),
                    "AppComponent should have angular.component attribute. Attributes: "
                            + (props != null ? props.attributes() : "null"));

            // Retrieve sources. MultiAnalyzer should merge TS and HTML.
            Set<String> sources = multi.getSources(appComponent, true);

            assertFalse(sources.isEmpty(), "Should return sources for AppComponent");
            String mergedSource = sources.iterator().next();

            // Assert TypeScript and Angular content are present with correct headers.
            // Note: Languages.TYPESCRIPT.name() is "Typescript" and AngularTemplateAnalyzer.name() is "Angular".
            assertCodeContains(mergedSource, "/* Typescript source */", "Should contain TS header");
            assertCodeContains(mergedSource, "export class AppComponent", "Should contain TS class definition");
            assertCodeContains(mergedSource, "/* Angular source */", "Should contain Angular header");
            assertCodeContains(mergedSource, "<h1>Hello Angular</h1>", "Should contain HTML template content");
        }
    }

    @Test
    void testCodeFragmentIncludesTemplateFiles_Integration() {
        String tsCode =
                """
                import { Component } from '@angular/core';

                @Component({
                  selector: 'app-test',
                  templateUrl: './test.component.html'
                })
                export class TestComponent {}
                """;
        String htmlCode = "<p>External Template Content</p>";

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(tsCode, "src/app/test.component.ts")
                .addFileContents(htmlCode, "src/app/test.component.html")
                .addFileContents("{}", "angular.json")
                .build()) {

            IAnalyzer tsAnalyzer = Languages.TYPESCRIPT.createAnalyzer(project, IAnalyzer.ProgressListener.NOOP);
            AngularTemplateAnalyzer angularTemplateAnalyzer = new AngularTemplateAnalyzer();
            MultiAnalyzer multi =
                    new MultiAnalyzer(Map.of(Languages.TYPESCRIPT, tsAnalyzer), List.of(angularTemplateAnalyzer));

            multi.snapshotState();

            CodeUnit testComponent = multi.getAllDeclarations().stream()
                    .filter(cu -> cu.isClass() && cu.fqName().endsWith("TestComponent"))
                    .findFirst()
                    .orElseThrow();

            TestContextManager cm = new TestContextManager(project, new TestConsoleIO(), project.getAllFiles(), multi);

            var fragment = new ContextFragments.CodeFragment(cm, testComponent);
            Set<ContextFragment> supporting = fragment.supportingFragments();

            boolean hasTemplate = supporting.stream()
                    .filter(f -> f instanceof ContextFragments.ProjectPathFragment)
                    .map(f -> (ContextFragments.ProjectPathFragment) f)
                    .anyMatch(pf -> pf.file().getFileName().equals("test.component.html"));

            assertTrue(hasTemplate, "Supporting fragments should include the external template file");
        }
    }

    @Test
    void testGetSources_WithExternalTemplate_Integration() {
        String tsCode =
                """
                import { Component } from '@angular/core';

                @Component({
                  selector: 'app-test',
                  templateUrl: './test.component.html'
                })
                export class TestComponent {}
                """;
        String htmlCode = "<p>External Template Content</p>";

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(tsCode, "src/app/test.component.ts")
                .addFileContents(htmlCode, "src/app/test.component.html")
                .addFileContents("{}", "angular.json")
                .build()) {

            // Set up the analyzers
            IAnalyzer tsAnalyzer = Languages.TYPESCRIPT.createAnalyzer(project, IAnalyzer.ProgressListener.NOOP);
            AngularTemplateAnalyzer angularTemplateAnalyzer = new AngularTemplateAnalyzer();
            MultiAnalyzer multi =
                    new MultiAnalyzer(Map.of(Languages.TYPESCRIPT, tsAnalyzer), List.of(angularTemplateAnalyzer));

            // Analysis pass to link metadata
            multi.snapshotState();

            // Find the component
            CodeUnit testComponent = multi.getAllDeclarations().stream()
                    .filter(cu -> cu.isClass() && cu.fqName().endsWith("TestComponent"))
                    .findFirst()
                    .orElseThrow();

            // Act: Retrieve merged sources
            Set<String> sources = multi.getSources(testComponent, true);

            // Assert: Verify merged output
            assertFalse(sources.isEmpty(), "Should return sources for TestComponent");
            String combined = sources.iterator().next();

            assertCodeContains(combined, "/* Typescript source */", "Should have TS header");
            assertCodeContains(combined, "export class TestComponent", "Should have TS body");
            assertCodeContains(combined, "/* Angular source */", "Should have Angular header");
            assertCodeContains(combined, "<p>External Template Content</p>", "Should have external HTML content");
        }
    }
}
