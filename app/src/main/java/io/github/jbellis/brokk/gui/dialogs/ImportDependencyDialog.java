package io.github.jbellis.brokk.gui.dialogs;

import static java.util.Objects.requireNonNull;

import io.github.jbellis.brokk.AbstractProject;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.FileSelectionPanel;
import io.github.jbellis.brokk.gui.dependencies.DependenciesPanel;
import io.github.jbellis.brokk.util.CloneOperationTracker;
import io.github.jbellis.brokk.util.FileUtil;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class ImportDependencyDialog {
    private static final Logger logger = LogManager.getLogger(ImportDependencyDialog.class);

    public static void show(Chrome chrome, @Nullable DependenciesPanel.DependencyLifecycleListener listener) {
        assert SwingUtilities.isEventDispatchThread() : "Dialogs should be created on the EDT";
        new DialogHelper(chrome, listener).buildAndShow();
    }

    public static void show(Chrome chrome) {
        show(chrome, null);
    }

    private static class DialogHelper {
        private final Chrome chrome;
        private JDialog dialog = new JDialog();

        @Nullable
        private final DependenciesPanel.DependencyLifecycleListener listener;

        private final JButton importButton = new JButton("Import");
        private final JTabbedPane tabbedPane = new JTabbedPane();

        private final Path dependenciesRoot;

        // Tabs
        private final Map<Language, ImportLanguagePanel> languagePanels = new LinkedHashMap<>();

        @Nullable
        private JPanel gitPanel;

        @Nullable
        private FileSelectionPanel dirSelectionPanel;

        // State for selections
        @Nullable
        private BrokkFile selectedDirectory;

        // Git-specific fields
        @Nullable
        private JTextField gitUrlField;

        @Nullable
        private JButton validateGitRepoButton;

        @Nullable
        private JComboBox<String> gitRefComboBox;

        @Nullable
        private GitRepo.RemoteInfo remoteInfo;

        DialogHelper(Chrome chrome, @Nullable DependenciesPanel.DependencyLifecycleListener listener) {
            this.chrome = chrome;
            this.dependenciesRoot = chrome.getProject()
                    .getRoot()
                    .resolve(AbstractProject.BROKK_DIR)
                    .resolve(AbstractProject.DEPENDENCIES_DIR);
            this.listener = listener;

            CloneOperationTracker.cleanupOrphanedClones(dependenciesRoot);
        }

        void buildAndShow() {
            dialog = new JDialog(chrome.getFrame(), "Import Dependency", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setLayout(new BorderLayout(10, 10));

            var mainPanel = new JPanel(new BorderLayout());
            mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

            // Dynamically add language tabs only when candidates exist.
            var project = chrome.getProject();
            for (var lang : project.getAnalyzerLanguages()) {
                try {
                    if (lang.getDependencyImportSupport() == Language.ImportSupport.NONE) continue;

                    var lp = new ImportLanguagePanel(chrome, lang);
                    lp.setLifecycleListener(listener);
                    lp.addSelectionListener(pkg -> updateImportButtonState());
                    lp.addDoubleClickListener(() -> {
                        if (importButton.isEnabled() && tabbedPane.getSelectedComponent() == lp) {
                            if (lp.initiateImport()) dialog.dispose();
                        }
                    });

                    languagePanels.put(lang, lp);
                    tabbedPane.addTab(lang.name(), lp);
                } catch (Exception ex) {
                    logger.debug(
                            "Skipping language {} due to candidate discovery error: {}", lang.name(), ex.toString());
                }
            }

            // Repository + Local Directory tabs
            gitPanel = createGitPanel();
            tabbedPane.addTab("Repository", gitPanel);

            var dirPanel = createDirectoryPanel();
            tabbedPane.addTab("Local Directory", dirPanel);

            tabbedPane.addChangeListener(e -> updateImportButtonState());

            mainPanel.add(tabbedPane, BorderLayout.CENTER);

            // Buttons
            importButton.setEnabled(false);
            importButton.addActionListener(e -> {
                var comp = tabbedPane.getSelectedComponent();
                if (comp instanceof ImportLanguagePanel lp) {
                    if (lp.initiateImport()) {
                        dialog.dispose();
                    } else {
                        importButton.setEnabled(true);
                    }
                } else if (comp == gitPanel) {
                    performGitImport();
                } else {
                    performDirectoryImport();
                }
            });

            var cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> dialog.dispose());

            var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(importButton);
            buttons.add(cancelButton);

            dialog.add(mainPanel, BorderLayout.CENTER);
            dialog.add(buttons, BorderLayout.SOUTH);

            updateImportButtonState();

            dialog.pack();
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
        }

        private JPanel createGitPanel() {
            var panel = new JPanel(new GridBagLayout());
            gitUrlField = new JTextField();
            gitUrlField.setColumns(30);
            validateGitRepoButton = new JButton("Load Tags & Branches");
            gitRefComboBox = new JComboBox<>();
            gitRefComboBox.setEnabled(false);
            gitRefComboBox.addActionListener(e -> updateImportButtonState());

            var gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 2, 2, 2);
            gbc.anchor = GridBagConstraints.WEST;

            // Row 0: URL Label
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 0;
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.NONE;
            panel.add(new JLabel("Repo URL:"), gbc);

            // Row 0: URL Field
            gbc.gridx = 1;
            gbc.gridy = 0;
            gbc.weightx = 1.0;
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(gitUrlField, gbc);

            // Row 0: Load Tags & Branches Button
            gbc.gridx = 2;
            gbc.gridy = 0;
            gbc.weightx = 0;
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.NONE;
            panel.add(validateGitRepoButton, gbc);

            // Row 1: Branch/Tag Label
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.weightx = 0;
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.NONE;
            panel.add(new JLabel("Branch/Tag:"), gbc);

            // Row 1: Branch/Tag Combo
            gbc.gridx = 1;
            gbc.gridy = 1;
            gbc.weightx = 1.0;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(gitRefComboBox, gbc);

            // Spacer to push content to top
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.weightx = 0;
            gbc.weighty = 1.0;
            gbc.gridwidth = 3;
            gbc.fill = GridBagConstraints.VERTICAL;
            panel.add(new JPanel(), gbc);

            panel.setBorder(new EmptyBorder(5, 5, 5, 5));

            validateGitRepoButton.addActionListener(e -> validateRepository());
            gitUrlField.getDocument().addDocumentListener(new SimpleDocumentListener() {
                @Override
                public void update(DocumentEvent e) {
                    remoteInfo = null;
                    requireNonNull(gitRefComboBox).removeAllItems();
                    gitRefComboBox.setEnabled(false);
                    updateImportButtonState();
                }
            });

            return panel;
        }

        private JPanel createDirectoryPanel() {
            Predicate<File> filter = File::isDirectory;
            Future<List<Path>> candidates = CompletableFuture.completedFuture(List.of());
            String helpText =
                    "Select a directory containing sources.\nSelected directory will be copied into the project.";

            var fspConfig = new FileSelectionPanel.Config(
                    chrome.getProject(),
                    true,
                    filter,
                    candidates,
                    false,
                    this::handleDirectoryConfirmed,
                    false,
                    helpText);

            dirSelectionPanel = new FileSelectionPanel(fspConfig);
            dirSelectionPanel.getFileInputComponent().getDocument().addDocumentListener(new SimpleDocumentListener() {
                @Override
                public void update(DocumentEvent e) {
                    onDirectoryInputChange();
                }
            });

            var wrapper = new JPanel(new BorderLayout());
            wrapper.add(dirSelectionPanel, BorderLayout.CENTER);
            return wrapper;
        }

        private void onDirectoryInputChange() {
            if (dirSelectionPanel == null) {
                selectedDirectory = null;
                updateImportButtonState();
                return;
            }
            var text = dirSelectionPanel.getInputText();
            if (text.isEmpty()) {
                selectedDirectory = null;
                updateImportButtonState();
                return;
            }

            Path path;
            try {
                path = Paths.get(text);
            } catch (InvalidPathException e) {
                selectedDirectory = null;
                updateImportButtonState();
                return;
            }

            Path resolvedPath =
                    path.isAbsolute() ? path : chrome.getProject().getRoot().resolve(path);
            if (Files.exists(resolvedPath)) {
                BrokkFile bf = resolvedPath.startsWith(chrome.getProject().getRoot())
                        ? new io.github.jbellis.brokk.analyzer.ProjectFile(
                                chrome.getProject().getRoot(),
                                chrome.getProject().getRoot().relativize(resolvedPath))
                        : new io.github.jbellis.brokk.analyzer.ExternalFile(resolvedPath);
                updateDirectorySelection(bf);
            } else {
                selectedDirectory = null;
                updateImportButtonState();
            }
        }

        private void handleDirectoryConfirmed(BrokkFile file) {
            if (dirSelectionPanel != null) {
                dirSelectionPanel.setInputText(file.absPath().toString());
            }
            updateDirectorySelection(file);
            if (importButton.isEnabled() && tabbedPane.getSelectedComponent() == dirSelectionPanel) {
                performDirectoryImport();
            }
        }

        private void updateDirectorySelection(BrokkFile file) {
            selectedDirectory = null;
            var path = file.absPath();
            if (Files.exists(path) && Files.isDirectory(path)) {
                selectedDirectory = file;
            }
            updateImportButtonState();
        }

        private void updateImportButtonState() {
            var comp = tabbedPane.getSelectedComponent();
            boolean enabled;
            if (comp instanceof ImportLanguagePanel lp) {
                enabled = lp.getSelectedPackage() != null;
            } else if (comp == gitPanel) {
                enabled = remoteInfo != null && requireNonNull(gitRefComboBox).getSelectedItem() != null;
            } else {
                enabled = selectedDirectory != null;
            }
            importButton.setEnabled(enabled);
        }

        private void validateRepository() {
            String url = requireNonNull(gitUrlField).getText().trim();
            if (url.isEmpty()) {
                chrome.toolError("Git repository URL cannot be empty.", "Validation Error");
                return;
            }

            requireNonNull(validateGitRepoButton).setEnabled(false);

            chrome.getContextManager().submitBackgroundTask("Loading branches and tags", () -> {
                try {
                    String normalizedUrl = url;
                    if (!normalizedUrl.endsWith(".git")) {
                        if (normalizedUrl.startsWith("http")) {
                            normalizedUrl += ".git";
                        }
                    }
                    final String finalUrl = normalizedUrl;
                    SwingUtilities.invokeLater(() -> requireNonNull(gitUrlField).setText(finalUrl));

                    var info = GitRepo.listRemoteRefs(finalUrl);
                    this.remoteInfo = info;

                    SwingUtilities.invokeLater(() -> {
                        populateGitRefComboBox(info);
                        chrome.systemOutput("Repository validated successfully. Select a branch or tag to import.");
                        updateImportButtonState();
                    });
                } catch (Exception ex) {
                    logger.warn("Failed to validate git repo {}", url, ex);
                    this.remoteInfo = null;
                    SwingUtilities.invokeLater(() -> {
                        chrome.toolError(
                                "Failed to access remote repository:\n" + ex.getMessage(), "Validation Failed");
                        updateImportButtonState();
                    });
                } finally {
                    SwingUtilities.invokeLater(
                            () -> requireNonNull(validateGitRepoButton).setEnabled(true));
                }
                return null;
            });
        }

        private void populateGitRefComboBox(GitRepo.RemoteInfo info) {
            var combo = requireNonNull(gitRefComboBox);
            combo.removeAllItems();

            if (info.defaultBranch() != null) {
                combo.addItem(info.defaultBranch() + " (default)");
            }
            for (String branch : info.branches()) {
                if (!branch.equals(info.defaultBranch())) combo.addItem(branch);
            }
            for (String tag : info.tags()) {
                combo.addItem(tag);
            }

            combo.setEnabled(combo.getItemCount() > 0);
            if (combo.getItemCount() > 0) combo.setSelectedIndex(0);
        }

        private void performDirectoryImport() {
            if (selectedDirectory == null) {
                importButton.setEnabled(true);
                return;
            }

            var sourcePath = selectedDirectory.absPath();
            var depName = sourcePath.getFileName().toString();

            if (listener != null) {
                SwingUtilities.invokeLater(() -> listener.dependencyImportStarted(depName));
            }
            dialog.dispose();

            var project = chrome.getProject();
            if (project.getAnalyzerLanguages().stream().anyMatch(lang -> lang.isAnalyzed(project, sourcePath))) {
                int proceedResponse = javax.swing.JOptionPane.showConfirmDialog(
                        dialog,
                        "The selected directory might already be part of the project's analyzed sources. Proceed?",
                        "Confirm Import",
                        javax.swing.JOptionPane.YES_NO_OPTION,
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                if (proceedResponse == javax.swing.JOptionPane.NO_OPTION) {
                    importButton.setEnabled(true);
                    return;
                }
            }

            var targetPath = dependenciesRoot.resolve(sourcePath.getFileName());
            if (Files.exists(targetPath)) {
                int overwriteResponse = javax.swing.JOptionPane.showConfirmDialog(
                        dialog,
                        "The destination '" + targetPath.getFileName() + "' already exists. Overwrite?",
                        "Confirm Overwrite",
                        javax.swing.JOptionPane.YES_NO_OPTION,
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                if (overwriteResponse == javax.swing.JOptionPane.NO_OPTION) {
                    importButton.setEnabled(true);
                    return;
                }
            }

            chrome.getContextManager().submitBackgroundTask("Copying directory: " + sourcePath.getFileName(), () -> {
                try {
                    Files.createDirectories(dependenciesRoot);
                    if (Files.exists(targetPath)) {
                        boolean deleted = FileUtil.deleteRecursively(targetPath);
                        if (!deleted && Files.exists(targetPath)) {
                            throw new IOException("Failed to delete existing destination: " + targetPath);
                        }
                    }
                    List<String> allowedExtensions = project.getAnalyzerLanguages().stream()
                            .flatMap(lang -> lang.getExtensions().stream())
                            .distinct()
                            .toList();
                    copyDirectoryRecursively(sourcePath, targetPath, allowedExtensions);
                    SwingUtilities.invokeLater(() -> {
                        chrome.systemOutput(
                                "Directory copied to " + targetPath + ". Reopen project to incorporate the new files.");
                        if (listener != null) listener.dependencyImportFinished(depName);
                    });
                } catch (IOException ex) {
                    logger.error("Error copying directory {} to {}", sourcePath, targetPath, ex);
                    SwingUtilities.invokeLater(() -> {
                        javax.swing.JOptionPane.showMessageDialog(
                                dialog,
                                "Error copying directory: " + ex.getMessage(),
                                "Error",
                                javax.swing.JOptionPane.ERROR_MESSAGE);
                        importButton.setEnabled(true);
                    });
                }
                return null;
            });
        }

        private void performGitImport() {
            if (remoteInfo == null) {
                javax.swing.JOptionPane.showMessageDialog(
                        dialog,
                        "No valid Git repository selected.",
                        "Import Error",
                        javax.swing.JOptionPane.ERROR_MESSAGE);
                importButton.setEnabled(true);
                return;
            }

            final String repoUrl = remoteInfo.url();
            String selectedRef = (String) requireNonNull(gitRefComboBox).getSelectedItem();
            final String branchOrTag = selectedRef != null && selectedRef.endsWith(" (default)")
                    ? selectedRef.replace(" (default)", "")
                    : selectedRef;

            final String repoName =
                    repoUrl.substring(repoUrl.lastIndexOf('/') + 1).replace(".git", "");
            final Path targetPath = dependenciesRoot.resolve(repoName);

            if (Files.exists(targetPath)) {
                int overwriteResponse = javax.swing.JOptionPane.showConfirmDialog(
                        dialog,
                        "The destination '" + targetPath.getFileName() + "' already exists. Overwrite?",
                        "Confirm Overwrite",
                        javax.swing.JOptionPane.YES_NO_OPTION,
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                if (overwriteResponse == javax.swing.JOptionPane.NO_OPTION) {
                    importButton.setEnabled(true);
                    return;
                }
            }

            if (listener != null) {
                SwingUtilities.invokeLater(() -> listener.dependencyImportStarted(repoName));
            }
            dialog.dispose();

            chrome.getContextManager().submitBackgroundTask("Cloning repository: " + repoUrl, () -> {
                try {
                    Files.createDirectories(dependenciesRoot);

                    if (Files.exists(targetPath)) {
                        boolean deleted = FileUtil.deleteRecursively(targetPath);
                        if (!deleted && Files.exists(targetPath)) {
                            throw new IOException("Failed to delete existing destination: " + targetPath);
                        }
                    }

                    GitRepo.cloneRepo(repoUrl, targetPath, 1, branchOrTag);

                    CloneOperationTracker.createInProgressMarker(targetPath, repoUrl, branchOrTag);
                    CloneOperationTracker.registerCloneOperation(targetPath);

                    try {
                        Path gitInternalDir = targetPath.resolve(".git");
                        if (Files.exists(gitInternalDir)) {
                            FileUtil.deleteRecursively(gitInternalDir);
                        }

                        CloneOperationTracker.createCompleteMarker(targetPath, repoUrl, branchOrTag);
                        CloneOperationTracker.unregisterCloneOperation(targetPath);

                        SwingUtilities.invokeLater(() -> {
                            chrome.systemOutput("Repository " + repoName + " imported successfully.");
                            if (listener != null) listener.dependencyImportFinished(repoName);
                        });
                    } catch (Exception postCloneException) {
                        CloneOperationTracker.unregisterCloneOperation(targetPath);
                        throw postCloneException;
                    }

                } catch (Exception ex) {
                    logger.error("Error cloning Git repository {}", repoUrl, ex);

                    if (Files.exists(targetPath)) {
                        try {
                            FileUtil.deleteRecursively(targetPath);
                        } catch (Exception cleanupEx) {
                            logger.warn("Failed to cleanup target directory after clone failure", cleanupEx);
                        }
                    }

                    SwingUtilities.invokeLater(() -> {
                        chrome.toolError("Failed to import repository: " + ex.getMessage(), "Import Error");
                        importButton.setEnabled(true);
                    });
                }
                return null;
            });
        }
    }

    private interface SimpleDocumentListener extends DocumentListener {
        void update(DocumentEvent e);

        @Override
        default void insertUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void removeUpdate(DocumentEvent e) {
            update(e);
        }

        @Override
        default void changedUpdate(DocumentEvent e) {
            update(e);
        }
    }

    private static void copyDirectoryRecursively(Path source, Path destination, List<String> allowedExtensions)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot > 0 && lastDot < fileName.length() - 1) {
                    if (allowedExtensions.contains(
                            fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT))) {
                        Files.copy(
                                file,
                                destination.resolve(source.relativize(file)),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
