package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.LLM;
import io.github.jbellis.brokk.analyzer.RepoFile;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * A panel that displays text (typically code) in an RSyntaxTextArea with
 * advanced features:
 * - Case-insensitive, as-you-type search with Next/Previous navigation
 * - Ctrl+F focuses the search field
 * - Context menu with Copy and Quick Edit options
 * - Quick Edit dialog allows AI-assisted editing of selected code
 * - Quick Results dialog provides real-time feedback during code modifications
 */
public class PreviewPanel extends JPanel
{
    private final PreviewTextArea textArea;
    private final JTextField searchField;
    private final JButton nextButton;
    private final JButton previousButton;
    private final ContextManager contextManager;

    // Theme manager reference
    private GuiTheme themeManager;

    // Nullable
    private final RepoFile file;

    public PreviewPanel(ContextManager contextManager,
                        RepoFile file,
                        String content,
                        String syntaxStyle,
                        GuiTheme guiTheme)
    {
        super(new BorderLayout());
        assert contextManager != null;
        assert guiTheme != null;

        this.contextManager = contextManager;
        this.themeManager = guiTheme;
        this.file = file;

        // === Top search bar ===
        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        searchField = new JTextField(20);
        nextButton = new JButton("↓");
        previousButton = new JButton("↑");

        searchPanel.add(new JLabel("Search:"));
        searchPanel.add(searchField);
        searchPanel.add(previousButton);
        searchPanel.add(nextButton);

        // === Text area with syntax highlighting ===
        textArea = new PreviewTextArea(content, syntaxStyle);

        // Put the text area in a scroll pane
        RTextScrollPane scrollPane = new RTextScrollPane(textArea);
        scrollPane.setFoldIndicatorEnabled(true);

        // Apply the current theme to the text area
        if (guiTheme != null) {
            guiTheme.applyCurrentThemeToComponent(textArea);
        }

        // Add top search panel + text area to this panel
        add(searchPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        // === Hook up the search as you type ===
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSearchHighlights(true);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSearchHighlights(true);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSearchHighlights(true);
            }
        });

        // === Enter key in search field triggers next match ===
        searchField.addActionListener(e -> findNext(true));

        // === Arrow keys for navigation ===
        InputMap inputMap = searchField.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = searchField.getActionMap();

        // Down arrow for next match
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "findNext");
        actionMap.put("findNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext(true);
            }
        });

        // Up arrow for previous match
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "findPrevious");
        actionMap.put("findPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                findNext(false);
            }
        });

        // === Next / Previous buttons ===
        nextButton.addActionListener(e -> findNext(true));

        previousButton.addActionListener(e -> findNext(false));

        // === Cmd/Ctrl+F focuses the search field ===
        KeyStroke ctrlF = KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(ctrlF, "focusSearch");
        getActionMap().put("focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchField.requestFocusInWindow();
                // If there's text in the search field, re-highlight matches
                // without changing the caret position
                String query = searchField.getText();
                if (query != null && !query.trim().isEmpty()) {
                    int originalCaretPosition = textArea.getCaretPosition();
                    updateSearchHighlights(false);
                    textArea.setCaretPosition(originalCaretPosition);
                }
            }
        });

        // Scroll to the beginning of the document
        textArea.setCaretPosition(0);

        // Register ESC key to close the dialog
        registerEscapeKey();
    }

    /**
     * Updates the theme of this panel
     * @param guiTheme The theme manager to use
     */
    public void updateTheme(GuiTheme guiTheme) {
        if (guiTheme != null) {
            guiTheme.applyCurrentThemeToComponent(textArea);
        }
    }
    
    /**
     * Constructs a new PreviewPanel with the given content and syntax style.
     *
     * @param content     The text content to display
     * @param syntaxStyle For example, SyntaxConstants.SYNTAX_STYLE_JAVA
     * @param guiTheme    The theme manager to use for styling the text area
     */
    /**
     * Custom RSyntaxTextArea implementation for preview panels with custom popup menu
     */
    public class PreviewTextArea extends RSyntaxTextArea {
        public PreviewTextArea(String content, String syntaxStyle) {
            setSyntaxEditingStyle(syntaxStyle != null ? syntaxStyle : SyntaxConstants.SYNTAX_STYLE_NONE);
            setCodeFoldingEnabled(true);
            setAntiAliasingEnabled(true);
            setHighlightCurrentLine(false);
            setEditable(false);
            setText(content);
        }

        @Override
        protected JPopupMenu createPopupMenu() {
            JPopupMenu menu = new JPopupMenu();

            // Add Copy option
            Action copyAction = new AbstractAction("Copy") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    copy();
                }
            };
            menu.add(copyAction);

            // Add Quick Edit option (disabled by default, will be enabled when text is selected and file != null)
            Action quickEditAction = new AbstractAction("Quick Edit") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    PreviewPanel.this.showQuickEditDialog(getSelectedText());
                }
            };
            quickEditAction.setEnabled(false);
            menu.add(quickEditAction);

            // Update Quick Edit enabled state when popup becomes visible
            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                    // Quick Edit only enabled if user selected some text AND file != null
                    quickEditAction.setEnabled(getSelectedText() != null && file != null);
                }
                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
            });

            return menu;
        }
    }
    
    /**
     * Shows a quick edit dialog with the selected text.
     *
     * @param selectedText The text currently selected in the preview
     */    private void showQuickEditDialog(String selectedText) {
        if (selectedText == null || selectedText.isEmpty()) {
            return;
        }

        // Create quick edit dialog
        Window ancestor = SwingUtilities.getWindowAncestor(this);
        JDialog quickEditDialog;
        if (ancestor instanceof Frame) {
            quickEditDialog = new JDialog((Frame) ancestor, "Quick Edit", true);
        } else if (ancestor instanceof Dialog) {
            quickEditDialog = new JDialog((Dialog) ancestor, "Quick Edit", true);
        } else {
            quickEditDialog = new JDialog((Frame) null, "Quick Edit", true);
        }
        quickEditDialog.setLayout(new BorderLayout());

        // Create main panel for quick edit dialog (without system messages pane)
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
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
        RSyntaxTextArea editArea = new RSyntaxTextArea(3, 40);
        editArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        editArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
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
        JScrollPane scrollPane = new JScrollPane(editArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // Informational label below the input area
        JLabel infoLabel = new JLabel("Quick Edit will apply to the selected lines only");
        infoLabel.setFont(new Font(Font.DIALOG, Font.ITALIC, 11));
        infoLabel.setForeground(Color.DARK_GRAY);
        infoLabel.setBorder(new EmptyBorder(5, 5, 0, 5));

        // Voice input button
        var inputPanel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();

        // Voice input button setup
        VoiceInputButton micButton = new VoiceInputButton(
                editArea,
                contextManager,
                () -> { /* no action on record start */ },
                error -> { /* no special error handling */ }
        );
        micButton.configure(
                contextManager != null &&
                        contextManager.getCoder() != null &&
                        contextManager.getCoder().models != null
        );

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
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton codeButton = new JButton("Code");
        JButton cancelButton = new JButton("Cancel");

        cancelButton.addActionListener(e -> quickEditDialog.dispose());
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

        // Set focus to the edit area when the dialog opens
        SwingUtilities.invokeLater(editArea::requestFocusInWindow);

        // Code button logic: dispose quick edit dialog and open Quick Results dialog.
        codeButton.addActionListener(e -> {
            var instructions = editArea.getText().trim();
            if (instructions.isEmpty()) {
                // Instead of silently closing, show a simple message and cancel.
                JOptionPane.showMessageDialog(quickEditDialog,
                                              "No instructions provided. Quick edit cancelled.",
                                              "Quick Edit", JOptionPane.INFORMATION_MESSAGE);
                quickEditDialog.dispose();
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
        Window ancestor = SwingUtilities.getWindowAncestor(this);
        JDialog resultsDialog;
        if (ancestor instanceof Frame) {
            resultsDialog = new JDialog((Frame) ancestor, "Quick Edit", false);
        } else if (ancestor instanceof Dialog) {
            resultsDialog = new JDialog((Dialog) ancestor, "Quick Edit", false);
        } else {
            resultsDialog = new JDialog((Frame) null, "Quick Edit", false);
        }
        resultsDialog.setLayout(new BorderLayout());

        // System messages pane
        JTextArea systemArea = new JTextArea();
        systemArea.setEditable(false);
        systemArea.setLineWrap(true);
        systemArea.setWrapStyleWord(true);
        systemArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        systemArea.setRows(5);
        JScrollPane systemScrollPane = new JScrollPane(systemArea);
        systemScrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "System Messages",
                javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION,
                new Font(Font.DIALOG, Font.BOLD, 12)
        ));
        systemArea.setText("Request sent.");
        
        // Set the same width as the quick edit dialog
        systemScrollPane.setPreferredSize(new Dimension(400, 200));
        
        // Set the same width as the quick edit dialog
        systemScrollPane.setPreferredSize(new Dimension(400, 200));

        // Bottom panel with Okay and Stop buttons
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okayButton = new JButton("Okay");
        JButton stopButton = new JButton("Stop");
        okayButton.setEnabled(false);
        bottomPanel.add(okayButton);
        bottomPanel.add(stopButton);

        resultsDialog.add(systemScrollPane, BorderLayout.CENTER);
        resultsDialog.add(bottomPanel, BorderLayout.PAGE_END);
        resultsDialog.pack();
        resultsDialog.setLocationRelativeTo(this);

        // Create our IConsoleIO for quick results that appends to the systemArea.
        class QuickResultsIo implements IConsoleIO {
            private void appendSystemMessage(String text) {
                if (!systemArea.getText().isEmpty() && !systemArea.getText().endsWith("\n")) {
                    systemArea.append("\n");
                }
                systemArea.append(text);
                systemArea.append("\n");
            }

            @Override
            public void actionOutput(String msg) {
                appendSystemMessage("Action: " + msg);
            }

            @Override
            public void actionComplete() {
                appendSystemMessage("[action complete]");
            }

            @Override
            public void toolErrorRaw(String msg) {
                appendSystemMessage("Error: " + msg);
            }

            @Override
            public void llmOutput(String token) {
                appendSystemMessage(token);
            }

            @Override
            public void systemOutput(String message) {
                appendSystemMessage(message);
            }

        }
        var resultsIo = new QuickResultsIo();

        // Submit the quick edit task.
        var future = contextManager.submitUserTask("Quick Edit", () -> {
            LLM.runQuickSession(
                    contextManager,
                    resultsIo,
                    file,
                    selectedText,
                    instructions
            );
        });

        // Stop button cancels the task and closes the dialog.
        stopButton.addActionListener(e -> {
            future.cancel(true);
            resultsDialog.dispose();
        });

        // Okay button simply closes the dialog.
        okayButton.addActionListener(e -> resultsDialog.dispose());

        // Wait for the task to complete in a background thread.
        new Thread(() -> {
            try {
                future.get(); // Wait for the task to complete successfully.
                // On success, reload the preview panel's contents from the repofile.
                var newContent = file.read(); // Assumes RepoFile.getContent() exists.
                SwingUtilities.invokeLater(() -> {
                    textArea.setText(newContent);
                    // Reapply the theme
                    if (themeManager != null) {
                        themeManager.applyCurrentThemeToComponent(textArea);
                    }
                    okayButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    resultsIo.systemOutput("Quick edit completed successfully.");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> resultsIo.toolErrorRaw("Error during quick edit: " + ex.getMessage()));
            }
        }).start();

        resultsDialog.setVisible(true);
    }
    
    /**
     * Registers ESC key to first clear search highlights if search field has focus,
     * otherwise close the preview panel
     */
    private void registerEscapeKey() {
        // Register ESC handling for the search field
        KeyStroke escapeKeyStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

        // Add ESC handler to search field to clear highlights and defocus it
        searchField.getInputMap(JComponent.WHEN_FOCUSED).put(escapeKeyStroke, "defocusSearch");
        searchField.getActionMap().put("defocusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Clear all highlights but keep search text
                SearchContext context = new SearchContext();
                context.setMarkAll(false);
                SearchEngine.markAll(textArea, context);
                // Clear the current selection/highlight as well
                textArea.setCaretPosition(textArea.getCaretPosition());
                textArea.requestFocusInWindow();
            }
        });
        
        // Add ESC handler to panel to close window when search is not focused
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escapeKeyStroke, "closePreview");
        getActionMap().put("closePreview", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Only close if search field doesn't have focus
                if (!searchField.hasFocus()) {
                    Window window = SwingUtilities.getWindowAncestor(PreviewPanel.this);
                    if (window != null) {
                        window.dispose();
                    }
                }
            }
        });
    }

    /**
     * Called whenever the user types in the search field, to highlight all matches (case-insensitive).
     * 
     * @param jumpToFirst If true, jump to the first occurrence; if false, maintain current position
     */
    private void updateSearchHighlights(boolean jumpToFirst)
    {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            // Clear all highlights if query is empty
            SearchContext clearContext = new SearchContext();
            clearContext.setMarkAll(false);
            SearchEngine.markAll(textArea, clearContext);
            return;
        }

        SearchContext context = new SearchContext(query);
        context.setMatchCase(false);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(true);
        context.setSearchWrap(true);

        // Mark all occurrences
        SearchEngine.markAll(textArea, context);

        if (jumpToFirst) {
            // Jump to the first occurrence as the user types
            int originalCaretPosition = textArea.getCaretPosition();
            textArea.setCaretPosition(0); // Start search from beginning
            SearchResult result = SearchEngine.find(textArea, context);
            if (!result.wasFound() && originalCaretPosition > 0) {
                // If not found from beginning, restore caret position
                textArea.setCaretPosition(originalCaretPosition);
            } else if (result.wasFound()) {
                // Center the match in the viewport
                centerCurrentMatchInView();
            }
        }
    }
    
    /**
     * Centers the current match in the viewport
     */
    private void centerCurrentMatchInView() {
        try {
            Rectangle matchRect = textArea.modelToView(textArea.getCaretPosition());
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, textArea);
            if (viewport != null && matchRect != null) {
                // Calculate the target Y position (1/3 from the top)
                int viewportHeight = viewport.getHeight();
                int targetY = Math.max(0, (int)(matchRect.y - viewportHeight * 0.33));

                // Create a new point for scrolling
                Rectangle viewRect = viewport.getViewRect();
                viewRect.y = targetY;
                textArea.scrollRectToVisible(viewRect);
            }
        } catch (Exception ex) {
            // Silently ignore any view transformation errors
        }
    }

    /**
     * Finds the next or previous match relative to the current caret position.
     * @param forward true = next match; false = previous match
     */
    private void findNext(boolean forward)
    {
        String query = searchField.getText();
        if (query == null || query.trim().isEmpty()) {
            return;
        }

        // Our context for next/previous. We'll ignore case, no regex, wrap around.
        SearchContext context = new SearchContext(query);
        context.setMatchCase(false);
        context.setMarkAll(true);
        context.setWholeWord(false);
        context.setRegularExpression(false);
        context.setSearchForward(forward);
        context.setSearchWrap(true);

        SearchResult result = SearchEngine.find(textArea, context);
        if (result.wasFound()) {
            centerCurrentMatchInView();
        }
    }
}
