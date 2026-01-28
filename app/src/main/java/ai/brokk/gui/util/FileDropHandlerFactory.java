package ai.brokk.gui.util;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;

import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
import javax.swing.TransferHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Factory for creating TransferHandler instances that accept file drops into the workspace.
 *
 * Extended to support files dropped from outside the project root by adding them as ExternalPathFragments.
 */
public final class FileDropHandlerFactory {
    private static final Logger logger = LogManager.getLogger(FileDropHandlerFactory.class);

    private FileDropHandlerFactory() {}

    public static TransferHandler createFileDropHandler(Chrome chrome) {
        var contextManager = chrome.getContextManager();
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

                if (contextManager.isLlmTaskInProgress()) {
                    chrome.systemNotify(
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

                    Path projectRoot = contextManager
                            .getProject()
                            .getRoot()
                            .toAbsolutePath()
                            .normalize();

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

                    // External files (absolute paths outside the project root)
                    var externalFiles = allPaths.stream()
                            .filter(p -> !p.startsWith(projectRoot))
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
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No project files found in drop");
                        return false;
                    }

                    // Combine for size estimation and confirmation
                    Collection<BrokkFile> combined = new LinkedHashSet<>();
                    combined.addAll(projectFiles);
                    combined.addAll(externalFiles);

                    // Check size and confirm before adding large content
                    ContextSizeGuard.checkAndConfirm(combined, chrome, decision -> {
                        if (decision == ContextSizeGuard.Decision.ALLOW) {
                            // Run additions off the EDT
                            contextManager.submitContextTask(() -> {
                                try {
                                    if (!projectFiles.isEmpty()) {
                                        contextManager.addFiles(projectFiles);
                                    }
                                    if (!externalFiles.isEmpty()) {
                                        // Convert ExternalFile -> PathFragments and add them
                                        var fragments = externalFiles.stream()
                                                .map(bf -> ContextFragment.toPathFragment(bf, contextManager))
                                                .collect(Collectors.toCollection(LinkedHashSet::new));
                                        logger.info("Adding {} external file(s) to context: {}", fragments.size(), externalFiles);
                                        contextManager.addFragments(fragments);
                                    }
                                } catch (Exception ex) {
                                    logger.error("Error adding dropped files to context", ex);
                                    // Use chrome.toolError on EDT if necessary
                                    chrome.toolError("Failed to add dropped files: " + ex.getMessage());
                                }
                            });
                        } else if (decision == ContextSizeGuard.Decision.CANCELLED) {
                            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "File addition cancelled");
                        }
                        // BLOCKED case already shows error dialog in checkAndConfirm
                    });

                    return true;
                } catch (Exception ex) {
                    logger.error("Error importing dropped files into workspace", ex);
                    chrome.toolError("Failed to import dropped files: " + ex.getMessage());
                    return false;
                }
            }
        };
    }
}
