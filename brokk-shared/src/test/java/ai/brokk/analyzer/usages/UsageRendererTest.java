package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageRendererTest {

    @TempDir
    Path root;

    @Test
    void renderNormalizesUsagePaths() {
        ProjectFile modelFile = new ProjectFile(root, Path.of("model\\album.go"));
        ProjectFile readerFile = new ProjectFile(root, Path.of("core\\reader.go"));
        CodeUnit target = CodeUnit.field(modelFile, "model", "Album.ImageFiles");
        CodeUnit enclosing = CodeUnit.fn(readerFile, "core", "Read");
        UsageHit hit = new UsageHit(readerFile, 7, 0, 10, enclosing, 1.0, "album.ImageFiles");
        FuzzyResult result = new FuzzyResult.Success(Map.of(target, Set.of(hit)));

        var output = UsageRenderer.render(
                new DisabledAnalyzer(), "model.Album.ImageFiles", List.of(target), result, UsageRenderer.Mode.SAMPLE);

        assertTrue(output.text().contains("core/reader.go:7"), output.text());
    }
}
