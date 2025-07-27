package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.FileSelectionPanel;
import io.github.jbellis.brokk.util.Decompiler;
import io.github.jbellis.brokk.util.FileUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

public class ImportDependencyDialog {
    private static final Logger logger = LogManager.getLogger(ImportDependencyDialog.class);

    private enum SourceType { JAR, DIRECTORY, GIT }

    public static void show(Chrome chrome, @Nullable ManageDependenciesDialog.DependencyLifecycleListener listener) {
        assert SwingUtilities.isEventDispatchThread() : "Dialogs should be created on the EDT";
        new DialogHelper(chrome, listener).buildAndShow();
    }

    public static void show(Chrome chrome) {
        show(chrome, null);
    }

    private static class DialogHelper {
        private final Chrome chrome;
        private JDialog dialog = new JDialog();
        @Nullable private JRadioButton jarRadioButton;
        @Nullable private JRadioButton dirRadioButton;
        @Nullable private JRadioButton gitRadioButton;
        @Nullable private final ManageDependenciesDialog.DependencyLifecycleListener listener;

        private JPanel contentPanel = new JPanel(new BorderLayout());
        private JButton importButton = new JButton("Import");

        private SourceType currentSourceType = SourceType.JAR;
        private final Path dependenciesRoot;

        // --- File/Dir specific fields
        @Nullable private FileSelectionPanel currentFileSelectionPanel;
        @Nullable private BrokkFile selectedBrokkFileForImport;

        // --- Git specific fields
        @Nullable private JPanel gitPanel;
        @Nullable private JTextField gitUrlField;
        @Nullable private JComboBox<String> gitRefComboBox;
        @Nullable private JButton validateGitRepoButton;
        @Nullable private GitRepo.RemoteInfo remoteInfo;

        DialogHelper(Chrome chrome, @Nullable ManageDependenciesDialog.DependencyLifecycleListener listener) {
            this.chrome = chrome;
            this.dependenciesRoot = chrome.getProject().getRoot().resolve(".brokk").resolve("dependencies");
            this.listener = listener;
        }

        void buildAndShow() {
            dialog = new JDialog(chrome.getFrame(), "Import Dependency", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setLayout(new BorderLayout(10, 10));

            JPanel mainPanel = new JPanel(new GridBagLayout());
            mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 5, 5, 5);
            gbc.anchor = GridBagConstraints.WEST;

            boolean allowJarImport = chrome.getProject().getAnalyzerLanguages().contains(Language.JAVA);
            currentSourceType = allowJarImport ? SourceType.JAR : SourceType.DIRECTORY;

            // --- Source Type Radio Buttons ---
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridwidth = 1;
            gbc.anchor = GridBagConstraints.NORTHWEST;
            mainPanel.add(new JLabel("Source Type:"), gbc);

            ButtonGroup sourceTypeGroup = new ButtonGroup();
            JPanel radioPanel = new JPanel();
            radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.PAGE_AXIS));

            if (allowJarImport) {
                jarRadioButton = new JRadioButton("JAR (decompile & add sources)");
                jarRadioButton.setSelected(true);
                jarRadioButton.addActionListener(e -> updateSourceType(SourceType.JAR));
                sourceTypeGroup.add(jarRadioButton);
                radioPanel.add(jarRadioButton);
            }

            dirRadioButton = new JRadioButton("Directory");
            dirRadioButton.setSelected(!allowJarImport);
            dirRadioButton.addActionListener(e -> updateSourceType(SourceType.DIRECTORY));
            sourceTypeGroup.add(dirRadioButton);
            radioPanel.add(dirRadioButton);

            gitRadioButton = new JRadioButton("Git Repository");
            gitRadioButton.addActionListener(e -> updateSourceType(SourceType.GIT));
            sourceTypeGroup.add(gitRadioButton);
            radioPanel.add(gitRadioButton);

            gbc.gridx = 1;
            mainPanel.add(radioPanel, gbc);

            // --- Content Panel (for FSP or Git panel) ---
            gbc.gridy = 1;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.NORTH;
            gbc.weightx = 1.0;
            gbc.weighty = 0;
            mainPanel.add(contentPanel, gbc);

            dialog.add(mainPanel, BorderLayout.CENTER);

            // --- Buttons ---
            importButton.setEnabled(false);
            importButton.addActionListener(e -> performImport());
            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> dialog.dispose());

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(importButton);
            buttonPanel.add(cancelButton);
            dialog.add(buttonPanel, BorderLayout.SOUTH);

            updateContentPanel();

            dialog.pack();
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
        }

        private void updateSourceType(SourceType newType) {
            if (currentSourceType == newType) return;
            currentSourceType = newType;
            selectedBrokkFileForImport = null;
            remoteInfo = null;
            importButton.setEnabled(false);
            updateContentPanel();
            dialog.pack();
        }

        private void updateContentPanel() {
            contentPanel.removeAll();
            if (currentSourceType == SourceType.GIT) {
                contentPanel.add(createGitPanel(), BorderLayout.CENTER);
            } else {
                contentPanel.add(createFileSelectionPanel(), BorderLayout.CENTER);
            }
            contentPanel.revalidate();
            contentPanel.repaint();
        }

        private JPanel createGitPanel() {
            gitPanel = new JPanel(new GridBagLayout());
            gitUrlField = new JTextField();
            gitUrlField.setColumns(30);
            validateGitRepoButton = new JButton("Load Tags & Branches");
            gitRefComboBox = new JComboBox<>();

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(2, 2, 2, 2);
            gbc.anchor = GridBagConstraints.WEST;

            // Row 0: URL Label
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 0;
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.NONE;
            gitPanel.add(new JLabel("Repo URL:"), gbc);

            // Row 0: URL Field
            gbc.gridx = 1;
            gbc.gridy = 0;
            gbc.weightx = 1.0;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gitPanel.add(gitUrlField, gbc);

            // Row 1: Button
            gbc.gridx = 1;
            gbc.gridy = 1;
            // gbc constraints from URL Field are reused to make button stretch
            gitPanel.add(validateGitRepoButton, gbc);

            // Row 2: Branch/Tag Label
            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.weightx = 0;
            gbc.gridwidth = 1;
            gbc.fill = GridBagConstraints.NONE;
            gitPanel.add(new JLabel("Branch/Tag:"), gbc);

            // Row 2: Branch/Tag ComboBox
            gbc.gridx = 1;
            gbc.gridy = 2;
            gbc.weightx = 1.0;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gitPanel.add(gitRefComboBox, gbc);

            gitPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

            validateGitRepoButton.addActionListener(e -> validateAndPopulateRefs());
            gitRefComboBox.addActionListener(e -> updateGitImportButtonState());
            gitUrlField.getDocument().addDocumentListener(new SimpleDocumentListener() {
                @Override
                public void update(DocumentEvent e) {
                    remoteInfo = null;
                    requireNonNull(gitRefComboBox).setModel(new DefaultComboBoxModel<>()); // Clear
                    importButton.setEnabled(false);
                }
            });

            return gitPanel;
        }

        private void validateAndPopulateRefs() {
            String url = requireNonNull(gitUrlField).getText().trim();
            if (url.isEmpty()) {
                chrome.toolError("Git repository URL cannot be empty.", "Validation Error");
                return;
            }

            requireNonNull(validateGitRepoButton).setEnabled(false);

            chrome.getContextManager().submitBackgroundTask("Validating Git remote", () -> {
                try {
                    // Normalize URL for display and use
                    String normalizedUrl = url;
                    if (!normalizedUrl.endsWith(".git")) {
                        // Avoid adding .git to SSH URLs like git@github.com:user/repo
                        if (normalizedUrl.startsWith("http")) {
                            normalizedUrl += ".git";
                        }
                    }
                    final String finalUrl = normalizedUrl;
                    SwingUtilities.invokeLater(() -> requireNonNull(gitUrlField).setText(finalUrl));

                    var info = GitRepo.listRemoteRefs(finalUrl);
                    this.remoteInfo = info;

                    SwingUtilities.invokeLater(() -> {
                        var cb = requireNonNull(gitRefComboBox);
                        cb.removeAllItems();
                        info.branches().forEach(cb::addItem);
                        info.tags().forEach(cb::addItem);

                        String preferred = info.defaultBranch();
                        if (preferred == null && info.branches().contains("main")) {
                            preferred = "main";
                        }
                        if (preferred == null && info.branches().contains("master")) {
                            preferred = "master";
                        }

                        if (preferred != null) {
                            cb.setSelectedItem(preferred);
                        } else if (!info.branches().isEmpty()) {
                            cb.setSelectedIndex(0);
                        } else if (!info.tags().isEmpty()) {
                            cb.setSelectedIndex(info.branches().size());
                        }

                        updateGitImportButtonState();
                    });
                } catch (Exception ex) {
                    logger.warn("Failed to validate git repo {}", url, ex);
                    this.remoteInfo = null;
                    SwingUtilities.invokeLater(() -> {
                        chrome.toolError("Failed to access remote repository:\n" + ex.getMessage(), "Validation Failed");
                        requireNonNull(gitRefComboBox).removeAllItems();
                        importButton.setEnabled(false);
                    });
                } finally {
                    SwingUtilities.invokeLater(() -> requireNonNull(validateGitRepoButton).setEnabled(true));
                }
                return null;
            });
        }

        private void updateGitImportButtonState() {
            boolean isReady = remoteInfo != null && requireNonNull(gitRefComboBox).getSelectedItem() != null;
            importButton.setEnabled(isReady);
        }

        private FileSelectionPanel createFileSelectionPanel() {
            Predicate<File> filter;
            Future<List<Path>> candidates;
            String helpText;

            if (currentSourceType == SourceType.JAR) {
                assert chrome.getProject().getAnalyzerLanguages().contains(Language.JAVA) : "JAR source type should only be possible for Java projects";
                filter = file -> file.isDirectory() || file.getName().toLowerCase(Locale.ROOT).endsWith(".jar");
                candidates = chrome.getContextManager().submitBackgroundTask("Scanning for JAR files",
                                                                           () -> Language.JAVA.getDependencyCandidates(chrome.getProject()));
                helpText = "Ctrl+Space to autocomplete common dependency JARs.\nSelected JAR will be decompiled and its sources added to the project.";
            } else { // DIRECTORY
                filter = File::isDirectory;
                candidates = CompletableFuture.completedFuture(List.of());
                helpText = "Select a directory containing sources.\nSelected directory will be copied into the project.";
            }

            var fspConfig = new FileSelectionPanel.Config(chrome.getProject(),
                                                          true,
                                                          filter,
                                                          candidates,
                                                          false,
                                                          this::handleFspSingleFileConfirmed,
                                                          false,
                                                          helpText);

            currentFileSelectionPanel = new FileSelectionPanel(fspConfig);

            currentFileSelectionPanel.getFileInputComponent().getDocument().addDocumentListener(new SimpleDocumentListener() {
                @Override
                public void update(DocumentEvent e) {
                    onFspInputTextChange();
                }
            });
            return currentFileSelectionPanel;
        }

        private void onFspInputTextChange() {
            SwingUtilities.invokeLater(() -> {
                if (currentFileSelectionPanel == null) {
                    selectedBrokkFileForImport = null;
                    importButton.setEnabled(false);
                    return;
                }
                String text = currentFileSelectionPanel.getInputText();
                if (text.isEmpty()) {
                    selectedBrokkFileForImport = null;
                    importButton.setEnabled(false);
                    return;
                }

                Path path;
                try {
                    path = Paths.get(text);
                } catch (InvalidPathException e) {
                    selectedBrokkFileForImport = null;
                    importButton.setEnabled(false);
                    return;
                }

                Path resolvedPath = path.isAbsolute() ? path : chrome.getProject().getRoot().resolve(path);
                if (Files.exists(resolvedPath)) {
                    BrokkFile bf = resolvedPath.startsWith(chrome.getProject().getRoot())
                                   ? new io.github.jbellis.brokk.analyzer.ProjectFile(chrome.getProject().getRoot(), chrome.getProject().getRoot().relativize(resolvedPath))
                                   : new io.github.jbellis.brokk.analyzer.ExternalFile(resolvedPath);
                    updatePreviewAndButtonState(bf);
                } else {
                    selectedBrokkFileForImport = null;
                    importButton.setEnabled(false);
                }
            });
        }

        private void handleFspSingleFileConfirmed(BrokkFile file) {
            if (currentFileSelectionPanel != null) {
                currentFileSelectionPanel.setInputText(file.absPath().toString());
            }
            updatePreviewAndButtonState(file);
            if (importButton.isEnabled()) {
                performImport();
            }
        }

        private void updatePreviewAndButtonState(BrokkFile file) {
            selectedBrokkFileForImport = null;
            importButton.setEnabled(false);

            Path path = file.absPath();
            if (!Files.exists(path)) {
                return;
            }

            if (currentSourceType == SourceType.JAR) {
                if (Files.isRegularFile(path) && path.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    selectedBrokkFileForImport = file;
                    importButton.setEnabled(true);
                }
            } else { // DIRECTORY
                if (Files.isDirectory(path)) {
                    selectedBrokkFileForImport = file;
                    importButton.setEnabled(true);
                }
            }
        }

        private void performImport() {
            importButton.setEnabled(false);
            if (currentSourceType == SourceType.JAR || currentSourceType == SourceType.DIRECTORY) {
                performFileBasedImport();
            } else if (currentSourceType == SourceType.GIT) {
                performGitImport();
            }
        }

        private void performGitImport() {
            if (remoteInfo == null || requireNonNull(gitRefComboBox).getSelectedItem() == null) {
                JOptionPane.showMessageDialog(dialog, "No valid Git repository and branch/tag selected.", "Import Error", JOptionPane.ERROR_MESSAGE);
                importButton.setEnabled(true);
                return;
            }

            final String repoUrl = remoteInfo.url();
            final String selectedRef = (String) requireNonNull(gitRefComboBox).getSelectedItem();
            final String repoName = repoUrl.substring(repoUrl.lastIndexOf('/') + 1).replace(".git", "");
            final Path targetPath = dependenciesRoot.resolve(repoName);

            if (Files.exists(targetPath)) {
                int overwriteResponse = JOptionPane.showConfirmDialog(dialog,
                        "The destination '" + targetPath.getFileName() + "' already exists. Overwrite?",
                        "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (overwriteResponse == JOptionPane.NO_OPTION) {
                    importButton.setEnabled(true);
                    return;
                }
            }

            if (listener != null) {
                SwingUtilities.invokeLater(() -> listener.dependencyImportStarted(repoName));
            }
            dialog.dispose();

            chrome.getContextManager().submitBackgroundTask("Cloning repository: " + repoUrl, () -> {
                Path tempDir = null;
                try {
                    tempDir = Files.createTempDirectory("brokk-git-clone-");
                    Git.cloneRepository().setURI(repoUrl).setBranch(selectedRef)
                       .setDirectory(tempDir.toFile()).setDepth(1).setCloneSubmodules(false).call();

                    Path gitInternalDir = tempDir.resolve(".git");
                    if (Files.exists(gitInternalDir)) {
                        FileUtil.deleteRecursively(gitInternalDir);
                    }

                    Files.createDirectories(dependenciesRoot);
                    if (Files.exists(targetPath)) {
                        FileUtil.deleteRecursively(targetPath);
                    }
                    Files.move(tempDir, targetPath, StandardCopyOption.REPLACE_EXISTING);

                    SwingUtilities.invokeLater(() -> {
                        chrome.systemOutput("Repository " + repoName + " imported successfully. Reopen project to incorporate the new files.");
                        if (listener != null) {
                            listener.dependencyImportFinished(repoName);
                        }
                    });

                } catch (Exception ex) {
                    logger.error("Error cloning Git repository {}", repoUrl, ex);
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(dialog, "Error cloning repository: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        importButton.setEnabled(true);
                    });
                    if (tempDir != null && Files.exists(tempDir)) {
                        try {
                            FileUtil.deleteRecursively(tempDir);
                        } catch (IOException e) {
                            logger.error("Failed to delete temporary clone directory {}", tempDir, e);
                        }
                    }
                }
                return null;
            });
        }

        private void performFileBasedImport() {
            if (selectedBrokkFileForImport == null) {
                JOptionPane.showMessageDialog(dialog, "No valid source selected.", "Import Error", JOptionPane.ERROR_MESSAGE);
                importButton.setEnabled(true);
                return;
            }

            Path sourcePath = selectedBrokkFileForImport.absPath();
            String depName = sourcePath.getFileName().toString();

            if (listener != null) {
                SwingUtilities.invokeLater(() -> listener.dependencyImportStarted(depName));
            }
            dialog.dispose();

            if (currentSourceType == SourceType.JAR) {
                Decompiler.decompileJar(chrome,
                                        sourcePath,
                                        chrome.getContextManager()::submitBackgroundTask,
                                        () -> SwingUtilities.invokeLater(() -> {
                                            if (listener != null) listener.dependencyImportFinished(depName);
                                        }));
            } else { // DIRECTORY
                var project = chrome.getProject();
                if (project.getAnalyzerLanguages().stream().anyMatch(lang -> lang.isAnalyzed(project, sourcePath))) {
                    int proceedResponse = JOptionPane.showConfirmDialog(dialog, "The selected directory might already be part of the project's analyzed sources. Proceed?",
                                                                      "Confirm Import", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (proceedResponse == JOptionPane.NO_OPTION) {
                        importButton.setEnabled(true);
                        return;
                    }
                }

                Path targetPath = dependenciesRoot.resolve(sourcePath.getFileName());
                if (Files.exists(targetPath)) {
                    int overwriteResponse = JOptionPane.showConfirmDialog(dialog, "The destination '" + targetPath.getFileName() + "' already exists. Overwrite?",
                                                                          "Confirm Overwrite", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (overwriteResponse == JOptionPane.NO_OPTION) {
                        importButton.setEnabled(true);
                        return;
                    }
                }

                chrome.getContextManager().submitBackgroundTask("Copying directory: " + sourcePath.getFileName(), () -> {
                    try {
                        Files.createDirectories(dependenciesRoot);
                        if (Files.exists(targetPath)) {
                            FileUtil.deleteRecursively(targetPath);
                        }
                        List<String> allowedExtensions = project.getAnalyzerLanguages().stream()
                            .flatMap(lang -> lang.getExtensions().stream()).distinct().toList();
                        copyDirectoryRecursively(sourcePath, targetPath, allowedExtensions);
                        SwingUtilities.invokeLater(() -> {
                            chrome.systemOutput("Directory copied to " + targetPath + ". Reopen project to incorporate the new files.");
                            if (listener != null) listener.dependencyImportFinished(depName);
                        });
                    } catch (IOException ex) {
                        logger.error("Error copying directory {} to {}", sourcePath, targetPath, ex);
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(dialog, "Error copying directory: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                            importButton.setEnabled(true);
                        });
                    }
                    return null;
                });
            }
        }
    }

    private interface SimpleDocumentListener extends DocumentListener {
        void update(DocumentEvent e);
        @Override default void insertUpdate(DocumentEvent e) { update(e); }
        @Override default void removeUpdate(DocumentEvent e) { update(e); }
        @Override default void changedUpdate(DocumentEvent e) { update(e); }
    }

    private static void copyDirectoryRecursively(Path source, Path destination, List<String> allowedExtensions) throws IOException {
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
                    if (allowedExtensions.contains(fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT))) {
                        Files.copy(file, destination.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
