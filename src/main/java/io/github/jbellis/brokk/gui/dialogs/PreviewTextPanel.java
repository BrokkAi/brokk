package io.github.jbellis.brokk.gui.dialogs;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.EditBlock;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.VoiceInputButton;
import io.github.jbellis.brokk.gui.search.GenericSearchBar;
import io.github.jbellis.brokk.gui.search.RTextAreaSearchableComponent;
import io.github.jbellis.brokk.gui.util.KeyboardShortcutUtil;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/**
 * Displays text (typically code) using an {@link org.fife.ui.rsyntaxtextarea.RSyntaxTextArea}
 * with syntax highlighting, search, and AI-assisted editing via "Quick Edit".
 *
 * <p>Supports editing {@link io.github.jbellis.brokk.analyzer.ProjectFile} content and capturing revisions.</p>
 */
public class PreviewTextPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(PreviewTextPanel.class);
    private final PreviewTextArea textArea;
    private final GenericSearchBar searchBar;
    @Nullable private JButton editButton;
    @Nullable private JButton captureButton;
    @Nullable private JButton saveButton;
    private final ContextManager contextManager;

    // Nullable
    @Nullable private final ProjectFile file;
    private final String contentBeforeSave;
    private List<ChatMessage> quickEditMessages = new ArrayList<>();
    @Nullable private final Future<Set<CodeUnit>> fileDeclarations;
    private final List<JComponent> dynamicMenuItems = new ArrayList<>(); // For usage capture items

    public PreviewTextPanel(ContextManager contextManager,
                            @Nullable ProjectFile file,
                            String content,
                            @Nullable String syntaxStyle,
                            GuiTheme guiTheme,
                            @Nullable ContextFragment fragment)
    {
        super(new BorderLayout());
        assert contextManager != null;
        assert guiTheme != null;

        this.contextManager = contextManager;
        this.file = file;
        this.contentBeforeSave = content; // Store initial content

        // === Text area with syntax highlighting ===
        // Initialize textArea *before* search bar that references it
        textArea = new PreviewTextArea(content, syntaxStyle, file != null); // syntaxStyle can be null here

        // === Top search/action bar ===
        var topPanel = new JPanel(new BorderLayout(8, 4));
        searchBar = new GenericSearchBar(RTextAreaSearchableComponent.wrap(textArea));
        topPanel.add(searchBar, BorderLayout.CENTER);

        // Button panel for actions on the right
        JPanel actionButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0)); // Use FlowLayout, add some spacing

        // Initialize buttons that might not be created
        this.saveButton = null;
        this.captureButton = null;
        this.editButton = null;

        // Save button (conditionally added for ProjectFile)
        if (file != null) {
            // Use the field saveButton directly
            saveButton = new JButton("Save");
            saveButton.setEnabled(false); // Initially disabled
            saveButton.addActionListener(e -> {
                performSave(saveButton); // Call the extracted save method, passing the button itself
            });
            actionButtonPanel.add(saveButton);
        }

        // Capture button (conditionally added for GitHistoryFragment)
        if (fragment != null && fragment.getType() == ContextFragment.FragmentType.GIT_FILE) {
            var ghf = (ContextFragment.GitFileFragment) fragment;
            captureButton = new JButton("Capture this Revision");
            var finalCaptureButton = captureButton; // Final reference for lambda
            captureButton.addActionListener(e -> {
                // Add the GitHistoryFragment to the read-only context
                contextManager.addReadOnlyFragment(ghf); // Use the new method
                if (finalCaptureButton != null) {
                    finalCaptureButton.setEnabled(false); // Disable after capture
                    finalCaptureButton.setToolTipText("Revision captured");
                }
            });
            actionButtonPanel.add(captureButton); // Add capture button
        }

        // Edit button (conditionally added for ProjectFile)
        if (file != null) {
            var text = (fragment != null && fragment.getType() == ContextFragment.FragmentType.GIT_FILE) ? "Edit Current Version" : "Edit File";
            editButton = new JButton(text);
            var finalEditButton = editButton; // Final reference for lambda
            if (contextManager.getEditableFiles().contains(file)) {
                if (finalEditButton != null) {
                    finalEditButton.setEnabled(false);
                    finalEditButton.setToolTipText("File is in Edit context");
                }
            } else {
                if (finalEditButton != null) {
                    finalEditButton.addActionListener(e -> {
                        contextManager.editFiles(List.of(this.file));
                        finalEditButton.setEnabled(false);
                        finalEditButton.setToolTipText("File is in Edit context");
                    });
                }
            }
            actionButtonPanel.add(editButton); // Add edit button to the action panel
        }

        // Add the action button panel to the top panel if it has any buttons
        if (actionButtonPanel.getComponentCount() > 0) {
            topPanel.add(actionButtonPanel, BorderLayout.EAST);
        }

        // Add document listener to enable/disable save button based on changes
        if (saveButton != null) {
            // Use the final reference created for the ActionListener
            final JButton finalSaveButtonRef = saveButton;
            textArea.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    finalSaveButtonRef.setEnabled(true);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    finalSaveButtonRef.setEnabled(true);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    finalSaveButtonRef.setEnabled(true);
                }
            });
        }

        // Put the text area in a scroll pane
        var scrollPane = new RTextScrollPane(textArea);
        scrollPane.setFoldIndicatorEnabled(true);

        // Apply the current theme to the text area
        guiTheme.applyCurrentThemeToComponent(textArea);

        // Add top panel (search + edit) + text area to this panel
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        // Register global shortcuts for the search bar
        searchBar.registerGlobalShortcuts(this);

        // Request focus for the text area after the panel is initialized and visible
        SwingUtilities.invokeLater(textArea::requestFocusInWindow);

        // Scroll to the beginning of the document
        textArea.setCaretPosition(0);

        // Register ESC key to close the dialog
        registerEscapeKey();
        // Register Ctrl/Cmd+S to save
        registerSaveKey();
        // Setup custom window close handler
        setupWindowCloseHandler();

        // Fetch declarations in the background if it's a project file
        if (file != null) {
            fileDeclarations = contextManager.submitBackgroundTask("Fetch Declarations", () -> {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                return analyzer.isEmpty() ? Collections.emptySet() : analyzer.getDeclarationsInFile(file);
            });
        } else {
            fileDeclarations = null; // Ensure @Nullable field is explicitly null if file is null
        }
    }

    /**
     * Implementation of {@link ThemeAware}. Delegates the actual work to
     * {@link GuiTheme#applyCurrentThemeToComponent}.
     */
    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.updateComponentTreeUI(this);
        guiTheme.applyCurrentThemeToComponent(textArea);
    }

    /**
     * Custom RSyntaxTextArea implementation for preview panels with custom popup menu
     */
    public class PreviewTextArea extends RSyntaxTextArea {
        public PreviewTextArea(String content, @Nullable String syntaxStyle, boolean isEditable) {
            setSyntaxEditingStyle(syntaxStyle != null ? syntaxStyle : SyntaxConstants.SYNTAX_STYLE_NONE);
            setCodeFoldingEnabled(true);
            setAntiAliasingEnabled(true);
            setHighlightCurrentLine(false);
            setEditable(isEditable);
            setText(content);
        }

        @Override
        protected JPopupMenu createPopupMenu() {
            var menu = new JPopupMenu();

            // Add Copy option
            var copyAction = new AbstractAction("Copy") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    copy();
                }
            };
            menu.add(copyAction);

            // Add Quick Edit option (disabled by default, will be enabled when text is selected and file != null)
            var quickEditAction = new AbstractAction("Quick Edit") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    PreviewTextPanel.this.showQuickEditDialog(getSelectedText());
                }
            };
            quickEditAction.setEnabled(false); // Initially disabled
            menu.add(quickEditAction);

            // Listener to enable/disable actions and add dynamic "Capture usages" items
            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                    var textSelected = getSelectedText() != null;

                    // Enable Quick Edit only if text is selected and it's a project file
                    quickEditAction.setEnabled(textSelected && file != null);

                    // Clear previous dynamic items
                    dynamicMenuItems.forEach(menu::remove);
                    dynamicMenuItems.clear();

                    populateDynamicMenuItems();
                    // Add separator and usage menu items if any were found
                    if (!dynamicMenuItems.isEmpty()) {
                        dynamicMenuItems.addFirst(new JPopupMenu.Separator());
                        dynamicMenuItems.forEach(menu::add); // Now add them to the menu
                    }
                }

                private void populateDynamicMenuItems() {
                    // Add "Capture usages" items if it's a project file and declarations are available
                    if (file == null) {
                        return;
                    }
                    if (fileDeclarations == null) { // Guard against null fileDeclarations
                        logger.warn("fileDeclarations is null when populating dynamic menu items. This should not happen if file is not null.");
                        return;
                    }

                    if (!fileDeclarations.isDone()) {
                        var item = new JMenuItem("Waiting for Code Intelligence...");
                        item.setEnabled(false);
                        dynamicMenuItems.add(item);
                        return;
                    }

                    int offset = -1;
                    var mousePos = getMousePosition(); // Position relative to this text area

                    if (mousePos != null) {
                        offset = viewToModel2D(mousePos); // Get document offset from mouse coordinates
                    } else {
                        // Fallback to caret position if mouse position is not available (e.g., keyboard invocation)
                        offset = getCaretPosition();
                    }

                    if (offset < 0) {
                        logger.warn("Could not determine valid document offset from mouse position {} or caret", mousePos);
                        return;
                    }

                    Set<CodeUnit> codeUnits;
                    try {
                        codeUnits = fileDeclarations.get();
                    } catch (InterruptedException | ExecutionException ex) {
                        throw new RuntimeException(ex);
                    }
                    try {
                        int lineNum = getLineOfOffset(offset);
                        int lastLine = getLineCount() - 1;
                        int lineStartOffset = getLineStartOffset(lineNum > 0 ? lineNum - 1 : lineNum);
                        int lineEndOffset = getLineEndOffset(lineNum < lastLine ? lineNum + 1 : lineNum);
                        var lineText = getText(lineStartOffset, lineEndOffset - lineStartOffset);

                        if (lineText != null && !lineText.trim().isEmpty()) {
                            for (CodeUnit unit : codeUnits) {
                                var identifier = unit.identifier();
                                var p = Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b");
                                if (p.matcher(lineText).find()) {
                                    var item = new JMenuItem("Capture usages of " + unit.shortName());
                                    // Use a local variable for the action listener lambda
                                    item.addActionListener(action -> {
                                        contextManager.submitBackgroundTask("Capture Usages", () -> contextManager.usageForIdentifier(unit.fqName()));
                                    });
                                    dynamicMenuItems.add(item); // Track for removal
                                }
                            }
                        }
                    } catch (javax.swing.text.BadLocationException ex) {
                        logger.warn("Error getting line text for usage capture menu items based on offset {}", offset, ex);
                    }
                }

                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                    // Clear dynamic items when the menu closes
                    dynamicMenuItems.forEach(menu::remove);
                    dynamicMenuItems.clear();
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                    // Clear dynamic items if the menu is canceled
                    dynamicMenuItems.forEach(menu::remove);
                    dynamicMenuItems.clear();
                }
            });

            return menu;
        }
    } // End of PreviewTextArea inner class

    /**
     * Shows a quick edit dialog with the selected text.
     *
     * @param selectedText The text currently selected in the preview
     */
    private void showQuickEditDialog(String selectedText) {
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }

        // Check if the selected text is unique before opening the dialog
        var currentContent = textArea.getText();
        int firstIndex = currentContent.indexOf(selectedText);
        int lastIndex = currentContent.lastIndexOf(selectedText);
        if (firstIndex != lastIndex) {
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(textArea),
                                          "Text selected for Quick Edit must be unique in the file -- expand your selection.",
                                          "Selection Not Unique",
                                          JOptionPane.WARNING_MESSAGE);
            return;
        }

        textArea.setEditable(false);

        // Create quick edit dialog
        var ancestor = SwingUtilities.getWindowAncestor(this);
        JDialog quickEditDialog;
        if (ancestor instanceof Frame frame) {
            quickEditDialog = new JDialog(frame, "Quick Edit", true);
        } else if (ancestor instanceof Dialog dialog) {
            quickEditDialog = new JDialog(dialog, "Quick Edit", true);
        } else {
            quickEditDialog = new JDialog((Frame) null, "Quick Edit", true);
        }
        quickEditDialog.setLayout(new BorderLayout());

        // Create main panel for quick edit dialog (without system messages pane)
        var mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createEtchedBorder(),
                        "Instructions",
                        javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                        javax.swing.border.TitledBorder.DEFAULT_POSITION,
                        new Font(Font.DIALOG, Font.BOLD, 12)
                ),
                new EmptyBorder(5, 5, 5, 5)
        ));

        // Create edit area with the same styling as the command input in Chrome
        var editArea = new RSyntaxTextArea(3, 40);
        editArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        editArea.setHighlightCurrentLine(false);
        editArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));
        editArea.setLineWrap(true);
        editArea.setWrapStyleWord(true);
        editArea.setRows(3);
        editArea.setMinimumSize(new Dimension(100, 80));
        editArea.setAutoIndentEnabled(false);

        // Scroll pane for edit area
        var scrollPane = new JScrollPane(editArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // Informational label below the input area
        var infoLabel = new JLabel("Quick Edit will apply to the selected lines only");
        infoLabel.setFont(new Font(Font.DIALOG, Font.ITALIC, 11));
        infoLabel.setForeground(Color.DARK_GRAY);
        infoLabel.setBorder(new EmptyBorder(5, 5, 0, 5));

        // Voice input button
        var inputPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();

        // Start calculation of symbols specific to the current file in the background
        Future<Set<String>> symbolsFuture = null;
        // Only try to get symbols if we have a file and its corresponding fragment is a PathFragment
        if (file != null) {
            // Submit the task to fetch symbols in the background
            symbolsFuture = contextManager.submitBackgroundTask("Fetch File Symbols", () -> {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                return analyzer.getSymbols(analyzer.getDeclarationsInFile(file));
            });
        }

        // Voice input button setup, passing the Future for file-specific symbols
        var micButton = new VoiceInputButton(editArea,
                                             contextManager,
                                             () -> { /* no action on record start */ },
                                             symbolsFuture,
                                             error -> { /* no special error handling */ });

        // infoLabel at row=0
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 5, 0);
        inputPanel.add(infoLabel, gbc);

        // micButton at (0,1)
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(2, 2, 2, 8);
        inputPanel.add(micButton, gbc);

        // scrollPane at (1,1)
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        inputPanel.add(scrollPane, gbc);

        // Bottom panel with buttons
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var codeButton = new JButton("Code");
        var cancelButton = new JButton("Cancel");

        cancelButton.addActionListener(e -> {
            quickEditDialog.dispose();
            textArea.setEditable(true);
        });
        buttonPanel.add(codeButton);
        buttonPanel.add(cancelButton);

        // Assemble quick edit dialog main panel
        mainPanel.add(inputPanel, BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.PAGE_END);
        quickEditDialog.add(mainPanel);

        // Set a preferred size for the scroll pane
        scrollPane.setPreferredSize(new Dimension(400, 150));

        quickEditDialog.pack();
        quickEditDialog.setLocationRelativeTo(this);
        // ESC closes dialog
        quickEditDialog.getRootPane().registerKeyboardAction(
                e -> quickEditDialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );

        // Set Code as the default button (triggered by Enter key)
        quickEditDialog.getRootPane().setDefaultButton(codeButton);

        // Register Ctrl+Enter to submit dialog
        var ctrlEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        editArea.getInputMap().put(ctrlEnter, "submitQuickEdit");
        editArea.getActionMap().put("submitQuickEdit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                codeButton.doClick();
            }
        });

        // Set focus to the edit area when the dialog opens
        SwingUtilities.invokeLater(editArea::requestFocusInWindow);

        // Code button logic: dispose quick edit dialog and open Quick Results dialog.
        codeButton.addActionListener(e -> {
            var instructions = editArea.getText().trim();
            if (instructions.isEmpty()) {
                // Instead of silently closing, show a simple message and cancel.
                JOptionPane.showMessageDialog(quickEditDialog,
                                              "No instructions provided. Quick edit cancelled.",
                                              "Quick Edit",
                                              JOptionPane.INFORMATION_MESSAGE);
                quickEditDialog.dispose();
                textArea.setEditable(true);
                return;
            }
            quickEditDialog.dispose();
            openQuickResultsDialog(selectedText, instructions);
        });

        quickEditDialog.setVisible(true);
    }

    /**
     * Opens the Quick Results dialog that displays system messages and controls task progress.
     *
     * @param selectedText The text originally selected.
     * @param instructions The instructions provided by the user.
     */
    private void openQuickResultsDialog(String selectedText, String instructions) {
        requireNonNull(file);

        var ancestor = SwingUtilities.getWindowAncestor(this);
        JDialog resultsDialog;
        if (ancestor instanceof Frame frame) {
            resultsDialog = new JDialog(frame, "Quick Edit", false);
        } else if (ancestor instanceof Dialog dialog) {
            resultsDialog = new JDialog(dialog, "Quick Edit", false);
        } else {
            resultsDialog = new JDialog((Frame) null, "Quick Edit", false);
        }
        resultsDialog.setLayout(new BorderLayout());

        // System messages pane
        var systemArea = new JTextArea();
        systemArea.setEditable(false);
        systemArea.setLineWrap(true);
        systemArea.setWrapStyleWord(true);
        systemArea.setRows(5);
        var systemScrollPane = new JScrollPane(systemArea);
        systemScrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "System Messages",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        systemArea.setText("Request sent");
        systemScrollPane.setPreferredSize(new Dimension(400, 200));

        // Bottom panel with Okay and Stop buttons
        var bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okayButton = new JButton("Okay");
        var stopButton = new JButton("Stop");
        okayButton.setEnabled(false);
        bottomPanel.add(okayButton);
        bottomPanel.add(stopButton);

        resultsDialog.add(systemScrollPane, BorderLayout.CENTER);
        resultsDialog.add(bottomPanel, BorderLayout.PAGE_END);
        resultsDialog.pack();
        resultsDialog.setLocationRelativeTo(this);

        // Create our IConsoleIO for quick results that appends to the systemArea.
        class QuickResultsIo implements IConsoleIO {
            final AtomicBoolean hasError = new AtomicBoolean();

            private void appendSystemMessage(String text) {
                if (!systemArea.getText().isEmpty() && !systemArea.getText().endsWith("\n")) {
                    systemArea.append("\n");
                }
                systemArea.append(text);
                systemArea.append("\n");
            }

            @Override
            public void toolError(String msg, String title) {
                hasError.set(true);
                appendSystemMessage(msg);
            }

            @Override
            public void actionOutput(String msg) {
                appendSystemMessage(msg);
            }

            @Override
            public void llmOutput(String token, ChatMessageType type, boolean isNewMessage) {
                appendSystemMessage(token);
            }

            @Override
            public void showOutputSpinner(String message) {
                // no-op
            }

            @Override
            public void hideOutputSpinner() {
                // no-op
            }
        }
        var resultsIo = new QuickResultsIo();

        // Submit the quick-edit session to a background future
        var future = contextManager.submitUserTask("Quick Edit", () -> {
            var agent = new CodeAgent(contextManager, contextManager.getService().quickModel());
            if (file == null) {
                // This should ideally not be reached if Quick Edit is only enabled for project files.
                // However, as a safeguard:
                throw new IllegalStateException("Quick Edit attempted on a non-project file or null file context.");
            }
            return agent.runQuickTask(file,
                                       selectedText,
                                       instructions);
        });

        // Stop button cancels the task and closes the dialog.
        stopButton.addActionListener(e -> {
            future.cancel(true);
            resultsDialog.dispose();
        });

        // Okay button simply closes the dialog.
        okayButton.addActionListener(e -> resultsDialog.dispose());

        // Fire up a background thread to retrieve results and apply snippet.
        new Thread(() -> {
            QuickEditResult quickEditResult = null;
            try {
                // Centralized logic for session + snippet extraction + file replace
                quickEditResult = performQuickEdit(future, selectedText);
            } catch (InterruptedException ex) {
                // If the thread itself is interrupted
                Thread.currentThread().interrupt();
                quickEditResult = new QuickEditResult(null, "Quick edit interrupted.");
            } catch (ExecutionException e) {
                logger.debug("Internal error executing Quick Edit", e);
                quickEditResult = new QuickEditResult(null, "Internal error executing Quick Edit");
            } finally {
                // Ensure we update the UI state on the EDT
                SwingUtilities.invokeLater(() -> {
                    stopButton.setEnabled(false);
                    okayButton.setEnabled(true);
                });

                // Log the outcome
                logger.debug(quickEditResult);
            }

            // If the quick edit was successful (snippet not null), select the new text
            if (quickEditResult.snippet() != null) {
                var newSnippet = quickEditResult.snippet();
                SwingUtilities.invokeLater(() -> {
                    // Re-enable the text area if we're going to modify it
                    textArea.setEditable(true);

                    int startOffset = textArea.getText().indexOf(newSnippet);
                    if (startOffset >= 0) {
                        textArea.setCaretPosition(startOffset);
                        textArea.moveCaretPosition(startOffset + newSnippet.length());
                    } else {
                        textArea.setCaretPosition(0); // fallback if not found
                    }
                    textArea.grabFocus();

                    // Close the dialog automatically if there were no errors
                    if (!resultsIo.hasError.get()) {
                        resultsDialog.dispose();
                    }
                });
            } else {
                // Display an error dialog with the failure message
                var errorMessage = quickEditResult.error();
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(textArea),
                                                  errorMessage,
                                                  "Quick Edit Failed",
                                                  JOptionPane.ERROR_MESSAGE);
                    textArea.setEditable(true);
                });
            }
        }).start();

        resultsDialog.setVisible(true);
    }

    /**
     * A small holder for quick edit outcome, containing either the generated snippet
     * or an error message detailing the failure. Exactly one field will be non-null.
     */
    private record QuickEditResult(@Nullable String snippet, @Nullable String error) {
        public QuickEditResult {
            assert (snippet == null) != (error == null) : "Exactly one of snippet or error must be non-null";
        }
    }

    /**
     * Centralizes retrieval of the SessionResult, extraction of the snippet,
     * and applying the snippet to the file. Returns a QuickEditResult with the final
     * success status, snippet text, and stop details.
     *
     * @throws InterruptedException If future.get() is interrupted.
     */
    private QuickEditResult performQuickEdit(Future<TaskResult> future,
                                             String selectedText) throws ExecutionException, InterruptedException
    {
        var sessionResult = future.get(); // might throw InterruptedException or ExecutionException
        var stopDetails = sessionResult.stopDetails();
        quickEditMessages = sessionResult.output().messages(); // Capture messages regardless of outcome

        // If the LLM itself was not successful, return the error
        if (stopDetails.reason() != TaskResult.StopReason.SUCCESS) {
            var explanation = stopDetails.explanation() != null ? stopDetails.explanation() : "LLM task failed with " + stopDetails.reason();
            logger.debug("Quick Edit LLM task failed: {}", explanation);
            return new QuickEditResult(null, Objects.toString(explanation, "LLM task failed without specific explanation."));
        }

        // LLM call succeeded; try to parse a snippet
        var response = sessionResult.output().messages().getLast();
        assert response instanceof AiMessage;
        var responseText = Messages.getText(response);
        var snippet = EditBlock.extractCodeFromTripleBackticks(responseText).trim();
        if (snippet.isEmpty()) {
            logger.debug("Could not parse a fenced code snippet from LLM response {}", responseText);
            return new QuickEditResult(null, "No code block found in LLM response");
        }

        // Apply the edit (replacing the unique occurrence)
        SwingUtilities.invokeLater(() -> {
            try {
                // Find position of the selected text
                var currentText = textArea.getText();
                int startPos = currentText.indexOf(selectedText.stripLeading());

                // Use beginAtomicEdit and endAtomicEdit to group operations as a single undo unit
                textArea.beginAtomicEdit();
                try {
                    textArea.getDocument().remove(startPos, selectedText.stripLeading().length());
                    textArea.getDocument().insertString(startPos, snippet.stripLeading(), null);
                } finally {
                    textArea.endAtomicEdit();
                }
            } catch (javax.swing.text.BadLocationException ex) {
                logger.error("Error applying quick edit change", ex);
                // Fallback to direct text replacement
                textArea.setText(textArea.getText().replace(selectedText.stripLeading(), snippet.stripLeading()));
            }
        });
        return new QuickEditResult(snippet, null);
    }

    /**
     * Registers ESC key to close the preview panel
     */
    private void registerEscapeKey() {
        KeyboardShortcutUtil.registerCloseEscapeShortcut(this, () -> {
            if (confirmClose()) {
                var window = SwingUtilities.getWindowAncestor(PreviewTextPanel.this);
                if (window != null) {
                    window.dispose();
                }
            }
        });
    }

    /**
     * Sets up a handler for the window's close button ("X") to ensure `confirmClose` is called.
     */
    private void setupWindowCloseHandler() {
        SwingUtilities.invokeLater(() -> {
            var ancestor = SwingUtilities.getWindowAncestor(this);
            // Set default close operation to DO_NOTHING_ON_CLOSE so we can handle it
            if (ancestor instanceof JFrame frame) {
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            } else if (ancestor instanceof JDialog dialog) {
                dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            }

            ancestor.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    if (confirmClose()) {
                        ancestor.dispose();
                    }
                }
            });
        });
    }

    /**
     * Checks for unsaved changes and prompts the user to save, discard, or cancel closing.
     *
     * @return true if the window should close, false otherwise.
     */
    public boolean confirmClose() {
        if (saveButton == null || !saveButton.isEnabled()) {
            return true; // No unsaved changes or not a savable file, okay to close
        }

        // Prompt the user
        int choice = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                "You have unsaved changes. Do you want to save them before closing?",
                "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);

        return switch (choice) {
            case JOptionPane.YES_OPTION -> performSave(saveButton);
            case JOptionPane.NO_OPTION -> true; // Allow closing, discard changes
            default -> false; // Prevent closing
        };
    }

    /**
     * Registers the Ctrl+S (or Cmd+S on Mac) keyboard shortcut to trigger the save action.
     */
    private void registerSaveKey() {
        KeyboardShortcutUtil.registerSaveShortcut(this, () -> {
            // Only perform save if the file exists and the save button is enabled (changes exist)
            if (file != null && saveButton != null && saveButton.isEnabled()) {
                performSave(saveButton);
            }
        });
    }


    /**
     * Performs the file save operation, updating history and disabling the save button.
     *
     * @param buttonToDisable The save button instance to disable after a successful save.
     * @return true if the save was successful, false otherwise.
     */
    private boolean performSave(@Nullable JButton buttonToDisable) {
        assert file != null : "Attempted to save but no ProjectFile is associated with this panel.";
        var newContent = textArea.getText();

        var contentChangedFromInitial = !newContent.equals(contentBeforeSave);

        if (contentChangedFromInitial) {
            try {
                // Generate a unified diff from the initial state to the current state
                var originalLines = contentBeforeSave.lines().collect(Collectors.toList());
                var newLines = newContent.lines().collect(Collectors.toList());
                var patch = DiffUtils.diff(originalLines, newLines);
                var fileNameForDiff = file != null ? file.toString() : "untitled";
                var unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(fileNameForDiff,
                                                                              fileNameForDiff,
                                                                              originalLines,
                                                                              patch,
                                                                              3);
                // Create the SessionResult representing the net change
                var actionDescription = "Edited " + fileNameForDiff;
                // Include quick edit messages accumulated since last save + the current diff
                var messagesForHistory = new ArrayList<>(quickEditMessages);
                messagesForHistory.add(Messages.customSystem("# Diff of changes\n\n```%s```".formatted(unifiedDiff)));
                var saveResult = new TaskResult(contextManager,
                                                actionDescription,
                                                messagesForHistory,
                                                Map.of(file, contentBeforeSave),
                                                TaskResult.StopReason.SUCCESS);
                contextManager.addToHistory(saveResult, false); // Add the single entry
                logger.debug("Added history entry for changes in: {}", file);
            } catch (Exception e) {
                logger.error("Failed to generate diff or add history entry for {}", file, e);
            }
        }

        try {
            // Write the new content to the file, regardless of whether it matched initial content,
            // because saveButton being enabled implies it's different from last saved state.
            if (file != null) {
                file.write(newContent);
                if (buttonToDisable != null) {
                    buttonToDisable.setEnabled(false); // Disable after successful save
                }
                quickEditMessages.clear(); // Clear quick edit messages accumulated up to this save
                logger.debug("File saved: " + file);
                return true; // Save successful
            } else {
                // Should not happen if save button is only enabled for project files
                logger.error("Attempted to save but no ProjectFile is associated with this panel.");
                JOptionPane.showMessageDialog(this,
                                              "Cannot save: No file context.",
                                              "Save Error",
                                              JOptionPane.ERROR_MESSAGE);
                return false;
            }
        } catch (IOException ex) {
            // If save fails, button remains enabled and messages are not cleared.
            logger.error("Error saving file {}", file != null ? file : "null", ex);
            JOptionPane.showMessageDialog(this,
                                          "Error saving file: " + ex.getMessage(),
                                          "Save Error",
                                          JOptionPane.ERROR_MESSAGE);
            return false; // Save failed
        }
    }

}
