package ai.brokk.gui.util;

import ai.brokk.ContextManager;
import ai.brokk.IAppContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
import javax.swing.TransferHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Factory for creating TransferHandler instances that accept file drops into the workspace.
 *
 * <p>Extended to support files dropped from outside the project root by adding them as
 * ExternalPathFragments.
 */
public final class FileDropHandlerFactory {
    private static final Logger logger = LogManager.getLogger(FileDropHandlerFactory.class);

    private FileDropHandlerFactory() {}

    /* Package-private test seam to allow tests to override the ContextSizeGuard check behavior.
     * Default delegates to production implementation. Generalized to accept IAppContextManager and IConsoleIO
     * so tests can exercise the logic without constructing a real Chrome.
     *
     * Note: the default implementation requires a Chrome to perform UI dialogs and to query model limits.
     * To avoid a raw ClassCastException for callers that pass a non-Chrome IConsoleIO, the default
     * implementation validates the io parameter and throws a clear IllegalStateException if it is not a Chrome.
     * Tests should override this seam when they provide a test IConsoleIO.
     */
    interface ContextSizeChecker {
        void check(
                Collection<? extends BrokkFile> files,
                IAppContextManager contextManager,
                IConsoleIO io,
                Consumer<ContextSizeGuard.Decision> onDecision);
    }

    static ContextSizeChecker contextSizeChecker = (files, contextManager, io, onDecision) -> {
        if (io instanceof Chrome chrome) {
            ContextSizeGuard.checkAndConfirm(files, chrome, onDecision);
        } else {
            throw new IllegalStateException(
                    "ContextSizeChecker default requires io to be a Chrome; override contextSizeChecker when using the IAppContextManager overload with a non-Chrome IConsoleIO.");
        }
    };

    static void resetContextSizeCheckerForTests() {
        contextSizeChecker = (files, contextManager, io, onDecision) -> {
            if (io instanceof Chrome chrome) {
                ContextSizeGuard.checkAndConfirm(files, chrome, onDecision);
            } else {
                throw new IllegalStateException(
                        "ContextSizeChecker default requires io to be a Chrome; override contextSizeChecker when using the IAppContextManager overload with a non-Chrome IConsoleIO.");
            }
        };
    }

    /**
     * Creates a {@link TransferHandler} for the given {@link Chrome} that accepts file drops into the workspace.
     *
     * <p>This overload is the production entrypoint used by the GUI. It delegates to the
     * overload that operates on {@link IAppContextManager} and {@link IConsoleIO} so that tests
     * can exercise the same logic without constructing a full Chrome.
     */
    public static TransferHandler createFileDropHandler(Chrome chrome) {
        return createFileDropHandler(chrome.getContextManager(), chrome);
    }

    /**
     * Core implementation of the drag-and-drop handler. Accepts an {@link IAppContextManager} and
     * {@link IConsoleIO} so tests can provide lightweight fakes instead of constructing a full Chrome.
     *
     * <p>The handler:
     * <ul>
     *   <li>Partitions dropped files into project-internal {@link ProjectFile}s and external {@link ExternalFile}s.</li>
     *   <li>Runs a size check via {@link ContextSizeGuard} (through the {@link #contextSizeChecker} seam).</li>
     *   <li>Adds project files via {@code contextManager.addFiles}.</li>
     *   <li>Adds external files as {@code ExternalPathFragment}s via {@link ContextFragment#toPathFragment}.</li>
     * </ul>
     */
    public static TransferHandler createFileDropHandler(IAppContextManager contextManager, IConsoleIO io) {
        var cm = contextManager;
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }

                // If the context manager is busy (e.g. LLM task running), block the drop.
                if (cm.isTaskInProgress()) {
                    io.systemNotify(
                            "Cannot add to workspace while an action is running.",
                            "Workspace",
                            JOptionPane.INFORMATION_MESSAGE);
                    return false;
                }

                try {
                    @SuppressWarnings("unchecked")
                    List<File> files =
                            (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) {
                        return false;
                    }

                    Path projectRoot =
                            cm.getProject().getRoot().toAbsolutePath().normalize();

                    // Normalize and partition into project-internal paths and external paths
                    List<Path> allPaths = files.stream()
                            .map(File::toPath)
                            .map(Path::toAbsolutePath)
                            .map(Path::normalize)
                            .collect(Collectors.toList());

                    // Project files (preserve insertion order & uniqueness)
                    var projectFiles = allPaths.stream()
                            .filter(p -> p.startsWith(projectRoot))
                            .map(projectRoot::relativize)
                            .map(rel -> new ProjectFile(projectRoot, rel))
                            .collect(Collectors.toCollection(LinkedHashSet::new));

                    // External files (absolute paths outside the project root) - only regular files
                    var externalFiles = allPaths.stream()
                            .filter(p -> !p.startsWith(projectRoot))
                            .filter(p -> {
                                boolean isFile = Files.isRegularFile(p);
                                if (!isFile) {
                                    logger.debug("Skipping external dropped path (not a regular file): {}", p);
                                }
                                return isFile;
                            })
                            .map(p -> {
                                try {
                                    var ef = new ExternalFile(p);
                                    logger.debug("Prepared ExternalFile for dropped path: {}", p);
                                    return ef;
                                } catch (Exception e) {
                                    logger.debug("Dropped path is not usable as ExternalFile: {}", p, e);
                                    return null;
                                }
                            })
                            .filter(x -> x != null)
                            .collect(Collectors.toCollection(LinkedHashSet::new));

                    if (projectFiles.isEmpty() && externalFiles.isEmpty()) {
                        // Preserve existing behavior for empty/unused drops
                        io.showNotification(IConsoleIO.NotificationRole.INFO, "No project files found in drop");
                        return false;
                    }

                    // Combine for size estimation and confirmation
                    Collection<BrokkFile> combined = new LinkedHashSet<>();
                    combined.addAll(projectFiles);
                    combined.addAll(externalFiles);

                    // Check size and confirm before adding large content (goes through test seam)
                    contextSizeChecker.check(combined, cm, io, decision -> {
                        if (decision == ContextSizeGuard.Decision.ALLOW) {
                            // Build the runnable to add files/fragments
                            Runnable additionTask = () -> {
                                try {
                                    if (!projectFiles.isEmpty()) {
                                        cm.addFiles(projectFiles);
                                    }
                                    if (!externalFiles.isEmpty()) {
                                        // Convert ExternalFile -> PathFragments and add them
                                        var fragments = externalFiles.stream()
                                                .map(bf -> ContextFragment.toPathFragment(bf, cm))
                                                .collect(Collectors.toCollection(LinkedHashSet::new));
                                        logger.info(
                                                "Adding {} external file(s) to context: {}",
                                                fragments.size(),
                                                externalFiles);
                                        cm.addFragments(fragments);
                                    }
                                } catch (Exception ex) {
                                    logger.error("Error adding dropped files to context", ex);
                                    io.toolError("Failed to add dropped files: " + ex.getMessage());
                                }
                            };

                            // Prefer ContextManager.submitContextTask when available for correct task scoping;
                            // otherwise fall back to the generic submitBackgroundTask.
                            if (cm instanceof ContextManager cmConcrete) {
                                cmConcrete.submitContextTask(additionTask);
                            } else {
                                cm.submitBackgroundTask("Add dropped files", additionTask);
                            }
                        } else if (decision == ContextSizeGuard.Decision.CANCELLED) {
                            io.showNotification(IConsoleIO.NotificationRole.INFO, "File addition cancelled");
                        }
                        // BLOCKED case already shows error dialog in checkAndConfirm
                    });

                    return true;
                } catch (Exception ex) {
                    logger.error("Error importing dropped files into workspace", ex);
                    io.toolError("Failed to import dropped files: " + ex.getMessage());
                    return false;
                }
            }
        };
    }
}
