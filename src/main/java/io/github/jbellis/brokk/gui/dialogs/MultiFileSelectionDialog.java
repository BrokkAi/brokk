package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.gui.AutoCompleteUtil;
import io.github.jbellis.brokk.gui.FileSelectionPanel; // Import new panel
import io.github.jbellis.brokk.util.LoggingExecutorService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * A selection dialog that presents a tabbed interface for selecting
 * MULTIPLE files and/or MULTIPLE classes.
 */
public class MultiFileSelectionDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(MultiFileSelectionDialog.class);

    public enum SelectionMode {
        FILES, CLASSES
    }

    public record Selection(@Nullable List<BrokkFile> files, @Nullable List<CodeUnit> classes) {
        public boolean isEmpty() {
            return (files == null || files.isEmpty()) && (classes == null || classes.isEmpty());
        }
    }

    private final IProject project;
    private final AnalyzerWrapper analyzerWrapper;

    // UI Components - Files Tab
    @Nullable private FileSelectionPanel fileSelectionPanel; // Use the new panel

    // UI Components - Classes Tab
    @Nullable private JTextArea classInput; // Keep for classes tab
    @Nullable private AutoCompletion classAutoCompletion; // Keep for classes tab

    // Common UI Components
    private JTabbedPane tabbedPane;
    private final JButton okButton;
    private final JButton cancelButton;

    @Nullable private Selection selectionResult = null;
    private boolean confirmed = false;
    private final ExecutorService backgroundExecutor;

    /**
     * Constructor for multiple source selection.
     *
     * @param parent             Parent frame.
     * @param contextManager     The application's ContextManager.
     * @param title              Dialog title.
     * @param allowExternalFiles If true, shows the full file system (Files tab only).
     * @param completableFiles   Future for set of project files for file completion.
     * @param modes              Set of allowed selection modes.
     */
    public MultiFileSelectionDialog(Frame parent, ContextManager contextManager, String title,
                                    boolean allowExternalFiles, Future<Set<ProjectFile>> completableFiles,
                                    Set<SelectionMode> modes) {
        super(parent, title, true);
        assert parent != null;
        assert contextManager != null;
        assert title != null;
        assert completableFiles != null;
        assert modes != null && !modes.isEmpty();

        this.project = contextManager.getProject();
        this.analyzerWrapper = contextManager.getAnalyzerWrapper();

        this.backgroundExecutor = new LoggingExecutorService(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MultiFileSelectionDialog-BG");
            t.setDaemon(true);
            return t;
        }), e -> logger.error("Unexpected error", e));

        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        tabbedPane = new JTabbedPane();

        // --- Create Files Tab (if requested) ---
        if (modes.contains(SelectionMode.FILES)) {
            // Convert Future<Set<ProjectFile>> to Future<List<Path>> for FileSelectionPanel
            Future<List<Path>> autocompletePathsForPanel = convertProjectFilesToPaths(completableFiles);

            var panelConfig = new FileSelectionPanel.Config(
                    project,
                    allowExternalFiles,
                    f -> true, // Default filter, can be customized if needed
                    autocompletePathsForPanel,
                    true, // multiSelect = true
                    bf -> {},  // No-op single file confirmed action for multi-select panel
                    true, // includeProjectFilesInAutocomplete
                    buildFilesTabHintText(allowExternalFiles)
            );
            fileSelectionPanel = new FileSelectionPanel(panelConfig);
            fileSelectionPanel.setName("FilesPanel"); // For focusing logic
            tabbedPane.addTab("Files", fileSelectionPanel);
        }

        // --- Create Classes Tab (if requested) ---
        if (modes.contains(SelectionMode.CLASSES) && analyzerWrapper.isCpg()) {
            tabbedPane.addTab("Classes", createClassSelectionPanel());
        }

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        // --- Buttons ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        okButton = new JButton("OK");
        okButton.addActionListener(e -> doOk());
        cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> {
            confirmed = false;
            dispose();
        });
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // --- Keyboard actions & Focus ---
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        getRootPane().registerKeyboardAction(e -> {
            confirmed = false;
            selectionResult = null;
            dispose();
        }, escapeKeyStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(okButton);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Component selectedComponent = tabbedPane.getSelectedComponent();
                    if (selectedComponent instanceof FileSelectionPanel fsp) {
                        fsp.getFileInputComponent().requestFocusInWindow();
                    } else if (selectedComponent != null && "ClassesPanel".equals(selectedComponent.getName()) && classInput != null) {
                        classInput.requestFocusInWindow();
                    }
                });
            }

            @Override
            public void windowClosed(WindowEvent e) {
                backgroundExecutor.shutdown();
            }
        });

        setContentPane(mainPanel);
        pack();
        setSize(Math.max(600, getWidth()), Math.max(550, getHeight()));
        setLocationRelativeTo(parent);
    }

    private String buildFilesTabHintText(boolean allowExternalFiles) {
        var sb = new StringBuilder("Ctrl-space to autocomplete project files.");
        if (allowExternalFiles) {
            sb.append(" External files may be selected from the tree.");
        }
        return sb.toString();
    }

    private Future<List<Path>> convertProjectFilesToPaths(Future<Set<ProjectFile>> projectFilesFuture) {
        // This conversion happens when the Future completes.
        // We return a new CompletableFuture that will complete with the List<Path>.
        return CompletableFuture.supplyAsync(() -> {
            try {
                return projectFilesFuture.get().stream()
                                         .map(ProjectFile::absPath)
                                         .collect(Collectors.toList());
            } catch (InterruptedException | ExecutionException e) {
                logger.error("Error converting project files to paths for autocompletion", e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return List.of(); // Or throw a runtime exception if preferred
            }
        }, backgroundExecutor); // Use an executor to avoid blocking GUI thread if original future is slow
    }


    /**
     * Creates the panel containing components for class selection. (Largely unchanged)
     */
    private JPanel createClassSelectionPanel() {
        JPanel classesPanel = new JPanel(new BorderLayout(8, 8));
        classesPanel.setName("ClassesPanel");

        classInput = new JTextArea(3, 30);
        classInput.setLineWrap(true);
        classInput.setWrapStyleWord(true);

        var provider = new SymbolCompletionProvider(analyzerWrapper, backgroundExecutor);
        classAutoCompletion = new AutoCompletion(provider);
        classAutoCompletion.setAutoActivationEnabled(false);
        classAutoCompletion.setTriggerKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK));
        classAutoCompletion.install(classInput);
        AutoCompleteUtil.bindCtrlEnter(classAutoCompletion, classInput);
        classInput.setToolTipText("Enter fully qualified class names (space-separated). Ctrl+Space for autocomplete. Enter/Ctrl+Enter to confirm.");

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        inputPanel.add(new JScrollPane(classInput), BorderLayout.CENTER);

        JPanel labelsPanel = new JPanel(new GridLayout(1, 1));
        labelsPanel.add(new JLabel("Ctrl-space to autocomplete class names."));
        inputPanel.add(labelsPanel, BorderLayout.SOUTH);
        classesPanel.add(inputPanel, BorderLayout.NORTH);
        classesPanel.add(new Box.Filler(new Dimension(0,0), new Dimension(500,350), new Dimension(Short.MAX_VALUE, Short.MAX_VALUE)), BorderLayout.CENTER);
        return classesPanel;
    }


    private void doOk() {
        Component selectedComponent = tabbedPane.getSelectedComponent();
        if (selectedComponent == null) {
            logger.warn("No tab selected in MultiFileSelectionDialog");
            confirmed = false;
            selectionResult = null;
            dispose();
            return;
        }

        String componentName = selectedComponent.getName();

        if ("FilesPanel".equals(componentName) && fileSelectionPanel != null) {
            List<BrokkFile> filesResult = fileSelectionPanel.resolveAndGetSelectedFiles();
            selectionResult = new Selection((filesResult != null && !filesResult.isEmpty()) ? List.copyOf(filesResult) : null, null);
            confirmed = !selectionResult.isEmpty();
            if (!confirmed) selectionResult = null;
            dispose();
            return;
        }

        if ("ClassesPanel".equals(componentName) && classInput != null) {
            final String typedClasses = classInput.getText().trim();
            if (!typedClasses.isEmpty()) {
                okButton.setEnabled(false);
                cancelButton.setEnabled(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

                backgroundExecutor.submit(() -> {
                    List<CodeUnit> resolvedClasses = null;
                    Exception backgroundException = null;
                    try {
                        resolvedClasses = parseAndResolveClasses(typedClasses);
                    } catch (Exception ex) {
                        logger.error("Error resolving classes in background", ex);
                        backgroundException = ex;
                    }

                    final List<CodeUnit> finalResolvedClasses = resolvedClasses;
                    final Exception finalBackgroundException = backgroundException;

                    SwingUtilities.invokeLater(() -> {
                        okButton.setEnabled(true);
                        cancelButton.setEnabled(true);
                        setCursor(Cursor.getDefaultCursor());

                        if (finalBackgroundException != null) {
                            JOptionPane.showMessageDialog(MultiFileSelectionDialog.this,
                                                          "Error resolving classes: " + finalBackgroundException.getMessage(),
                                                          "Resolution Error", JOptionPane.ERROR_MESSAGE);
                            selectionResult = null;
                            confirmed = false;
                        } else {
                            List<CodeUnit> finalClasses = (finalResolvedClasses != null && !finalResolvedClasses.isEmpty()) ? List.copyOf(finalResolvedClasses) : null;
                            selectionResult = new Selection(null, finalClasses);
                            confirmed = !selectionResult.isEmpty();
                            if (!confirmed) selectionResult = null;
                        }
                        dispose();
                    });
                    return null;
                });
                return; // Dialog stays open for async class parsing
            } else {
                selectionResult = new Selection(null, null);
                confirmed = false;
                dispose();
                return;
            }
        }

        logger.warn("Unknown or unexpected tab selected, or input component missing: {}", componentName);
        selectionResult = null;
        confirmed = false;
        dispose();
    }

    // parseAndResolveFiles is now handled by FileSelectionPanel.resolveAndGetSelectedFiles()

    /**
     * Parses space-separated class names and resolves them to CodeUnit objects using the IAnalyzer.
     */
    private List<CodeUnit> parseAndResolveClasses(String inputText) {
        // This method remains as it's specific to class resolution logic
        List<String> classNames = splitQuotedString(inputText);
        logger.debug("Raw class names parsed: {}", classNames);

        List<CodeUnit> resolvedClasses = new ArrayList<>();
        IAnalyzer activeAnalyzer;
        try {
            activeAnalyzer = analyzerWrapper.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while getting analyzer for class resolution", e);
            return List.of();
        }

        if (activeAnalyzer == null || !activeAnalyzer.isCpg()) {
            logger.warn("Cannot resolve classes: Analyzer is not available or not a CPG analyzer.");
            return List.of();
        }

        Map<String, CodeUnit> knownClasses = Completions.completeSymbols("", activeAnalyzer)
                .stream()
                .filter(cu -> cu.kind() == CodeUnitType.CLASS)
                .collect(Collectors.toMap(CodeUnit::fqName, cu -> cu, (cu1, cu2) -> cu1));

        for (String className : classNames) {
            if (className.isBlank()) continue;
            CodeUnit found = knownClasses.get(className);
            if (found != null) {
                resolvedClasses.add(found);
            } else {
                logger.warn("Could not resolve class name: {}", className);
            }
        }
        logger.debug("Resolved unique classes: {}", resolvedClasses.stream().map(CodeUnit::fqName).toList());
        return resolvedClasses;
    }

    /**
     * Splits a string by whitespace, respecting double quotes.
     * This could be moved to a common utility class if used elsewhere.
     */
    private List<String> splitQuotedString(String input) {
        List<String> tokens = new ArrayList<>();
        if (input.isBlank()) return tokens;
        StringBuilder currentToken = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!currentToken.isEmpty()) {
                    tokens.add(currentToken.toString());
                    currentToken.setLength(0);
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


    public boolean isConfirmed() {
        return confirmed;
    }

    @Nullable
    public Selection getSelection() {
        return selectionResult;
    }

    // getCurrentTokenText is now handled by FileSelectionPanel's internal completion provider for its JTextArea
    // FileCompletionProvider (inner class for files) is now part of FileSelectionPanel.

    /**
     * Custom CompletionProvider for Java classes using the IAnalyzer. (Largely unchanged)
     * Overrides getAlreadyEnteredText for multi-class input area.
     * Loads class completions in the background using a provided ExecutorService.
     */
    public class SymbolCompletionProvider extends DefaultCompletionProvider {
        private final AnalyzerWrapper analyzerWrapperField; // Renamed to avoid conflict
        private final Future<List<CodeUnit>> completionsFuture;

        public SymbolCompletionProvider(AnalyzerWrapper analyzerWrapperParam, ExecutorService backgroundExecutor) {
            super();
            assert analyzerWrapperParam != null;
            assert backgroundExecutor != null;
            this.analyzerWrapperField = analyzerWrapperParam;

            this.completionsFuture = backgroundExecutor.submit(() -> {
                try {
                    return Completions.completeSymbols("", analyzerWrapperField.get())
                            .stream()
                            .filter(c -> c.kind() == CodeUnitType.CLASS)
                            .toList();
                } catch (Exception e) {
                    logger.error("Error loading symbol completions in background", e);
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    return Collections.emptyList();
                }
            });
        }

        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            // For class input, which is always JTextArea, we need token extraction
            return getCurrentTokenTextForCompletion(comp);
        }
        
        // Helper method for token extraction, similar to what was in FileSelectionPanel
        private String getCurrentTokenTextForCompletion(JTextComponent comp) {
            String text = comp.getText();
            int caretPos = comp.getCaretPosition();
            if (caretPos == 0) return "";

            int tokenStart = caretPos - 1;
            // boolean inQuotes = false; // Unused variable
            char[] chars = text.toCharArray();
            int quoteCountBeforeCaret = 0;
            for (int i = 0; i < caretPos; i++) {
                if (chars[i] == '"') quoteCountBeforeCaret++;
            }
            boolean currentlyInQuotes = (quoteCountBeforeCaret % 2) != 0; // Renamed for clarity

            while (tokenStart >= 0) {
                char c = chars[tokenStart];
                if (c == '"') {
                    currentlyInQuotes = !currentlyInQuotes; // This logic seems to be for state *before* current char
                } else if (Character.isWhitespace(c) && !currentlyInQuotes) {
                    tokenStart++;
                    break;
                }
                if (tokenStart == 0) break;
                tokenStart--;
            }
            if (tokenStart < 0) tokenStart = 0;

            String currentToken = text.substring(tokenStart, caretPos);
            // Simplified logic: if the token starts with a quote and we are effectively inside quotes (odd number of quotes from tokenStart to caretPos)
            // then the pattern for completion is what's after the opening quote.
            // This is a common behavior for completion within quoted strings.
            if (currentToken.startsWith("\"")) {
                long quotesInToken = currentToken.chars().filter(ch -> ch == '"').count();
                if (quotesInToken % 2 != 0) { // Odd number of quotes means caret is likely inside an unterminated quote
                    return currentToken.substring(1);
                }
            }
            return currentToken;
        }


        @Override
        public List<Completion> getCompletions(JTextComponent comp) {
            String pattern = getAlreadyEnteredText(comp).trim();
            if (pattern.isEmpty()) return List.of();

            List<CodeUnit> availableCompletions;
            try {
                availableCompletions = completionsFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.debug("Error getting symbol completions", e);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return List.of();
            }

            var matches = Completions.scoreShortAndLong(pattern,
                                                        availableCompletions,
                                                        CodeUnit::identifier,
                                                        CodeUnit::fqName,
                                                        cu -> 0,
                                                        this::createClassCompletion);

            if (classAutoCompletion != null) { // classAutoCompletion can be null if Classes tab is not created
                AutoCompleteUtil.sizePopupWindows(classAutoCompletion, comp, matches);
            }
            return matches.stream().map(c -> (Completion) c).toList();
        }

        private ShorthandCompletion createClassCompletion(CodeUnit codeUnit) {
            String fqn = codeUnit.fqName();
            String shortName = codeUnit.shortName();
            String replacement = fqn + " ";
            return new ShorthandCompletion(this, shortName, replacement, fqn);
        }
    }
}
