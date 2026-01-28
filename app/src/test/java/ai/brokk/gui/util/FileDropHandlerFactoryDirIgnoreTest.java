package ai.brokk.gui.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.ExternalFile;
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
 * Verifies that dropped external directories are ignored (not converted into ExternalFile fragments).
 *
 * This guards the Files.isRegularFile filter in FileDropHandlerFactory so that directories do not
 * become ExternalPathFragment instances and produce broken fragments.
 */
public class FileDropHandlerFactoryDirIgnoreTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
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
            // Use a friendly TestConsoleIO for any UI feedback by default
            setIo(new TestConsoleIO());
        }

        @Override
        public void addFiles(Collection<ProjectFile> files) {
            addFilesCalls.add(List.copyOf(files));
            // Do not delegate to super to keep behavior simple/record-only
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
    void externalDirectory_isIgnored_and_onlyRegularFileIsProcessed() throws Exception {
        IProject project = new TestProject(tempDir);
        var cm = new RecordingContextManager(project);
        TestConsoleIO consoleIO = new TestConsoleIO();
        cm.setIo(consoleIO);

        // External directory outside the project root
        Path externalDir =
                Files.createTempDirectory("ext-dir-drop").toAbsolutePath().normalize();

        // External regular file outside the project root
        Path externalFile =
                Files.createTempFile("ext-file-drop", ".txt").toAbsolutePath().normalize();
        Files.writeString(externalFile, "external file content");

        assertFalse(externalDir.startsWith(project.getRoot().toAbsolutePath().normalize()));
        assertFalse(externalFile.startsWith(project.getRoot().toAbsolutePath().normalize()));

        // Capture the BrokkFile collection passed to the size checker and force ALLOW
        List<Collection<? extends BrokkFile>> captured = new ArrayList<>();
        FileDropHandlerFactory.contextSizeChecker = (files, cmIn, ioIn, onDecision) -> {
            captured.add(List.copyOf(files));
            onDecision.accept(ContextSizeGuard.Decision.ALLOW);
        };

        TransferHandler handler = FileDropHandlerFactory.createFileDropHandler(cm, consoleIO);
        var support = supportForFiles(List.of(externalDir.toFile(), externalFile.toFile()));

        assertTrue(handler.canImport(support));
        assertTrue(handler.importData(support));

        // Verify size-check invoked once
        assertEquals(1, captured.size(), "Expected one invocation of size checker");
        var filesParam = captured.get(0);

        // The combined collection should include the regular external file but NOT the directory.
        assertTrue(
                filesParam.stream()
                        .anyMatch(f -> f instanceof ExternalFile && f.absPath().equals(externalFile)),
                "Expected ExternalFile for the regular external file to be present");
        assertFalse(
                filesParam.stream()
                        .anyMatch(f -> f instanceof ExternalFile && f.absPath().equals(externalDir)),
                "Did not expect the external directory to be present as an ExternalFile in the size-check collection");

        // No project files should have been added
        assertTrue(cm.addFilesCalls.isEmpty(), "No project files expected for purely external drops");

        // External fragments should have been added only for the regular file
        assertEquals(1, cm.addFragmentsCalls.size(), "Expected addFragments to be called once");
        var fragments = cm.addFragmentsCalls.get(0);
        assertFalse(fragments.isEmpty(), "Expected at least one fragment for the regular external file");

        // Ensure no fragment corresponds to the directory
        assertFalse(
                fragments.stream()
                        .anyMatch(f -> f instanceof ai.brokk.context.ContextFragments.ExternalPathFragment
                                && ((ai.brokk.context.ContextFragments.ExternalPathFragment) f)
                                        .file()
                                        .absPath()
                                        .equals(externalDir)),
                "Expected no ExternalPathFragment for the external directory");

        // Ensure there is an ExternalPathFragment for the regular file
        var extFrag = fragments.stream()
                .filter(f -> f instanceof ai.brokk.context.ContextFragments.ExternalPathFragment)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an ExternalPathFragment for the regular file"));
        ai.brokk.context.ContextFragments.ExternalPathFragment e =
                (ai.brokk.context.ContextFragments.ExternalPathFragment) extFrag;
        assertEquals(externalFile, e.file().absPath());
    }
}
