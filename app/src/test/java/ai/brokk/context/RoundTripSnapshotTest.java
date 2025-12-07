package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.HistoryIo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RoundTripSnapshotTest {

    @TempDir
    Path tempDir;

    @Test
    void projectPathFragmentRoundTripPreservesSnapshotText() throws Exception {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());

        // Create a project file with v1
        var pf = new ProjectFile(tempDir, "sample.txt");
        pf.write("v1");

        // Build context and history (constructor snapshots computed values)
        var live = new Context(cm).addFragments(new ContextFragment.ProjectPathFragment(pf, cm));
        var history = new ContextHistory(live);

        // Serialize to zip
        Path zip = tempDir.resolve("history-project.zip");
        HistoryIo.writeZip(history, zip);

        // Mutate underlying file to v2 after serialization
        pf.write("v2");

        // Reload history
        var reloaded = HistoryIo.readZip(zip, cm);
        var ctx = reloaded.getHistory().get(reloaded.getHistory().size() - 1);

        // Find the project path fragment and assert its text is the snapshot ("v1")
        var fragment = ctx.fileFragments()
                .filter(f -> f instanceof ContextFragment.ProjectPathFragment)
                .map(f -> (ContextFragment.ProjectPathFragment) f)
                .findFirst()
                .orElseThrow();

        assertEquals("v1", fragment.text().join(), "Reloaded fragment should return snapshot text (v1)");
    }

    @Test
    void externalPathFragmentRoundTripPreservesSnapshotText() throws Exception {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());

        // Create an external file with v1
        Path externalPath = tempDir.resolve("external.txt");
        Files.writeString(externalPath, "v1", StandardCharsets.UTF_8);
        var ef = new ExternalFile(externalPath);

        // Build context and history (constructor snapshots computed values)
        var live = new Context(cm).addFragments(new ContextFragment.ExternalPathFragment(ef, cm));
        var history = new ContextHistory(live);

        // Serialize to zip
        Path zip = tempDir.resolve("history-external.zip");
        HistoryIo.writeZip(history, zip);

        // Mutate underlying external file to v2 after serialization
        Files.writeString(externalPath, "v2", StandardCharsets.UTF_8);

        // Reload history
        var reloaded = HistoryIo.readZip(zip, cm);
        var ctx = reloaded.getHistory().get(reloaded.getHistory().size() - 1);

        // Find the external path fragment and assert its text is the snapshot ("v1")
        var fragment = ctx.fileFragments()
                .filter(f -> f instanceof ContextFragment.ExternalPathFragment)
                .map(f -> (ContextFragment.ExternalPathFragment) f)
                .findFirst()
                .orElseThrow();

        assertEquals("v1", fragment.text().join(), "Reloaded fragment should return snapshot text (v1)");
    }
}
