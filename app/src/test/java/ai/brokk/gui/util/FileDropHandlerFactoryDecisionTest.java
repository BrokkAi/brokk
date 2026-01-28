package ai.brokk.gui.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.project.IProject;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestProject;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.JLabel;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests to ensure that non-ALLOW decisions from the ContextSizeGuard (CANCELLED, BLOCKED)
 * correctly suppress additions and surface the expected notifications (or lack thereof).
 *
 * These tests use the package-private seam FileDropHandlerFactory.contextSizeChecker to
 * stub size-check decisions synchronously.
 */
public class FileDropHandlerFactoryDecisionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        // Restore production seam after each test
        FileDropHandlerFactory.resetContextSizeCheckerForTests();
    }

    private static TransferHandler.TransferSupport supportForFiles(List<File> files) {
        Transferable transferable = new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[] {DataFlavor.javaFileListFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.javaFileListFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                if (!isDataFlavorSupported(flavor)) {
                    throw new UnsupportedFlavorException(flavor);
                }
                return files;
            }
        };
        return new TransferHandler.TransferSupport(new JLabel(), transferable);
    }

    /**
     * Recording ContextManager that runs submitted context tasks synchronously and records calls
     * to addFiles/addFragments.
     */
    static final class RecordingContextManager extends ContextManager {
        public final List<Collection<ProjectFile>> addFilesCalls = new ArrayList<>();
        public final List<Collection<? extends ContextFragment>> addFragmentsCalls = new ArrayList<>();

        public RecordingContextManager(IProject project) {
            super(project);
            // Default IO so the ContextManager has something sensible; tests may replace it.
            setIo(new TestConsoleIO());
        }

        @Override
        public void addFiles(Collection<ProjectFile> files) {
            addFilesCalls.add(List.copyOf(files));
            // Intentionally do not delegate to super; keep behavior simple/record-only
        }

        @Override
        public void addFragments(Collection<? extends ContextFragment> fragments) {
            addFragmentsCalls.add(List.copyOf(fragments));
        }

        @Override
        public boolean isTaskInProgress() {
            return false;
        }

        @Override
        public CompletableFuture<Void> submitContextTask(Runnable task) {
            // Run synchronously to make assertions straightforward
            task.run();
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void cancelledDecision_preventsAdditions_andShowsCancellationNotification() throws Exception {
        IProject project = new TestProject(tempDir);
        var cm = new RecordingContextManager(project);
        TestConsoleIO consoleIO = new TestConsoleIO();
        cm.setIo(consoleIO);

        // Create an external file outside the project root
        Path external =
                Files.createTempFile("ext-cancel", ".txt").toAbsolutePath().normalize();
        Files.writeString(external, "external content");

        assertFalse(external.startsWith(project.getRoot().toAbsolutePath().normalize()));

        // Stub the size-check seam to synchronously return CANCELLED
        FileDropHandlerFactory.contextSizeChecker =
                (files, cmIn, ioIn, onDecision) -> onDecision.accept(ContextSizeGuard.Decision.CANCELLED);

        TransferHandler handler = FileDropHandlerFactory.createFileDropHandler(cm, consoleIO);
        var support = supportForFiles(List.of(external.toFile()));

        assertTrue(handler.canImport(support));

        // importData should still return true (handler handled the drop), but additions must not occur
        assertTrue(handler.importData(support));

        // No project files or fragments should have been added
        assertTrue(cm.addFilesCalls.isEmpty(), "addFiles should not have been called when user cancelled");
        assertTrue(cm.addFragmentsCalls.isEmpty(), "addFragments should not have been called when user cancelled");

        // TestConsoleIO should have recorded the info notification "File addition cancelled"
        String out = consoleIO.getOutputLog();
        assertNotNull(out);
        assertTrue(
                out.contains("File addition cancelled"),
                () -> "Expected TestConsoleIO to record cancellation notification, got: " + out);
    }

    @Test
    void blockedDecision_preventsAdditions_andDoesNotProduceExtraNotifications() throws Exception {
        IProject project = new TestProject(tempDir);
        var cm = new RecordingContextManager(project);
        TestConsoleIO consoleIO = new TestConsoleIO();
        cm.setIo(consoleIO);

        // Create an external file outside the project root
        Path external =
                Files.createTempFile("ext-block", ".txt").toAbsolutePath().normalize();
        Files.writeString(external, "external content");

        assertFalse(external.startsWith(project.getRoot().toAbsolutePath().normalize()));

        // Stub the size-check seam to synchronously return BLOCKED.
        // We intentionally do NOT show any UI here, simulating that the size guard would have already shown an error.
        final boolean[] blockedObserved = {false};
        FileDropHandlerFactory.contextSizeChecker = (files, cmIn, ioIn, onDecision) -> {
            blockedObserved[0] = true;
            onDecision.accept(ContextSizeGuard.Decision.BLOCKED);
        };

        TransferHandler handler = FileDropHandlerFactory.createFileDropHandler(cm, consoleIO);
        var support = supportForFiles(List.of(external.toFile()));

        assertTrue(handler.canImport(support));

        // Handler returns true indicating it processed the transfer, but it must not add anything.
        assertTrue(handler.importData(support));

        assertTrue(blockedObserved[0], "Expected the test seam to observe BLOCKED decision");

        // Ensure no additions were performed
        assertTrue(cm.addFilesCalls.isEmpty(), "addFiles must not be called when blocked");
        assertTrue(cm.addFragmentsCalls.isEmpty(), "addFragments must not be called when blocked");

        // Because we stubbed the seam to avoid showing UI, TestConsoleIO should have no notifications or errors.
        assertTrue(consoleIO.getOutputLog().isEmpty(), "No info notifications expected when BLOCKED seam is stubbed");
        assertTrue(consoleIO.getErrorLog().isEmpty(), "No error notifications expected when BLOCKED seam is stubbed");
    }
}
