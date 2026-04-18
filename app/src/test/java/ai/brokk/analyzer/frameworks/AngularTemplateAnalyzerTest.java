package ai.brokk.analyzer.frameworks;

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
import ai.brokk.context.ContextFragments.ProjectPathFragment;
import ai.brokk.context.ContextFragments.SummaryFragment;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void testProjectPathFragmentForTemplateIncludesHostTs_Integration() {
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

            ProjectFile htmlFile = project.getAllFiles().stream()
                    .filter(pf -> pf.getFileName().equals("test.component.html"))
                    .findFirst()
                    .orElseThrow();

            TestContextManager cm = new TestContextManager(project, new TestConsoleIO(), project.getAllFiles(), multi);

            var fragment = new ProjectPathFragment(htmlFile, cm);
            Set<ContextFragment> supporting = fragment.supportingFragments();

            boolean hasHostTs = supporting.stream()
                    .filter(f -> f instanceof ProjectPathFragment)
                    .map(f -> ((ProjectPathFragment) f).file().getFileName())
                    .anyMatch(fn -> fn.equals("test.component.ts"));

            assertTrue(hasHostTs, "Supporting fragments should include the host TypeScript component file");
        }
    }

    @Test
    void testSummaryFragmentForTemplateIncludesHostClassSkeleton_Integration() {
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

            TestContextManager cm = new TestContextManager(project, new TestConsoleIO(), project.getAllFiles(), multi);

            var fragment =
                    new SummaryFragment(cm, "src/app/test.component.html", ContextFragment.SummaryType.FILE_SKELETONS);
            Set<ContextFragment> supporting = fragment.supportingFragments();

            boolean hasHostSkeleton = supporting.stream()
                    .filter(f -> f instanceof SummaryFragment)
                    .map(f -> (SummaryFragment) f)
                    .anyMatch(sf -> sf.getSummaryType() == ContextFragment.SummaryType.CODEUNIT_SKELETON
                            && sf.getTargetIdentifier().endsWith("TestComponent"));

            assertTrue(
                    hasHostSkeleton,
                    "Supporting fragments should include a class skeleton summary for the host component");
        }
    }

    @Test
    void testSummarizeTemplate() {
        System.setProperty("brokk.debug.ast", "true");
        String html =
                """
                <div [class.active]="isActive">
                  <app-header (logout)="onLogout()"></app-header>
                  @if (user) {
                    <span>{{ user.name | uppercase }}</span>
                  }
                  <section *ngIf="showSection"></section>
                </div>
                """;
        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(html, "src/app/test.component.html")
                .build()) {

            AngularTemplateAnalyzer analyzer = new AngularTemplateAnalyzer();
            ProjectFile templateFile = project.getAllFiles().iterator().next();

            Optional<String> summaryOpt = analyzer.summarizeTemplate(templateFile, project);

            assertTrue(summaryOpt.isPresent());
            String summary = summaryOpt.get();

            assertCodeContains(summary, "Components:", "Should list components");
            assertCodeContains(summary, "- app-header", "Should identify app-header");
            assertCodeContains(summary, "Control Flow:", "Should list control flow");
            assertCodeContains(summary, "- @if", "Should identify @if block");
            assertCodeContains(summary, "Directives:", "Should list structural directives");
            assertCodeContains(summary, "- *ngIf", "Should identify *ngIf");
            assertCodeContains(summary, "Pipes:", "Should list pipes");
            assertCodeContains(summary, "- uppercase", "Should identify pipe");
            assertCodeContains(summary, "Bindings:", "Should list bindings");
            assertCodeContains(summary, "- class.active", "Should identify property binding");
            assertCodeContains(summary, "Events:", "Should list events");
            assertCodeContains(summary, "- logout", "Should identify event binding");
        }
    }
}
