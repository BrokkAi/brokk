package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.imageio.ImageIO;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class UndoRedoRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void undoRestoresSnapshotContentFromCachedComputedValues() throws Exception {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());

        // 1) Create a file with initial contents and add it to the context
        var pf = new ProjectFile(tempDir, "sample.txt");
        pf.write("v1");

        var live = new Context(cm).addFragments(List.of(new ContextFragments.ProjectPathFragment(pf, cm)));
        live.awaitContextsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        // 2) Build history with the initial snapshot
        var history = new ContextHistory(live);

        // 3) Push a second snapshot (e.g., adding a virtual fragment) to enable undo
        history.push(ctx -> ctx.addFragments(
                new ContextFragments.StringFragment(cm, "hello", "desc", SyntaxConstants.SYNTAX_STYLE_NONE)));

        // 4) Modify the file externally to a different value
        pf.write("v2");

        // 5) Undo should restore the workspace file to the cached snapshot content ("v1")
        var io = new NoOpConsoleIO();
        var project = cm.getProject();
        var result = history.undo(1, io, project);
        // Verify workspace content is restored to the snapshot value
        var restored = pf.read().orElse("");
        assertEquals("v1", restored, "Undo should restore file to snapshot content, not retain external changes");
    }

    @Test
    void undoRedoWithMixedFragmentsRestoresProjectFilesFromSnapshots() throws Exception {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());

        // Prepare project file
        var pf = new ProjectFile(tempDir, "sample2.txt");
        pf.write("v1");

        // Prepare external text file
        var externalPath = tempDir.resolve("external.txt");
        Files.writeString(externalPath, "e1");

        // Prepare external image file
        var imagePath = tempDir.resolve("image.png");
        BufferedImage img1 = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        img1.setRGB(0, 0, 0xFFFF0000); // red
        ImageIO.write(img1, "png", imagePath.toFile());

        // Build initial context with mixed fragments
        var live = new Context(cm)
                .addFragments(List.of(
                        new ContextFragments.ProjectPathFragment(pf, cm),
                        new ContextFragments.ExternalPathFragment(new ExternalFile(externalPath), cm),
                        new ContextFragments.ImageFileFragment(new ExternalFile(imagePath), cm)));
        live.awaitContextsAreComputed(Duration.of(10, ChronoUnit.SECONDS));

        // Initialize history (snapshot 1)
        var history = new ContextHistory(live);

        // Push second context with a virtual fragment (snapshot 2)
        history.push(ctx -> ctx.addFragments(
                new ContextFragments.StringFragment(cm, "hello", "desc", SyntaxConstants.SYNTAX_STYLE_NONE)));

        // Mutate on-disk contents after snapshot 2
        pf.write("v2");
        Files.writeString(externalPath, "e2");
        BufferedImage img2 = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        img2.setRGB(0, 0, 0xFF0000FF); // blue
        ImageIO.write(img2, "png", imagePath.toFile());

        var io = new NoOpConsoleIO();
        var project = cm.getProject();

        // Undo → workspace should be restored to snapshot content of snapshot 1 (v1)
        history.undo(1, io, project);
        var restoredAfterUndo = pf.read().orElse("");
        assertEquals("v1", restoredAfterUndo, "Undo should restore project file to snapshot content");

        // Redo → workspace should be restored to snapshot content of snapshot 2
        history.redo(io, project);
        var restoredAfterRedo = pf.read().orElse("");
        assertEquals("v1", restoredAfterRedo, "Redo should restore second snapshot's project file content");
    }

    private static void writeImage(ProjectFile pf, Color color) throws Exception {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, color.getRGB());
        ImageIO.write(img, "png", pf.absPath().toFile());
    }

    private static int readFirstPixelRgb(ProjectFile pf) throws Exception {
        BufferedImage img = ImageIO.read(pf.absPath().toFile());
        return img.getRGB(0, 0);
    }

    @Test
    void undoRedoRestoresSnapshotsForMixedFragments() throws Exception {
        var cm = new TestContextManager(tempDir, new NoOpConsoleIO());

        // Create project text file and image file (v1)
        var pfText = new ProjectFile(tempDir, "mixed.txt");
        pfText.write("v1");

        var pfImg = new ProjectFile(tempDir, "mixed.png");
        writeImage(pfImg, Color.RED); // v1 image

        // Create external file (v1)
        var externalPath = tempDir.resolve("external.txt");
        Files.writeString(externalPath, "extV1");
        var extFile = new ExternalFile(externalPath);

        // Create initial context with mixed fragments
        var live = new Context(cm)
                .addFragments(List.of(
                        new ContextFragments.ProjectPathFragment(pfText, cm),
                        new ContextFragments.ImageFileFragment(pfImg, cm),
                        new ContextFragments.ExternalPathFragment(extFile, cm)));
        live.awaitContextsAreComputed(Duration.ofSeconds(10));

        // Build history capturing v1 snapshot
        var history = new ContextHistory(live);

        // Mutate on-disk files to new contents (v2)
        pfText.write("v2");
        writeImage(pfImg, Color.GREEN); // v2 image
        Files.writeString(externalPath, "extV2");

        // Push a second context capturing v2 snapshots for project and image files
        history.push(ctx -> ctx.removeAll()
                .addFragments(List.of(
                        new ContextFragments.ProjectPathFragment(pfText, cm),
                        new ContextFragments.ImageFileFragment(pfImg, cm),
                        new ContextFragments.ExternalPathFragment(extFile, cm))));

        // Mutate again to a different on-disk state (v3)
        pfText.write("v3");
        writeImage(pfImg, Color.BLUE); // v3 image
        Files.writeString(externalPath, "extV3");

        var io = new NoOpConsoleIO();
        var project = cm.getProject();

        // Undo -> should restore to the first snapshot (v1)
        var undoResult = history.undo(1, io, project);
        var restoredTextAfterUndo = pfText.read().orElse("");
        assertEquals("v1", restoredTextAfterUndo, "Undo should restore project text file to v1");
        assertEquals(Color.RED.getRGB(), readFirstPixelRgb(pfImg), "Undo should restore image file to v1 (red)");

        // Redo -> should restore to the second snapshot (v2)
        var redoResult = history.redo(io, project);
        var restoredTextAfterRedo = pfText.read().orElse("");
        assertEquals("v2", restoredTextAfterRedo, "Redo should restore project text file to v2");
        assertEquals(Color.GREEN.getRGB(), readFirstPixelRgb(pfImg), "Redo should restore image file to v2 (green)");
    }
}
