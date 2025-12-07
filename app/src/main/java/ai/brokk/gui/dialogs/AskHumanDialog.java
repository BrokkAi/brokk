package ai.brokk.gui.dialogs;

import ai.brokk.context.ContextFragment;
import ai.brokk.gui.BorderUtils;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.mop.MarkdownOutputPanel;
import dev.langchain4j.data.message.AiMessage;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a markdown-rendered question to the human and returns the answer text. Returns {@code null} if the user
 * cancels, closes the dialog, or provides an empty answer.
 */
public final class AskHumanDialog {

    public static @Nullable String ask(Chrome chrome, String question) {
        // Run the entire UI interaction on the EDT and return the answer (or null).
        return SwingUtil.runOnEdt(
                () -> {
                    String sessionName = "Ask Human";

                    /* --------- Question (Markdown) ---------------------------------- */
                    var questionPanel = new MarkdownOutputPanel(true);
                    questionPanel.withContextForLookups(chrome.getContextManager(), chrome);
                    var fragment = new ContextFragment.TaskFragment(
                            chrome.getContextManager(), List.of(new AiMessage(question)), sessionName);
                    questionPanel.setText(fragment);
                    questionPanel.applyTheme(chrome.getTheme());

                    var questionScroll = new JScrollPane(questionPanel);
                    questionScroll.setPreferredSize(new Dimension(800, 400));
                    questionScroll.setBorder(new EmptyBorder(10, 10, 10, 10));
                    questionScroll.getVerticalScrollBar().setUnitIncrement(16);

                    /* --------- Answer area ----------------------------------------- */
                    var answerArea = new JTextArea(6, 60);
                    answerArea.setLineWrap(true);
                    answerArea.setWrapStyleWord(true);
                    BorderUtils.addFocusBorder(answerArea, answerArea);

                    var answerScroll = new JScrollPane(answerArea);
                    answerScroll.setBorder(new EmptyBorder(0, 10, 10, 10));

                    /* --------- Compose content ------------------------------------- */
                    var content = new JPanel(new BorderLayout(0, 8));
                    content.add(questionScroll, BorderLayout.CENTER);
                    content.add(answerScroll, BorderLayout.SOUTH);

                    /* --------- Custom buttons and dialog logic --------------------- */
                    final String[] resultHolder = {null}; // To store the answer from listeners

                    var okButton = new MaterialButton("OK");
                    SwingUtil.applyPrimaryButtonStyle(okButton);
                    var cancelButton = new MaterialButton("Cancel");

                    // Disable OK button initially
                    okButton.setEnabled(false);

                    // DocumentListener for answerArea to enable/disable OK button
                    answerArea.getDocument().addDocumentListener(new DocumentListener() {
                        private void updateOkButtonState() {
                            okButton.setEnabled(!answerArea.getText().trim().isEmpty());
                        }

                        @Override
                        public void insertUpdate(DocumentEvent e) {
                            updateOkButtonState();
                        }

                        @Override
                        public void removeUpdate(DocumentEvent e) {
                            updateOkButtonState();
                        }

                        @Override
                        public void changedUpdate(DocumentEvent e) {
                            updateOkButtonState();
                        }
                    });

                    // JOptionPane with custom buttons
                    var optionPane = new JOptionPane(
                            content,
                            JOptionPane.QUESTION_MESSAGE,
                            JOptionPane.DEFAULT_OPTION, // Indicates custom options are provided
                            null, // Use default icon for QUESTION_MESSAGE
                            new Object[] {okButton, cancelButton}, // Custom buttons
                            okButton // Default focused button (if enabled)
                            );

                    // Create the dialog
                    var dialog = optionPane.createDialog(null, sessionName);
                    dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE); // Handle close via listeners

                    // Action listener for OK button
                    okButton.addActionListener(e -> {
                        String answer = answerArea.getText().trim();
                        if (!answer.isEmpty()) {
                            resultHolder[0] = answer;
                            dialog.dispose();
                        }
                    });

                    // Action listener for Cancel button
                    cancelButton.addActionListener(e -> {
                        resultHolder[0] = null;
                        dialog.dispose();
                    });

                    // Window listener for 'X' button close (behaves as Cancel)
                    dialog.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosing(WindowEvent windowEvent) {
                            resultHolder[0] = null;
                            dialog.dispose();
                        }
                    });

                    dialog.pack(); // Adjust dialog size to fit contents
                    dialog.setLocationRelativeTo(null); // Center on screen (or relative to parent if specified)
                    dialog.setVisible(true); // Show modal dialog, blocks here

                    return resultHolder[0];
                },
                null); // defaultValue if invokeAndWait is interrupted
    }

    /**
     * Shows a markdown-rendered question to the human with multiple choice options and returns the selected choice.
     * Returns {@code null} if the user cancels, closes the dialog, or provides no selection.
     *
     * @param chrome the Chrome instance for theme and context
     * @param question the markdown-formatted question to display
     * @param choices list of choice strings to present as radio button options
     * @return the selected choice string, or null if cancelled
     */
    public static @Nullable String askWithChoices(Chrome chrome, String question, List<String> choices) {
        return SwingUtil.runOnEdt(
                () -> {
                    String sessionName = "Ask Human";

                    /* --------- Question (Markdown) ---------------------------------- */
                    var questionPanel = new MarkdownOutputPanel(true);
                    questionPanel.withContextForLookups(chrome.getContextManager(), chrome);
                    var fragment = new ContextFragment.TaskFragment(
                            chrome.getContextManager(), List.of(new AiMessage(question)), sessionName);
                    questionPanel.setText(fragment);
                    questionPanel.applyTheme(chrome.getTheme());

                    var questionScroll = new JScrollPane(questionPanel);
                    questionScroll.setPreferredSize(new Dimension(800, 300));
                    questionScroll.setBorder(new EmptyBorder(10, 10, 10, 10));
                    questionScroll.getVerticalScrollBar().setUnitIncrement(16);

                    /* --------- Custom buttons (create early for listeners) ---------- */
                    var okButton = new MaterialButton("OK");
                    SwingUtil.applyPrimaryButtonStyle(okButton);
                    var cancelButton = new MaterialButton("Cancel");

                    // Disable OK button initially (no choice selected)
                    okButton.setEnabled(false);

                    /* --------- Choice buttons (Radio button group) ------------------- */
                    var choicePanel = new JPanel();
                    choicePanel.setLayout(new BoxLayout(choicePanel, BoxLayout.Y_AXIS));
                    choicePanel.setBorder(new EmptyBorder(0, 20, 10, 20));

                    var buttonGroup = new ButtonGroup();

                    for (String choice : choices) {
                        var radioButton = new JRadioButton(choice);
                        radioButton.setActionCommand(choice);
                        radioButton.addActionListener(e -> okButton.setEnabled(true));
                        buttonGroup.add(radioButton);
                        choicePanel.add(radioButton);
                        choicePanel.add(Box.createVerticalStrut(4));
                    }

                    /* --------- Compose content ------------------------------------- */
                    var content = new JPanel(new BorderLayout(0, 8));
                    content.add(questionScroll, BorderLayout.CENTER);
                    content.add(choicePanel, BorderLayout.SOUTH);

                    /* --------- Dialog logic ---------------------------------------- */
                    final String[] resultHolder = {null};

                    // JOptionPane with custom buttons
                    var optionPane = new JOptionPane(
                            content,
                            JOptionPane.QUESTION_MESSAGE,
                            JOptionPane.DEFAULT_OPTION,
                            null,
                            new Object[] {okButton, cancelButton},
                            okButton);

                    // Create the dialog
                    var dialog = optionPane.createDialog(null, sessionName);
                    dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

                    // Action listener for OK button: read selected value directly from ButtonGroup
                    okButton.addActionListener(e -> {
                        var selection = buttonGroup.getSelection();
                        if (selection != null) {
                            resultHolder[0] = selection.getActionCommand();
                            dialog.dispose();
                        }
                    });

                    // Action listener for Cancel button
                    cancelButton.addActionListener(e -> {
                        resultHolder[0] = null;
                        dialog.dispose();
                    });

                    // Window listener for 'X' button close (behaves as Cancel)
                    dialog.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosing(WindowEvent windowEvent) {
                            resultHolder[0] = null;
                            dialog.dispose();
                        }
                    });

                    dialog.pack();
                    dialog.setLocationRelativeTo(null);
                    dialog.setVisible(true);

                    return resultHolder[0];
                },
                null);
    }

    private AskHumanDialog() {} // utility class; prevent instantiation
}
