package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.FileSelectionPanel;
import io.github.jbellis.brokk.util.Decompiler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

import static java.util.Objects.requireNonNull;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ImportDependencyDialog {
    private static final Logger logger = LogManager.getLogger(ImportDependencyDialog.class);

    private enum SourceType { JAR, DIRECTORY, GIT }

    public static void show(Chrome chrome) {
        assert SwingUtilities.isEventDispatchThread() : "Dialogs should be created on the EDT";
        new DialogHelper(chrome).buildAndShow();
    }

    private static class DialogHelper {
        private final Chrome chrome;
        private JDialog dialog = new JDialog();
        @Nullable private JRadioButton jarRadioButton;
        @Nullable private JRadioButton dirRadioButton;
        @Nullable private JRadioButton gitRadioButton;

        private JPanel contentPanel = new JPanel(new BorderLayout());
        private JTextArea previewArea = new JTextArea();
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


        DialogHelper(Chrome chrome) {
            this.chrome = chrome;
            this.dependenciesRoot = chrome.getProject().getRoot().resolve(".brokk").resolve("dependencies");
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
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.gridwidth = 2;
            gbc.fill = GridBagConstraints.BOTH;
            gbc.weightx = 1.0;
            gbc.weighty = 1.0;
            contentPanel.setPreferredSize(new Dimension(500, 250));
            mainPanel.add(contentPanel, gbc);

            // --- Preview Area ---
            gbc.gridy = 2;
            gbc.weighty = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            mainPanel.add(new JLabel("Preview:"), gbc);

            gbc.gridy = 3;
            gbc.weighty = 0.5;
            gbc.fill = GridBagConstraints.BOTH;
            previewArea = new JTextArea(5, 40);
            previewArea.setEditable(false);
            previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane previewScrollPane = new JScrollPane(previewArea);
            previewScrollPane.setMinimumSize(new Dimension(100, 80));
            mainPanel.add(previewScrollPane, gbc);

            dialog.add(mainPanel, BorderLayout.CENTER);

            // --- Buttons ---
            importButton = new JButton("Import");
            importButton.setEnabled(false);
            importButton.addActionListener(e -> performImport());
            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> dialog.dispose());

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(importButton);
            buttonPanel.add(cancelButton);
            dialog.add(buttonPanel, BorderLayout.SOUTH);

            updateContentPanel();

            dialog.setMinimumSize(new Dimension(600, 500));
            dialog.pack();
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
        }

        private void updateSourceType(SourceType newType) {
            if (currentSourceType == newType) return;
            currentSourceType = newType;
            selectedBrokkFileForImport = null;
            remoteInfo = null;
            previewArea.setText("");
            importButton.setEnabled(false);
            updateContentPanel();
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
            gitPanel = new JPanel(new BorderLayout(5, 5));
            gitUrlField = new JTextField();
            validateGitRepoButton = new JButton("Validate & List Branches/Tags");
            gitRefComboBox = new JComboBox<>();

            JPanel topPanel = new JPanel(new BorderLayout(5, 5));
            topPanel.add(new JLabel("Repo URL:"), BorderLayout.WEST);
            topPanel.add(gitUrlField, BorderLayout.CENTER);
            topPanel.add(validateGitRepoButton, BorderLayout.EAST);

            JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
            bottomPanel.add(new JLabel("Branch/Tag:"), BorderLayout.WEST);
            bottomPanel.add(gitRefComboBox, BorderLayout.CENTER);

            gitPanel.add(topPanel, BorderLayout.NORTH);
            gitPanel.add(bottomPanel, BorderLayout.CENTER);
            gitPanel.setBorder(new EmptyBorder(5, 5, 5, 5));

            validateGitRepoButton.addActionListener(e -> validateAndPopulateRefs());
            gitRefComboBox.addActionListener(e -> updateGitImportButtonState());
            gitUrlField.getDocument().addDocumentListener(new SimpleDocumentListener() {
                @Override
                public void update(DocumentEvent e) {
                    remoteInfo = null;
                    requireNonNull(gitRefComboBox).setModel(new DefaultComboBoxModel<>()); // Clear
                    importButton.setEnabled(false);
                    previewArea.setText("");
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
            previewArea.setText("Validating remote: " + url + "...");

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

                        if (info.defaultBranch() != null && info.branches().contains(info.defaultBranch())) {
                            cb.setSelectedItem(info.defaultBranch());
                        } else if (!info.branches().isEmpty()) {
                            cb.setSelectedIndex(0);
                        } else if (!info.tags().isEmpty()) {
                            cb.setSelectedIndex(info.branches().size());
                        }

                        previewArea.setText(String.format("Found %d branches and %d tags.", info.branches().size(), info.tags().size()));
                        updateGitImportButtonState();
                    });
                } catch (Exception ex) {
                    logger.warn("Failed to validate git repo {}", url, ex);
                    this.remoteInfo = null;
                    SwingUtilities.invokeLater(() -> {
                        chrome.toolError("Failed to access remote repository:\n" + ex.getMessage(), "Validation Failed");
                        previewArea.setText("Validation failed for: " + url);
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

            var fspConfig = new FileSelectionPanel.Config(
                    chrome.getProject(),
                    true,
                    filter,
                    candidates,
                    false,
                    this::handleFspSingleFileConfirmed,
                    false,
                    helpText
            );

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
                    previewArea.setText("");
                    importButton.setEnabled(false);
                    return;
                }
                String text = currentFileSelectionPanel.getInputText();
                if (text.isEmpty()) {
                    selectedBrokkFileForImport = null;
                    previewArea.setText("");
                    importButton.setEnabled(false);
                    return;
                }

                Path path;
                try {
                    path = Paths.get(text);
                } catch (InvalidPathException e) {
                    selectedBrokkFileForImport = null;
                    previewArea.setText("");
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
                    previewArea.setText("");
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
            previewArea.setText("");

            Path path = file.absPath();
            if (!Files.exists(path)) {
                return;
            }

            if (currentSourceType == SourceType.JAR) {
                if (Files.isRegularFile(path) && path.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    selectedBrokkFileForImport = file;
                    previewArea.setText(generateJarPreviewText(path));
                    importButton.setEnabled(true);
                } else {
                    previewArea.setText("Selected item is not a valid JAR file: " + path.getFileName());
                }
            } else { // DIRECTORY
                if (Files.isDirectory(path)) {
                    selectedBrokkFileForImport = file;
                    previewArea.setText(generateDirectoryPreviewText(path));
                    importButton.setEnabled(true);
                } else {
                    previewArea.setText("Selected item is not a valid directory: " + path.getFileName());
                }
            }
            previewArea.setCaretPosition(0);
        }

        private String generateJarPreviewText(Path jarPath) {
            Map<String, Integer> classCountsByPackage = new HashMap<>();
            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                        String className = entry.getName().replace('/', '.').substring(0, entry.getName().length() - ".class".length());
                        int lastDot = className.lastIndexOf('.');
                        String packageName = (lastDot == -1) ? "(default package)" : className.substring(0, lastDot);
                        classCountsByPackage.merge(packageName, 1, Integer::sum);
                    }
                }
            } catch (IOException e) {
                logger.warn("Error reading JAR for preview: {}", jarPath, e);
                return "Error reading JAR: " + e.getMessage();
            }
            if (classCountsByPackage.isEmpty()) return "No classes found in JAR.";
            return classCountsByPackage.entrySet().stream()
                                       .sorted(Map.Entry.comparingByKey())
                                       .map(e -> e.getKey() + ": " + e.getValue() + " class(es)")
                                       .collect(Collectors.joining("\n"));
        }

        private String generateDirectoryPreviewText(Path dirPath) {
            List<String> extensions = chrome.getProject().getAnalyzerLanguages().stream()
                                            .flatMap(lang -> lang.getExtensions().stream())
                                            .distinct().toList();
            Map<String, Long> counts = new TreeMap<>();
            long rootFileCount = 0;
            try (Stream<Path> filesInRoot = Files.list(dirPath).filter(Files::isRegularFile)) {
                rootFileCount = filesInRoot.filter(p -> {
                    String fileName = p.getFileName().toString();
                    int lastDot = fileName.lastIndexOf('.');
                    if (lastDot > 0 && lastDot < fileName.length() - 1) {
                        return extensions.contains(fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT));
                    }
                    return false;
                }).count();
            } catch (IOException e) {
                logger.warn("Error listing files in directory root for preview: {}", dirPath, e);
            }
            if (rootFileCount > 0) {
                counts.put("(Files in " + dirPath.getFileName() + ")", rootFileCount);
            }

            try (Stream<Path> subdirs = Files.list(dirPath).filter(Files::isDirectory)) {
                for (Path subdir : subdirs.toList()) {
                    try (Stream<Path> allFilesRecursive = Files.walk(subdir)) {
                        long count = allFilesRecursive.filter(Files::isRegularFile).filter(p -> {
                            String fileName = p.getFileName().toString();
                            int lastDot = fileName.lastIndexOf('.');
                            if (lastDot > 0 && lastDot < fileName.length() - 1) {
                                return extensions.contains(fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT));
                            }
                            return false;
                        }).count();
                        if (count > 0) {
                            counts.put(subdir.getFileName().toString(), count);
                        }
                    } catch (IOException e) {
                         logger.warn("Error walking subdirectory for preview: {}", subdir, e);
                    }
                }
            } catch (IOException e) {
                logger.warn("Error listing subdirectories for preview: {}", dirPath, e);
                if (counts.isEmpty() && rootFileCount == 0) return "Error reading directory: " + e.getMessage();
            }

            if (counts.isEmpty()) return "No relevant files found for project language(s) (" + String.join(", ", extensions) + ").";

            String languagesDisplay = chrome.getProject().getAnalyzerLanguages().stream()
                                            .map(l -> l.name().toLowerCase(Locale.ROOT))
                                            .sorted().collect(Collectors.joining("/"));
            return counts.entrySet().stream()
                         .map(e -> e.getKey() + ": " + e.getValue() + " " + languagesDisplay + " file(s)")
                         .collect(Collectors.joining("\n"));
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

            chrome.getContextManager().submitBackgroundTask("Cloning repository: " + repoUrl, () -> {
                Path tempDir = null;
                try {
                    tempDir = Files.createTempDirectory("brokk-git-clone-");
                    Git.cloneRepository().setURI(repoUrl).setBranch(selectedRef)
                       .setDirectory(tempDir.toFile()).setDepth(1).setCloneSubmodules(false).call();

                    Path gitInternalDir = tempDir.resolve(".git");
                    if (Files.exists(gitInternalDir)) {
                        deleteRecursively(gitInternalDir);
                    }

                    Files.createDirectories(dependenciesRoot);
                    if (Files.exists(targetPath)) {
                        deleteRecursively(targetPath);
                    }
                    Files.move(tempDir, targetPath, StandardCopyOption.REPLACE_EXISTING);

                    SwingUtilities.invokeLater(() -> {
                        chrome.systemOutput("Repository " + repoName + " imported successfully. Reopen project to incorporate the new files.");
                        dialog.dispose();
                    });

                } catch (Exception ex) {
                    logger.error("Error cloning Git repository {}", repoUrl, ex);
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(dialog, "Error cloning repository: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        importButton.setEnabled(true);
                    });
                    if (tempDir != null && Files.exists(tempDir)) {
                        try {
                            deleteRecursively(tempDir);
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
            if (currentSourceType == SourceType.JAR) {
                Decompiler.decompileJar(chrome, sourcePath, chrome.getContextManager()::submitBackgroundTask);
                dialog.dispose();
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
                            deleteRecursively(targetPath);
                        }
                        List<String> allowedExtensions = project.getAnalyzerLanguages().stream()
                            .flatMap(lang -> lang.getExtensions().stream()).distinct().toList();
                        copyDirectoryRecursively(sourcePath, targetPath, allowedExtensions);
                        SwingUtilities.invokeLater(() -> {
                            chrome.systemOutput("Directory copied to " + targetPath + ". Reopen project to incorporate the new files.");
                            dialog.dispose();
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

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    logger.warn("Failed to delete path during recursive cleanup: {}", p, e);
                }
            });
        }
    }
}
