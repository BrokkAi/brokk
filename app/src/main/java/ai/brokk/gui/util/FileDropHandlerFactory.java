package ai.brokk.gui.util;

import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.gui.Chrome;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.JOptionPane;
import javax.swing.TransferHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No project files found in drop");
                        return false;
                    }

                    // Immediately add dropped files as editable (no dialog)
                    contextManager.submitContextTask(() -> contextManager.addFiles(projectFiles));

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
