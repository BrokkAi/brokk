package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import javax.imageio.ImageIO;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffServiceTest {

    @TempDir
    Path tempDir;

    private IContextManager contextManager;

    @BeforeEach
    void setup() {
        contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        ContextFragment.setMinimumId(1);
    }

    @Test
    void diffs_only_for_project_path_fragments() throws Exception {
        // Arrange old content
        var pf = new ProjectFile(tempDir, "src/File1.txt");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "line1\n");

        var oldFrag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        // Pre-compute and cache old content
        oldFrag.text().await(Duration.ofSeconds(2));

        var oldCtx = new Context(
                contextManager, List.of(oldFrag), List.of(), null, CompletableFuture.completedFuture("old"));

        // Mutate file for new context
        Files.writeString(pf.absPath(), "line1\nline2\n");

        var newFrag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var newCtx = new Context(
                contextManager, List.of(newFrag), List.of(), null, CompletableFuture.completedFuture("new"));

        // Act
        var diffs = DiffService.computeDiff(newCtx, oldCtx);

        // Assert
        assertEquals(1, diffs.size(), "Expected a single diff for the changed project file");
        var de = diffs.getFirst();
        assertEquals(ContextFragment.FragmentType.PROJECT_PATH, de.fragment().getType());
        assertEquals(1, de.linesAdded(), "Expected exactly one added line");
    }

    @Test
    void virtual_fragments_are_excluded_from_diff() {
        var sfOld = new ContextFragment.StringFragment(
                contextManager, "old text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);
        var oldCtx =
                new Context(contextManager, List.of(sfOld), List.of(), null, CompletableFuture.completedFuture("old"));

        var sfNew = new ContextFragment.StringFragment(
                contextManager, "new text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);
        var newCtx =
                new Context(contextManager, List.of(sfNew), List.of(), null, CompletableFuture.completedFuture("new"));

        var diffs = DiffService.computeDiff(newCtx, oldCtx);
        assertEquals(0, diffs.size(), "Virtual fragments (StringFragment) should be excluded from diffs");
    }

    @Test
    void external_path_fragments_are_excluded_from_diff() throws Exception {
        var extPath = tempDir.resolve("ext.txt");
        Files.writeString(extPath, "v1");
        var extFile = new ExternalFile(extPath);

        var oldFrag = new ContextFragment.ExternalPathFragment(extFile, contextManager);
        var oldCtx = new Context(
                contextManager, List.of(oldFrag), List.of(), null, CompletableFuture.completedFuture("old"));

        Files.writeString(extPath, "v2");
        var newFrag = new ContextFragment.ExternalPathFragment(extFile, contextManager);
        var newCtx = new Context(
                contextManager, List.of(newFrag), List.of(), null, CompletableFuture.completedFuture("new"));

        var diffs = DiffService.computeDiff(newCtx, oldCtx);
        assertEquals(
                0, diffs.size(), "External path diffs should be excluded (only editable project files are diffed)");
    }

    @Test
    void read_only_project_path_fragment_is_excluded() throws Exception {
        // Arrange old content
        var pf = new ProjectFile(tempDir, "src/ReadOnly.java");
        Files.createDirectories(pf.absPath().getParent());
        Files.writeString(pf.absPath(), "class A {}\n");

        var oldFrag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        // Seed cache for old content
        oldFrag.text().await(Duration.ofSeconds(2));

        var oldCtx = new Context(
                contextManager, List.of(oldFrag), List.of(), null, CompletableFuture.completedFuture("old"));

        // Mutate file for new context
        Files.writeString(pf.absPath(), "class A {}\nclass B {}\n");

        var newFrag = new ContextFragment.ProjectPathFragment(pf, contextManager);
        var newCtx = new Context(
                contextManager, List.of(newFrag), List.of(), null, CompletableFuture.completedFuture("new"));

        // Mark the new fragment as read-only in the new context
        newCtx = newCtx.setReadonly(newFrag, true);

        var diffs = DiffService.computeDiff(newCtx, oldCtx);
        assertEquals(0, diffs.size(), "Read-only project path fragments should be excluded from diffs");
    }

    @Test
    void image_fragments_omitted_when_equal() throws Exception {
        var imgFile = new ProjectFile(tempDir, "src/img.png");
        Files.createDirectories(imgFile.absPath().getParent());
        writeImage(imgFile, Color.RED);

        var oldImgFrag = new ContextFragment.ImageFileFragment(imgFile, contextManager);
        var oldCtx = new Context(
                contextManager, List.of(oldImgFrag), List.of(), null, CompletableFuture.completedFuture("old"));

        // No change to image
        var newImgFrag = new ContextFragment.ImageFileFragment(imgFile, contextManager);
        var newCtx = new Context(
                contextManager, List.of(newImgFrag), List.of(), null, CompletableFuture.completedFuture("new"));

        var diffs = DiffService.computeDiff(newCtx, oldCtx);
        assertEquals(0, diffs.size(), "Equal images must not produce a diff entry");
    }

    @Test
    void image_fragments_produce_placeholder_when_bytes_differ() throws Exception {
        var imgFile = new ProjectFile(tempDir, "src/img2.png");
        Files.createDirectories(imgFile.absPath().getParent());
        writeImage(imgFile, Color.RED);

        var oldImgFrag = new ContextFragment.ImageFileFragment(imgFile, contextManager);
        var oldCtx = new Context(
                contextManager, List.of(oldImgFrag), List.of(), null, CompletableFuture.completedFuture("old"));

        // Change image bytes
        writeImage(imgFile, Color.GREEN);

        var newImgFrag = new ContextFragment.ImageFileFragment(imgFile, contextManager);
        var newCtx = new Context(
                contextManager, List.of(newImgFrag), List.of(), null, CompletableFuture.completedFuture("new"));

        var diffs = DiffService.computeDiff(newCtx, oldCtx);
        assertEquals(1, diffs.size(), "Byte-different images should produce a placeholder diff");
        var de = diffs.getFirst();
        assertEquals("[Image changed]", de.diff());
        assertEquals(1, de.linesAdded());
        assertEquals(1, de.linesDeleted());
    }

    @Test
    void text_diff_falls_back_when_new_text_not_computed() {
        class SlowFragment extends ContextFragment.AbstractComputedFragment implements ContextFragment.DynamicIdentity {
            private final ContextFragment.FragmentType type;

            SlowFragment(
                    String id,
                    IContextManager cm,
                    @Nullable ContextFragment.FragmentSnapshot snapshot,
                    @Nullable Callable<ContextFragment.FragmentSnapshot> task,
                    ContextFragment.FragmentType type) {
                super(id, cm, snapshot, task);
                this.type = type;
            }

            @Override
            public String id() {
                return super.id();
            }

            @Override
            public ContextFragment.FragmentType getType() {
                return type;
            }

            @Override
            public boolean isEligibleForAutoContext() {
                return true;
            }

            @Override
            public String repr() {
                return "SlowFragment";
            }

            @Override
            public ContextFragment refreshCopy() {
                return this;
            }
        }

        var oldSnap = new ContextFragment.FragmentSnapshot(
                "d", "d", "old-line", SyntaxConstants.SYNTAX_STYLE_NONE, Set.of(), Set.of(), (List<Byte>) null);
        var oldFrag = new SlowFragment("99", contextManager, oldSnap, null, ContextFragment.FragmentType.PROJECT_PATH);

        var latch = new CountDownLatch(1);
        var newFrag = new SlowFragment(
                "99",
                contextManager,
                null,
                () -> {
                    latch.await(); // never released in test; simulates long computation
                    return oldSnap;
                },
                ContextFragment.FragmentType.PROJECT_PATH);

        var oldCtx = new Context(
                contextManager, List.of(oldFrag), List.of(), null, CompletableFuture.completedFuture("old"));
        var newCtx = new Context(
                contextManager, List.of(newFrag), List.of(), null, CompletableFuture.completedFuture("new"));

        var diffs = DiffService.computeDiff(newCtx, oldCtx);
        assertEquals(1, diffs.size(), "Should produce a fallback diff even if new text is unresolved");
        var de = diffs.getFirst();
        assertTrue(de.diff().contains("old-line"), "Diff should reflect old content vs fallback new content");
        assertEquals("old-line", de.oldContent());
        assertEquals("", de.newContent(), "New content should fall back to empty on timeout");
    }

    private static void writeImage(ProjectFile file, Color color) throws Exception {
        var img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                img.setRGB(x, y, color.getRGB());
            }
        }
        Files.createDirectories(file.absPath().getParent());
        ImageIO.write(img, "png", file.absPath().toFile());
    }
}
