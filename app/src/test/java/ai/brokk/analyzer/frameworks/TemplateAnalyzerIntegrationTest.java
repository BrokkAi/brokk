package ai.brokk.analyzer.frameworks;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ITemplateAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.FailingTemplateAnalyzer;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestTemplateAnalyzer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TemplateAnalyzerIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    public void getSources_aggregatesHostAndTemplateEvenIfOneFails() {
        var tsFile = new ProjectFile(tempDir, "app.component.ts");
        var hostClass = CodeUnit.cls(tsFile, "app", "AppComponent");

        IAnalyzer hostAnalyzer = new TestAnalyzer();
        ITemplateAnalyzer failingTemplate = new FailingTemplateAnalyzer("Failing", "Template analysis failed");
        ITemplateAnalyzer validTemplate =
                new TestTemplateAnalyzer("Valid", Map.of(hostClass, Set.of("<div>Template Content</div>")));

        MultiAnalyzer analyzer =
                new MultiAnalyzer(Map.of(Languages.TYPESCRIPT, hostAnalyzer), List.of(failingTemplate, validTemplate));

        Set<String> result = analyzer.getSources(hostClass, true);

        assertEquals(1, result.size(), "Should aggregate available sources");
        assertTrue(result.contains("<div>Template Content</div>"));
    }
}
