package io.github.jbellis.brokk.gui.util;

import io.github.jbellis.brokk.AnalyzerWrapper;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.dialogs.DropActionDialog;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
import javax.swing.TransferHandler;
import javax.swing.TransferHandler.TransferSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FileDropHandlerFactory {
    private static final Logger logger = LogManager.getLogger(FileDropHandlerFactory.class);

    private FileDropHandlerFactory() {}

    private static boolean isAnalyzerReady(ContextManager contextManager, IConsoleIO io) {
        if (!contextManager.getAnalyzerWrapper().isReady()) {
            io.systemNotify(
                    AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                    AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

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
                            .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

                    if (projectFiles.isEmpty()) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No project files found in drop");
                        return false;
                    }

                    // Ask the user what to do
                    var analyzedExts = contextManager.getProject().getAnalyzerLanguages().stream()
                            .flatMap(lang -> lang.getExtensions().stream())
                            .collect(Collectors.toSet());
                    boolean canSummarize = projectFiles.stream().anyMatch(pf -> analyzedExts.contains(pf.extension()));
                    java.awt.Point pointer = null;
                    try {
                        var pi = java.awt.MouseInfo.getPointerInfo();
                        if (pi != null) {
                            pointer = pi.getLocation();
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                    var selection = DropActionDialog.show(chrome.getFrame(), canSummarize, pointer);
                    if (selection == null) {
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Drop canceled");
                        return false;
                    }
                    switch (selection) {
                        case EDIT -> {
                            contextManager.submitContextTask(() -> {
                                contextManager.addFiles(projectFiles);
                            });
                        }
                        case SUMMARIZE -> {
                            if (!isAnalyzerReady(contextManager, chrome)) {
                                return false;
                            }
                            contextManager.submitContextTask(() -> {
                                contextManager.addSummaries(
                                        new java.util.HashSet<>(projectFiles), Collections.emptySet());
                            });
                        }
                        default -> {
                            logger.warn("Unexpected drop selection: {}", selection);
                            return false;
                        }
                    }

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
