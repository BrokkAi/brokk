package ai.brokk.gui.util;

import ai.brokk.IConsoleIO;
import ai.brokk.IContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
import javax.swing.TransferHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FileDropHandlerFactory {
    private static final Logger logger = LogManager.getLogger(FileDropHandlerFactory.class);

    private FileDropHandlerFactory() {}

    /**
     * Generic file-drop handler that works with any IContextManager / IConsoleIO pair.
     *
     * This method preserves the GUI behavior when a Chrome instance is provided as the IConsoleIO:
     * - For Chrome, ContextSizeGuard.checkAndConfirm(...) is used to prompt/deny based on token estimates.
     *
     * For non-Chrome IConsoleIO implementations it will not attempt to cast to Chrome or show UI
     * confirmation dialogs. Instead it takes a safe fallback path: it skips the interactive size
     * confirmation (but not the task-in-progress guard) and proceeds to submit the files to the
     * context. A notification is emitted to inform the caller that size checks are not available.
     */
    public static TransferHandler createFileDropHandler(ai.brokk.IContextManager contextManager, IConsoleIO io) {
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

                // Preserve the existing task-in-progress guard used by the Chrome overload.
                // Use the same semantic: block drops while an LLM/task is in progress.
                if (contextManager.isLlmTaskInProgress()) {
                    // Prefer non-blocking notification for generic IConsoleIO implementations.
                    try {
                        io.showNotification(IConsoleIO.NotificationRole.INFO, "Cannot add to workspace while an action is running.");
                    } catch (Exception ignored) {
                        // Best-effort: do not fail if the IO implementation mishandles notifications.
                    }
                    return false;
                }

                try {
                    @SuppressWarnings("unchecked")
                    List<File> files =
                            (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) {
                        return false;
                    }

                    Path projectRoot = contextManager
                            .getProject()
                            .getRoot()
                            .toAbsolutePath()
                            .normalize();
                    // Map to ProjectFile inside this project; ignore anything outside
                    var projectFiles = files.stream()
                            .map(File::toPath)
                            .map(Path::toAbsolutePath)
                            .map(Path::normalize)
                            .filter(p -> {
                                boolean inside = p.startsWith(projectRoot);
                                if (!inside) {
                                    logger.debug("Ignoring dropped file outside project: {}", p);
                                }
                                return inside;
                            })
                            .map(projectRoot::relativize)
                            .map(rel -> new ProjectFile(projectRoot, rel))
                            .collect(Collectors.toCollection(LinkedHashSet::new));

                    if (projectFiles.isEmpty()) {
                        io.showNotification(IConsoleIO.NotificationRole.INFO, "No project files found in drop");
                        return false;
                    }

                    // If the provided IConsoleIO is a Chrome, preserve interactive size checks.
                    // Test seam: if tests installed a ContextSizeGuard.TEST_CHECK_AND_CONFIRM hook,
                    // prefer that path so unit tests can exercise the size-check flow without
                    // constructing a full Chrome instance. This is only active when the seam is set.
                    var testSeam = ContextSizeGuard.TEST_CHECK_AND_CONFIRM;
                    if (testSeam != null) {
                        testSeam.accept(projectFiles, decision -> {
                            if (decision == ContextSizeGuard.Decision.ALLOW) {
                                contextManager.submitContextTask(() -> contextManager.addFiles(projectFiles));
                            } else if (decision == ContextSizeGuard.Decision.CANCELLED) {
                                try {
                                    io.showNotification(IConsoleIO.NotificationRole.INFO, "File addition cancelled");
                                } catch (Exception ignored) {
                                }
                            } else if (decision == ContextSizeGuard.Decision.BLOCKED) {
                                try {
                                    io.toolError("File addition blocked due to context size", "Context Size Limit");
                                } catch (Exception ignored) {
                                }
                            }
                        });
                    } else if (io instanceof Chrome chrome) {
                        ContextSizeGuard.checkAndConfirm(projectFiles, chrome, decision -> {
                            if (decision == ContextSizeGuard.Decision.ALLOW) {
                                contextManager.submitContextTask(() -> contextManager.addFiles(projectFiles));
                            } else if (decision == ContextSizeGuard.Decision.CANCELLED) {
                                chrome.showNotification(IConsoleIO.NotificationRole.INFO, "File addition cancelled");
                            }
                            // BLOCKED case already shows error dialog in checkAndConfirm
                        });
                    } else {
                        // Non-GUI or test-friendly fallback: do not attempt to show dialogs.
                        // Fail-open policy: allow the addition but inform the caller that size checks were skipped.
                        try {
                            io.showNotification(
                                    IConsoleIO.NotificationRole.INFO,
                                    "Context size checks are unavailable in this environment; proceeding to add files.");
                        } catch (Exception ignored) {
                            // best-effort only
                        }
                        contextManager.submitContextTask(() -> contextManager.addFiles(projectFiles));
                    }

                    return true;
                } catch (Exception ex) {
                    logger.error("Error importing dropped files into workspace", ex);
                    try {
                        io.toolError("Failed to import dropped files: " + ex.getMessage());
                    } catch (Exception ignored) {
                        // Do not let notification failures mask the original problem.
                    }
                    return false;
                }
            }
        };
    }

    /** Convenience overload to keep existing Chrome-based callers working. */
    public static TransferHandler createFileDropHandler(Chrome chrome) {
        return createFileDropHandler(chrome.getContextManager(), chrome);
    }
}
