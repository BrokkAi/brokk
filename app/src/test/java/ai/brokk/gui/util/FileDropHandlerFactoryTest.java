package ai.brokk.gui.util;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
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
 * Tests for FileDropHandlerFactory focusing on partitioning of dropped files into
 * ProjectFile vs ExternalFile and verifying addFiles/addFragments are called.
 *
 * <p>Uses the package-private test seam FileDropHandlerFactory.contextSizeChecker to capture the
 * files passed to the size check and to force Decision.ALLOW so additions run synchronously.
 */
public class FileDropHandlerFactoryTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        // Reset seam to production behavior
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
        public CompletableFuture<Void> submitContextTask(Runnable task) {
            // Run synchronously to make assertions straightforward
            task.run();
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    public void singleExternalFile_isAddedAsExternalFragment() throws Exception {
        IProject project = new TestProject(tempDir);
        var cm = new RecordingContextManager(project);
        TestConsoleIO consoleIO = new TestConsoleIO();
        cm.setIo(consoleIO);

        // Create an external file outside the project root
        Path external = Files.createTempFile("ext-file-drop", ".txt").toAbsolutePath().normalize();
        Files.writeString(external, "external content");

        assertFalse(external.startsWith(project.getRoot().toAbsolutePath().normalize()));

        // Capture the BrokkFile collection passed to the size checker and force ALLOW
        List<Collection<? extends BrokkFile>> captured = new ArrayList<>();
        FileDropHandlerFactory.contextSizeChecker = (files, cmIn, ioIn, onDecision) -> {
            captured.add(List.copyOf(files));
            onDecision.accept(ContextSizeGuard.Decision.ALLOW);
        };

        TransferHandler handler = FileDropHandlerFactory.createFileDropHandler(cm, consoleIO);
        var support = supportForFiles(List.of(external.toFile()));

        assertTrue(handler.canImport(support));
        assertTrue(handler.importData(support));

        // Verify size-check invoked once with a single ExternalFile
        assertEquals(1, captured.size());
        var filesParam = captured.get(0);
        assertEquals(1, filesParam.size());
        BrokkFile only = filesParam.iterator().next();
        assertTrue(only instanceof ExternalFile);
        assertEquals(external, only.absPath());

        // No project files should have been added
        assertTrue(cm.addFilesCalls.isEmpty(), "No project files expected for purely external drop");

        // External fragments should have been added
        assertEquals(1, cm.addFragmentsCalls.size(), "Expected addFragments to be called once");
        var fragments = cm.addFragmentsCalls.get(0);
        assertFalse(fragments.isEmpty());
        var extFrag = fragments.stream()
                .filter(f -> f instanceof ContextFragments.ExternalPathFragment)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an ExternalPathFragment"));
        ContextFragments.ExternalPathFragment e = (ContextFragments.ExternalPathFragment) extFrag;
        assertEquals(external, e.file().absPath());
    }

    @Test
    public void mixedInternalAndExternal_filesPartitionedAndAddedAppropriately() throws Exception {
        IProject project = new TestProject(tempDir);
        var cm = new RecordingContextManager(project);
        TestConsoleIO consoleIO = new TestConsoleIO();
        cm.setIo(consoleIO);

        // Internal file under project root
        Path internal = tempDir.resolve("internal.txt").toAbsolutePath().normalize();
        Files.createDirectories(internal.getParent());
        Files.writeString(internal, "internal content");

        // External file outside project root
        Path external = Files.createTempFile("ext-mix", ".txt").toAbsolutePath().normalize();
        Files.writeString(external, "external content");

        List<File> dropped = List.of(internal.toFile(), external.toFile());

        // Capture size-check input and allow
        List<Collection<? extends BrokkFile>> captured = new ArrayList<>();
        FileDropHandlerFactory.contextSizeChecker = (files, cmIn, ioIn, onDecision) -> {
            captured.add(List.copyOf(files));
            onDecision.accept(ContextSizeGuard.Decision.ALLOW);
        };

        TransferHandler handler = FileDropHandlerFactory.createFileDropHandler(cm, consoleIO);
        var support = supportForFiles(dropped);

        assertTrue(handler.canImport(support));
        assertTrue(handler.importData(support));

        // Verify size guard observed both ProjectFile and ExternalFile
        assertEquals(1, captured.size());
        var filesParam = captured.get(0);
        assertEquals(2, filesParam.size());
        assertTrue(filesParam.stream().anyMatch(f -> f instanceof ProjectFile));
        assertTrue(filesParam.stream().anyMatch(f -> f instanceof ExternalFile));

        // Verify addFiles was called once with the project file
        assertEquals(1, cm.addFilesCalls.size(), "Expected addFiles to be called once for internal files");
        var addedProjectFiles = cm.addFilesCalls.get(0);
        assertEquals(1, addedProjectFiles.size());
        ProjectFile pf = addedProjectFiles.iterator().next();
        assertEquals(internal, pf.absPath());

        // Verify addFragments was called for the external file
        assertEquals(1, cm.addFragmentsCalls.size(), "Expected addFragments to be called once for external files");
        var fragments = cm.addFragmentsCalls.get(0);
        var extFrag = fragments.stream()
                .filter(f -> f instanceof ContextFragments.ExternalPathFragment)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an ExternalPathFragment"));
        ContextFragments.ExternalPathFragment e = (ContextFragments.ExternalPathFragment) extFrag;
        assertEquals(external, e.file().absPath());
    }
}
