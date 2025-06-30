package io.github.jbellis.brokk.gui;

import com.google.common.base.Splitter;
import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

public class FileSelectionPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(FileSelectionPanel.class);

    public record Config(IProject project,
                         boolean allowExternalFiles,
                         Predicate<File> fileFilter,
                         Future<List<Path>> autocompleteCandidates,
                         boolean multiSelect,
                         Consumer<BrokkFile> onSingleFileConfirmed,
                         boolean includeProjectFilesInAutocomplete,
                         String customHintText)
    { }

    private final Config config;
    private final IProject project;
    private final Path rootPath;
    private final FileSelectionTree tree;
    private final JTextComponent fileInputComponent; // JTextField or JTextArea

    public FileSelectionPanel(Config config) {
        super(new BorderLayout(8, 8));
        this.config = config;
        this.project = config.project();
        this.rootPath = this.project.getRoot();

        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // 1. File Input Component (always JTextField)
        fileInputComponent = new JTextField(30);

        // 2. AutoCompletion
        // The provider needs access to project, allowExternalFiles, and autocompleteCandidates from config
        var provider = new FilePanelCompletionProvider(this.project,
                                                       config.autocompleteCandidates(),
                                                       config.allowExternalFiles(),
                                                       config.multiSelect(),
                                                       config.includeProjectFilesInAutocomplete());
        var autoCompletion = new AutoCompletion(provider);
        autoCompletion.setAutoActivationEnabled(false);
        autoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        autoCompletion.install(fileInputComponent);
        AutoCompleteUtil.bindCtrlEnter(autoCompletion, fileInputComponent); // Ctrl+Enter on input might imply confirmation

        // 3. FileTree
        tree = new FileSelectionTree(this.project, config.allowExternalFiles(), config.fileFilter());

        // Layout: Input at North, Tree at Center
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0)); // Space below input
        inputPanel.add(fileInputComponent, BorderLayout.CENTER);

        // Hints below input
        JPanel labelsPanel = new JPanel();
        labelsPanel.setLayout(new BoxLayout(labelsPanel, BoxLayout.PAGE_AXIS));

        if (!config.customHintText().isBlank()) {
            for (String line : Splitter.on('\n').splitToList(config.customHintText())) {
                labelsPanel.add(new JLabel(line));
            }
        }

        if (config.multiSelect() && config.includeProjectFilesInAutocomplete()) {
            labelsPanel.add(new JLabel("*/? to glob (project files only); ** to glob recursively"));
        }

        if (labelsPanel.getComponentCount() > 0) {
            labelsPanel.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0)); // Small top padding
            inputPanel.add(labelsPanel, BorderLayout.SOUTH);
        }
        add(inputPanel, BorderLayout.NORTH);

        JScrollPane treeScrollPane = new JScrollPane(tree);
        treeScrollPane.setPreferredSize(new Dimension(450, 150)); // Default size, can be overridden
        add(treeScrollPane, BorderLayout.CENTER);

        setupListeners();
    }

    private void setupListeners() {
        // Tree selection listener
        tree.addTreeSelectionListener(e -> {
            // Only proceed if the current AWT event represents a user action
            var awtEvent = EventQueue.getCurrentEvent();
            if (!(awtEvent instanceof MouseEvent) && !(awtEvent instanceof KeyEvent)) {
                return; // skip program-generated initial selection or other non-user-driven events
            }

            TreePath currentPath = e.getPath(); // Use currentPath to avoid clash with java.nio.file.Path
            if (currentPath != null && currentPath.getLastPathComponent() instanceof DefaultMutableTreeNode node) {
                Object userObject = node.getUserObject();
                if (userObject instanceof FileSelectionTree.FileTreeNode fileNodeUserObj) {
                    File file = fileNodeUserObj.getFile();
                    // A node in the tree is "selectable" if it passes the fileFilter.
                    // This allows selecting directories if the filter accepts them, even if they aren't leaves.
                    if (config.fileFilter().test(file)) {
                        updateFileInputFromTreeSelection(node, currentPath);
                    }
                } else if (!config.allowExternalFiles() && node.isLeaf() && userObject instanceof String) {
                    // This handles selection of leaf nodes in a project-files-only tree (not FileTreeNodes)
                    // Such a tree is built if allowExternalFiles is false.
                    // ImportDependencyDialog sets allowExternalFiles=true, so this branch is not typically hit for it.
                    updateFileInputFromTreeSelection(node, currentPath);
                }
            }
        });

        // Tree double-click listener
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        if (node.isLeaf()) {
                            tree.setSelectionPath(path); // Visually select
                            updateFileInputFromTreeSelection(node, path); // Update input field
                            if (!config.multiSelect()) {
                                List<BrokkFile> resolved = resolveAndGetSelectedFiles();
                                if (!resolved.isEmpty()) {
                                    config.onSingleFileConfirmed().accept(resolved.getFirst());
                                }
                            }
                        } else { // Double-clicked a directory
                            if (tree.isExpanded(path)) {
                                tree.collapsePath(path);
                            } else {
                                tree.expandPath(path);
                            }
                        }
                    }
                }
            }
        });

        // Optional: Listener for Enter key in fileInputComponent if not handled by Ctrl+Enter binding from AutoCompleteUtil
        // This depends on how dialogs want to use the panel. Usually, an OK button handles confirmation.
    }

    private void updateFileInputFromTreeSelection(DefaultMutableTreeNode node, TreePath treePath) {
        String filePathString = getPathStringFromNode(node, treePath);
        if (filePathString == null) return;

        if (config.multiSelect()) {
            appendToFileInput(filePathString);
        } else {
            fileInputComponent.setText(filePathString);
            fileInputComponent.setCaretPosition(fileInputComponent.getDocument().getLength());
        }
        fileInputComponent.requestFocusInWindow();
    }

    private @Nullable String getPathStringFromNode(DefaultMutableTreeNode node, TreePath path) {
        if (node.getUserObject() instanceof FileSelectionTree.FileTreeNode fileNode) {
            return fileNode.getFile().getAbsolutePath();
        } else if (!config.allowExternalFiles() && node.getUserObject() instanceof String) {
            // Repo file in repo-only mode - reconstruct relative path
            StringBuilder rel = new StringBuilder();
            for (int i = 1; i < path.getPathCount(); i++) { // Skip root
                if (i > 1) rel.append(File.separator);
                rel.append(path.getPathComponent(i).toString());
            }
            return rel.toString();
        }
        return null;
    }

    private void appendToFileInput(String filename) {
        String currentText = fileInputComponent.getText();
        String formattedFilename = quotePathIfNecessary(filename);
        String textToAppend = formattedFilename + " "; // Always add trailing space

        // Smart append: if current text ends with space, or current text is empty, just append.
        if (currentText.endsWith(" ") || currentText.isEmpty()) {
            fileInputComponent.setText(currentText + textToAppend);
        } else {
            // Insert with a leading space if needed
            fileInputComponent.setText(currentText + " " + textToAppend);
        }
        fileInputComponent.setCaretPosition(fileInputComponent.getDocument().getLength()); // Move caret to end
    }

    /**
     * Parses the current text in the input component, resolves paths and globs,
     * and returns a list of unique BrokkFile objects.
     * This is the primary method to get the resolved selection from the panel.
     */
    public List<BrokkFile> resolveAndGetSelectedFiles() {
        String inputText = fileInputComponent.getText().trim();
        if (inputText.isEmpty()) {
            return List.of();
        }

        List<String> filenames = config.multiSelect() ? splitQuotedString(inputText) : List.of(inputText);
        logger.debug("Input strings to resolve: {}", filenames);

        Map<Path, BrokkFile> uniqueFiles = new HashMap<>();
        for (String filenameToken : filenames) {
            if (filenameToken.isBlank()) continue;

            Path potentialPath = Path.of(filenameToken); // Path.of normalizes, good.

            if (potentialPath.isAbsolute()) {
                if (Files.isRegularFile(potentialPath)) { // Must be an actual file for external
                    if (potentialPath.startsWith(rootPath)) {
                        Path relPath = rootPath.relativize(potentialPath);
                        uniqueFiles.put(potentialPath, new ProjectFile(rootPath, relPath));
                    } else if (config.allowExternalFiles()) {
                        uniqueFiles.put(potentialPath, new ExternalFile(potentialPath));
                    } else {
                        logger.warn("Absolute path provided is outside the project and external files are not allowed: {}", filenameToken);
                    }
                } else if (config.allowExternalFiles() && Files.exists(potentialPath)) {
                    logger.warn("Absolute path provided is not a regular file (e.g. a directory): {}", filenameToken);
                } else {
                    logger.warn("Absolute path provided is not a regular file or does not exist: {}", filenameToken);
                }
            } else { // Relative path, assume project relative or glob
                var expanded = Completions.expandPath(project, filenameToken);
                for (BrokkFile file : expanded) {
                    uniqueFiles.put(file.absPath(), file);
                }
            }
        }

        List<BrokkFile> result = new ArrayList<>(uniqueFiles.values());

        // If external files are not allowed, keep only git-tracked project files.
        if (!config.allowExternalFiles()) {
            assert true : "project should not be null when external files are disallowed";
            var tracked = project.getAllFiles();
            //noinspection SuspiciousMethodCalls
            result = result.stream()
                           .filter(tracked::contains)
                           .toList();
        }

        logger.debug("Resolved unique files: {}", result);
        return result;
    }

    public void setInputText(String text) {
        fileInputComponent.setText(text);
        fileInputComponent.setCaretPosition(text.length());
    }

    public String getInputText() {
        return fileInputComponent.getText();
    }

    public JTextComponent getFileInputComponent() {
        return fileInputComponent;
    }

    // Helper: Quotes a path string if it contains spaces.
    private static String quotePathIfNecessary(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    // Helper: Splits a string by whitespace, respecting double quotes.
    private static List<String> splitQuotedString(String input) {
        List<String> tokens = new ArrayList<>();
        if (input.isBlank()) return tokens;

        StringBuilder currentToken = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                // Optional: decide if quotes themselves should be part of the token or stripped.
                // Current logic keeps them if they are not delimiters.
                // For file paths, quotes are usually for delimiting, not part of the name.
                // But if they are part of the token, expandPath and Path.of should handle it.
                // Let's assume for now that quotePathIfNecessary adds them, so they should be part of the token here
                // or stripped if expandPath doesn't expect them.
                // The original splitQuotedString didn't add quotes to tokens. Let's stick to that.
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!currentToken.isEmpty()) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0); // Reset
                }
            } else {
                currentToken.append(c);
            }
        }
        if (!currentToken.isEmpty()) {
            tokens.add(currentToken.toString());
        }
        return tokens;
    }


    /**
     * Custom CompletionProvider for files in FileSelectionPanel.
     * Handles project files, external paths, and different input component types.
     */
    private static class FilePanelCompletionProvider extends DefaultCompletionProvider {
        private final IProject project;
        private final Future<List<Path>> autocompleteCandidatesFuture;
        private final boolean allowExternalFiles;
        private final boolean multiSelectMode; // To determine how to get_already_entered_text
        private final boolean includeProjectFilesInAutocomplete;

        public FilePanelCompletionProvider(IProject project,
                                           Future<List<Path>> autocompleteCandidatesFuture,
                                           boolean allowExternalFiles,
                                           boolean multiSelectMode,
                                           boolean includeProjectFilesInAutocomplete)
        {
            super();
            this.project = project;
            this.autocompleteCandidatesFuture = autocompleteCandidatesFuture;
            this.allowExternalFiles = allowExternalFiles;
            this.multiSelectMode = multiSelectMode;
            this.includeProjectFilesInAutocomplete = includeProjectFilesInAutocomplete;
        }

        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            if (multiSelectMode) {
                // Use token extraction logic for JTextArea (multi-select)
                return getCurrentTokenTextForCompletion(comp);
            } else {
                // use the entire text
                return comp.getText();
            }
        }

        @Override
        public List<Completion> getCompletions(JTextComponent tc) {
            String pattern = getAlreadyEnteredText(tc); // This will be either full line or current token
            if (pattern.isEmpty() && !multiSelectMode) return List.of(); // For single, empty pattern = no completions
            if (pattern.trim().isEmpty() && multiSelectMode)
                return List.of(); // For multi, empty token = no completions


            Path potentialPath = null;
            boolean isAbsolutePattern = false;
            if (!pattern.trim().isEmpty()) { // Only try to parse if pattern is not just whitespace
                try {
                    potentialPath = Path.of(pattern); // Can throw InvalidPathException
                    isAbsolutePattern = potentialPath.isAbsolute();
                } catch (java.nio.file.InvalidPathException e) {
                    // Invalid syntax, treat as non-absolute. potentialPath remains null.
                    isAbsolutePattern = false;
                }
            }


            // 1. External file completions if pattern is absolute and external files allowed
            if (allowExternalFiles && isAbsolutePattern) { // check potentialPath not null
                return getAbsolutePathCompletions(castNonNull(potentialPath), pattern);
            }

            // 2. Project file and pre-defined candidate completions
            // (also fallback for non-absolute patterns or if external not allowed)
            List<Path> candidates;
            try {
                candidates = autocompleteCandidatesFuture.get(); // These are pre-vetted paths
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error getting autocomplete candidates", e);
                candidates = List.of(); // Or throw, but better to degrade gracefully
            }

            // If project exists, add its files to the candidates for completion
            // The original MultiFSD used a Future<Set<ProjectFile>>. Here we have Future<List<Path>> from config.
            // If project files need to be dynamically added, the `autocompleteCandidatesFuture` should provide them.
            // For simplicity, we assume `autocompleteCandidatesFuture` contains all relevant paths (project + external).
            // Or, we can merge if project exists:
            Set<Path> allCandidatePaths = new java.util.HashSet<>(candidates);
            if (this.includeProjectFilesInAutocomplete) {
                project.getRepo().getTrackedFiles().stream().map(ProjectFile::absPath).forEach(allCandidatePaths::add);
            }


            if (pattern.trim().isEmpty()) return List.of(); // Avoid processing empty patterns after merging

            // Score based on filename and full path.
            // Tie-breaking: project files (if identifiable) could be prioritized.
            // The original FSD/MFSD had specific logic for this.
            // Here, we simplify: if a path is within project root, it's a "project file" for scoring.
            var comps = Completions.scoreShortAndLong(
                    pattern.trim(), // Trim pattern for matching
                    allCandidatePaths,
                    p -> p.getFileName().toString(),
                    Path::toString,
                    p -> p.startsWith(project.getRoot()) ? 0 : 1, // Simple tie-breaker
                    this::createPathCompletion);

            // Sizing popup - needs AutoCompletion instance. This is tricky if provider is static.
            // This suggests AutoCompleteUtil.sizePopupWindows should be called outside, or AC passed in.
            // For now, let's assume the caller of provider might do this, or we omit it here.
            // If `autoCompletion` field is accessible (e.g., provider is inner class of panel), then it can be used.
            // This is not a static class, so it can access outer class members if needed.
            // The autoCompletion instance that this provider is registered with is what matters.

            return comps.stream().map(c -> (Completion) c).toList();
        }

        private ShorthandCompletion createPathCompletion(Path path) {
            Path absolutePath = path.toAbsolutePath().normalize();

            String pathForReplacement;
            if (!this.allowExternalFiles
                    && absolutePath.startsWith(this.project.getRoot())) {
                pathForReplacement = this.project.getRoot().relativize(absolutePath).toString();
            } else {
                pathForReplacement = absolutePath.toString();
            }

            String summaryPathString = pathForReplacement; // same as replacement target
            String displayFileName = Objects.requireNonNull(absolutePath.getFileName()).toString();

            String replacementText = multiSelectMode
                                     ? quotePathIfNecessary(pathForReplacement) + " "
                                     : pathForReplacement;

            return new ShorthandCompletion(this, displayFileName, replacementText, summaryPathString);
        }


        private List<Completion> getAbsolutePathCompletions(Path inputPath, String pattern) {
            List<Completion> pathCompletions = new ArrayList<>();
            try {
                Path parentDir;
                String filePrefix;
                boolean endsWithSeparator = pattern.endsWith(inputPath.getFileSystem().getSeparator());

                if (endsWithSeparator) {
                    parentDir = inputPath;
                    filePrefix = "";
                } else {
                    parentDir = inputPath.getParent();
                    if (parentDir == null && inputPath.getRoot() != null && inputPath.getNameCount() == 0) {
                        parentDir = inputPath.getRoot();
                        filePrefix = "";
                    } else if (parentDir != null) {
                        filePrefix = inputPath.getFileName() != null ? inputPath.getFileName().toString() : "";
                    } else {
                        parentDir = inputPath.getRoot(); // Fallback for e.g. "C:"
                        if (parentDir == null) return List.of();
                        filePrefix = "";
                    }
                }

                if (!Files.isDirectory(parentDir)) {
                    return List.of();
                }

                final String effectiveFilePrefix = filePrefix.toLowerCase(Locale.ROOT);
                try (var stream = Files.newDirectoryStream(parentDir, p -> p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(effectiveFilePrefix))) {
                    for (Path p : stream) {
                        String absolutePath = p.toAbsolutePath().toString();
                        String replacement = multiSelectMode ? quotePathIfNecessary(absolutePath) + " " : absolutePath;
                        String display = p.getFileName().toString();
                        if (Files.isDirectory(p)) {
                            display += p.getFileSystem().getSeparator(); // Indicate directory
                            replacement = multiSelectMode ? quotePathIfNecessary(absolutePath + p.getFileSystem().getSeparator()) + " " : absolutePath + p.getFileSystem().getSeparator();
                        }
                        pathCompletions.add(new org.fife.ui.autocomplete.BasicCompletion(this, replacement, display, absolutePath));
                    }
                } catch (java.io.IOException e) {
                    logger.debug("IOException listing directory for completion: {}", parentDir, e);
                }
            } catch (java.nio.file.InvalidPathException e) {
                logger.debug("Invalid path for completion: {}", pattern, e);
            }
            pathCompletions.sort(Comparator.comparing(Completion::getSummary)); // Sort by full path
            return pathCompletions;
        }

        /**
         * Extracts the current token for completion in a JTextComponent, respecting quotes.
         * Used for multi-select mode with JTextArea.
         */
        private String getCurrentTokenTextForCompletion(JTextComponent comp) {
            String text = comp.getText();
            int caretPos = comp.getCaretPosition();
            if (caretPos == 0) return "";

            int tokenStart = caretPos - 1;
            boolean inQuotes;

            // Look backwards from caret to find start of token
            // A token is delimited by whitespace, unless it's quoted.
            char[] chars = text.toCharArray();
            int quoteCountBeforeCaret = 0;
            for (int i = 0; i < caretPos; i++) {
                if (chars[i] == '"') quoteCountBeforeCaret++;
            }
            inQuotes = (quoteCountBeforeCaret % 2) != 0;


            while (tokenStart >= 0) {
                char c = chars[tokenStart];
                if (c == '"') {
                    // If we encounter a quote, the state of inQuotes flips for chars before it
                    inQuotes = !inQuotes;
                    if (!inQuotes) { // This quote closes a quoted segment scan backwards from here
                        // This logic is complex with quote handling inside tokens.
                        // A simpler rule: if current char is quote, break if it *starts* the token.
                        // Or, if it's whitespace AND we are not in quotes, it's a delimiter.
                    }
                } else if (Character.isWhitespace(c)) {
                    if (!inQuotes) { // Whitespace outside quotes delimits token
                        tokenStart++; // The token starts after this whitespace
                        break;
                    }
                }
                if (tokenStart == 0) break; // Reached beginning of text
                tokenStart--;
            }
            if (tokenStart < 0) tokenStart = 0; // Ensure not negative

            // If token starts with a quote, and caret is effectively inside, pattern is from after quote
            String currentToken = text.substring(tokenStart, caretPos);
            if (currentToken.startsWith("\"") && !(text.substring(tokenStart, caretPos).chars().filter(ch -> ch == '"').count() % 2 == 0)) {
                // if token starts with quote and we are "in" that quote block for completion
                return currentToken.substring(1);
            }

            return currentToken;
        }
    }
}
