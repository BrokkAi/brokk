package ai.brokk.analyzer.angular;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ITemplateAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.TemplateAnalysisResult;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
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
        String html = """
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
            assertTrue(result.discoveredUnits().stream().anyMatch(cu -> cu.shortName().equals("div")),
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
}
