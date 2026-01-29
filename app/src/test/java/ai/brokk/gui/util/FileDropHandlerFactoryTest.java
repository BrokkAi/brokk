package ai.brokk.gui.util;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestConsoleIO;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FileDropHandlerFactory covering both non-Chrome (headless) and GUI-like paths.
 *
 * These tests exercise:
 * - busy guard (isLlmTaskInProgress)
 * - non-Chrome fallback path (no IllegalStateException, files added)
 * - GUI-like path via ContextSizeGuard test seam
 */
class FileDropHandlerFactoryTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        // Reset the ContextSizeGuard test seam so other tests are unaffected.
        ContextSizeGuard.TEST_CHECK_AND_CONFIRM = null;
    }

    /**
     * Simple transferable that exposes a javaFileListFlavor payload.
     */
    private static final class SimpleTransferable implements Transferable {
        private final List<File> files;

        SimpleTransferable(List<File> files) {
            this.files = files;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] { DataFlavor.javaFileListFlavor };
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) {
            if (!isDataFlavorSupported(flavor)) {
                return null;
            }
            return files;
        }
    }

    /**
     * Lightweight delegating context manager used in tests. It delegates project/io lookups
     * to a provided TestContextManager but captures addFiles calls and exposes a configurable
     * busy flag.
     */
    private static final class DelegatingContextManager implements IContextManager {
        private final TestContextManager delegate;
        private final CopyOnWriteArrayList<ProjectFile> captured = new CopyOnWriteArrayList<>();
        private volatile boolean busy = false;

        DelegatingContextManager(TestContextManager delegate) {
            this.delegate = delegate;
        }

        void setBusy(boolean busy) {
            this.busy = busy;
        }

        List<ProjectFile> getCaptured() {
            return captured;
        }

        @Override
        public void addFiles(Collection<ProjectFile> files) {
            captured.addAll(files);
        }

        @Override
        public boolean isLlmTaskInProgress() {
            return busy;
        }

        @Override
        public IProject getProject() {
            return delegate.getProject();
        }

        @Override
        public CompletableFuture<Void> submitContextTask(Runnable task) {
            // Run synchronously in tests to avoid threading flakiness
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public IConsoleIO getIo() {
            return delegate.getIo();
        }
    }

    @Test
    void headless_addsFiles_when_not_busy() throws Exception {
        // arrange: create a file inside the project root
        Path projectRoot = tempDir.toAbsolutePath().normalize();
        Files.createDirectories(projectRoot);
        Path file = projectRoot.resolve("foo.txt");
        Files.writeString(file, "hello");

        var io = new TestConsoleIO();
        var base = new TestContextManager(projectRoot, io);
        var cm = new DelegatingContextManager(base);

        var handler = FileDropHandlerFactory.createFileDropHandler(cm, io);

        var transferable = new SimpleTransferable(List.of(file.toFile()));
        var support = new TransferHandler.TransferSupport(new JPanel(), transferable);

        // act
        boolean ok = handler.importData(support);

        // assert
        assertTrue(ok, "importData should return true for successful headless drop");
        assertFalse(cm.getCaptured().isEmpty(), "Files should have been added to the context");
        assertEquals(1, cm.getCaptured().size());
        assertEquals("foo.txt", cm.getCaptured().get(0).absPath().getFileName().toString());
    }

    @Test
    void busy_guard_blocks_when_busy_headless() throws Exception {
        Path projectRoot = tempDir.toAbsolutePath().normalize();
        Files.createDirectories(projectRoot);
        Path file = projectRoot.resolve("foo.txt");
        Files.writeString(file, "hello");

        var io = new TestConsoleIO();
        var base = new TestContextManager(projectRoot, io);
        var cm = new DelegatingContextManager(base);
        cm.setBusy(true);

        var handler = FileDropHandlerFactory.createFileDropHandler(cm, io);

        var transferable = new SimpleTransferable(List.of(file.toFile()));
        var support = new TransferHandler.TransferSupport(new JPanel(), transferable);

        boolean ok = handler.importData(support);

        assertFalse(ok, "importData should return false when a task/LLM is in progress");
        assertTrue(cm.getCaptured().isEmpty(), "No files should be added while busy");
    }

    @Test
    void gui_like_path_invokes_size_check_and_adds_when_allowed() throws Exception {
        Path projectRoot = tempDir.toAbsolutePath().normalize();
        Files.createDirectories(projectRoot);
        Path file = projectRoot.resolve("bar.txt");
        Files.writeString(file, "content");

        var io = new TestConsoleIO();
        var base = new TestContextManager(projectRoot, io);
        var cm = new DelegatingContextManager(base);

        var invoked = new AtomicBoolean(false);

        // Install test seam that simulates the size-check flow and allows the operation
        ContextSizeGuard.TEST_CHECK_AND_CONFIRM = (files, onDecision) -> {
            invoked.set(true);
            // simulate user allowing the operation
            onDecision.accept(ContextSizeGuard.Decision.ALLOW);
        };

        var handler = FileDropHandlerFactory.createFileDropHandler(cm, io);
        var transferable = new SimpleTransferable(List.of(file.toFile()));
        var support = new TransferHandler.TransferSupport(new JPanel(), transferable);

        boolean ok = handler.importData(support);

        assertTrue(ok, "importData should return true when size-check seam allows operation");
        assertTrue(invoked.get(), "ContextSizeGuard test seam should have been invoked");
        assertFalse(cm.getCaptured().isEmpty(), "Files should have been added when size-check allows");
        assertEquals(1, cm.getCaptured().size());
        assertEquals("bar.txt", cm.getCaptured().get(0).absPath().getFileName().toString());
    }

    @Test
    void gui_like_path_cancelled_by_size_check_results_in_no_add() throws Exception {
        Path projectRoot = tempDir.toAbsolutePath().normalize();
        Files.createDirectories(projectRoot);
        Path file = projectRoot.resolve("baz.txt");
        Files.writeString(file, "content");

        var io = new TestConsoleIO();
        var base = new TestContextManager(projectRoot, io);
        var cm = new DelegatingContextManager(base);

        // Install test seam that simulates the user cancelling the confirmation
        ContextSizeGuard.TEST_CHECK_AND_CONFIRM = (files, onDecision) -> onDecision.accept(ContextSizeGuard.Decision.CANCELLED);

        var handler = FileDropHandlerFactory.createFileDropHandler(cm, io);
        var transferable = new SimpleTransferable(List.of(file.toFile()));
        var support = new TransferHandler.TransferSupport(new JPanel(), transferable);

        boolean ok = handler.importData(support);

        assertTrue(ok, "importData returns true even if user cancels the follow-up action (drop handled)");
        assertTrue(ContextSizeGuard.TEST_CHECK_AND_CONFIRM != null, "ContextSizeGuard test seam should have been installed");
        assertTrue(cm.getCaptured().isEmpty(), "Files should not be added when size-check cancels the operation");
    }
}
