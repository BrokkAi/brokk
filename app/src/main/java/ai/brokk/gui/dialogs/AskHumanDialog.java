package ai.brokk.gui.dialogs;

import ai.brokk.context.ContextFragments;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a markdown-rendered question to the human and returns the answer text. Returns {@code null} if the user
 * cancels, closes the dialog, or provides an empty answer.
 */
public final class AskHumanDialog {

    /**
     * Blocking dialog that shows a markdown-rendered question and returns the user's free-form answer.
     *
     * Threading contract:
     * - This method blocks until the user responds or cancels.
     * - Do NOT call from the Swing EDT; doing so will deadlock because the dialog is modeless and this call waits on a latch.
     *
     * @param chrome Chrome instance for theme and context
     * @param question markdown-formatted question text
     * @return the user's answer, or null if canceled/closed
     */
    @Blocking
    public static @Nullable String ask(Chrome chrome, String question) {
        assert !SwingUtilities.isEventDispatchThread()
                : "AskHumanDialog.ask() must not be called on the EDT; it blocks waiting for user input.";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<>(null);
        final AtomicBoolean completed = new AtomicBoolean(false);

        SwingUtil.runOnEdt(() -> {
            String sessionName = "Ask Human";

            // Question (Markdown)
            var questionPanel = new MarkdownOutputPanel(true);
            questionPanel.withContextForLookups(chrome.getContextManager(), chrome);
            var fragment = new ContextFragments.TaskFragment(
                    chrome.getContextManager(), List.of(new AiMessage(question)), sessionName);
            questionPanel.setText(fragment);
            questionPanel.applyTheme(chrome.getTheme());

            var questionScroll = new JScrollPane(questionPanel);
            questionScroll.setPreferredSize(new Dimension(800, 400));
            questionScroll.setBorder(new EmptyBorder(10, 10, 10, 10));
            questionScroll.getVerticalScrollBar().setUnitIncrement(16);

            // Answer area
            var answerArea = new JTextArea(6, 60);
            answerArea.setLineWrap(true);
            answerArea.setWrapStyleWord(true);
            BorderUtils.addFocusBorder(answerArea, answerArea);

            var answerScroll = new JScrollPane(answerArea);
            answerScroll.setBorder(new EmptyBorder(0, 10, 10, 10));

            // Compose content
            var content = new JPanel(new BorderLayout(0, 8));
            content.add(questionScroll, BorderLayout.CENTER);
            content.add(answerScroll, BorderLayout.SOUTH);

            // Buttons
            var okButton = new MaterialButton("OK");
            SwingUtil.applyPrimaryButtonStyle(okButton);
            var cancelButton = new MaterialButton("Cancel");

            okButton.setEnabled(false);

            // Enable OK only when non-empty
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
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    new Object[] {okButton, cancelButton},
                    okButton);

            // Create a modeless dialog; do not block EDT
            var dialog = optionPane.createDialog(null, sessionName);
            dialog.setModal(false);
            dialog.setResizable(true);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

            // OK action
            okButton.addActionListener(e -> {
                String answer = answerArea.getText().trim();
                if (!answer.isEmpty()) {
                    resultRef.set(answer);
                    if (completed.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                    dialog.dispose();
                }
            });

            // Cancel action
            cancelButton.addActionListener(e -> {
                resultRef.set(null);
                if (completed.compareAndSet(false, true)) {
                    latch.countDown();
                }
                dialog.dispose();
            });

            // Window close action behaves like cancel
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent windowEvent) {
                    resultRef.set(null);
                    if (completed.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                    dialog.dispose();
                }
            });

            dialog.pack();
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
        });

        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return resultRef.get();
    }

    /**
     * Blocking dialog that shows a markdown-rendered question with multiple-choice options.
     *
     * Threading contract:
     * - This method blocks until the user responds or cancels.
     * - Do NOT call from the Swing EDT; doing so will deadlock because the dialog is modeless and this call waits on a latch.
     *
     * @param chrome the Chrome instance for theme and context
     * @param question the markdown-formatted question to display
     * @param choices list of choice strings to present as radio button options
     * @return the selected choice string, or null if cancelled
     */
    @Blocking
    public static @Nullable String askWithChoices(Chrome chrome, String question, List<String> choices) {
        assert !SwingUtilities.isEventDispatchThread()
                : "AskHumanDialog.askWithChoices() must not be called on the EDT; it blocks waiting for user input.";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<>(null);
        final AtomicBoolean completed = new AtomicBoolean(false);

        SwingUtil.runOnEdt(() -> {
            String sessionName = "Ask Human";

            // Question (Markdown)
            var questionPanel = new MarkdownOutputPanel(true);
            questionPanel.withContextForLookups(chrome.getContextManager(), chrome);
            var fragment = new ContextFragments.TaskFragment(
                    chrome.getContextManager(), List.of(new AiMessage(question)), sessionName);
            questionPanel.setText(fragment);
            questionPanel.applyTheme(chrome.getTheme());

            var questionScroll = new JScrollPane(questionPanel);
            questionScroll.setPreferredSize(new Dimension(800, 300));
            questionScroll.setBorder(new EmptyBorder(10, 10, 10, 10));
            questionScroll.getVerticalScrollBar().setUnitIncrement(16);

            // Buttons (create early for listeners)
            var okButton = new MaterialButton("OK");
            SwingUtil.applyPrimaryButtonStyle(okButton);
            var cancelButton = new MaterialButton("Cancel");

            okButton.setEnabled(false);

            // Choice group
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

            // Compose content
            var content = new JPanel(new BorderLayout(0, 8));
            content.add(questionScroll, BorderLayout.CENTER);
            content.add(choicePanel, BorderLayout.SOUTH);

            // JOptionPane with custom buttons
            var optionPane = new JOptionPane(
                    content,
                    JOptionPane.QUESTION_MESSAGE,
                    JOptionPane.DEFAULT_OPTION,
                    null,
                    new Object[] {okButton, cancelButton},
                    okButton);

            var dialog = optionPane.createDialog(null, sessionName);
            dialog.setModal(false);
            dialog.setResizable(true);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

            // OK action: read selection
            okButton.addActionListener(e -> {
                var selection = buttonGroup.getSelection();
                if (selection != null) {
                    resultRef.set(selection.getActionCommand());
                    if (completed.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                    dialog.dispose();
                }
            });

            // Cancel action
            cancelButton.addActionListener(e -> {
                resultRef.set(null);
                if (completed.compareAndSet(false, true)) {
                    latch.countDown();
                }
                dialog.dispose();
            });

            // Window close action behaves like cancel
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent windowEvent) {
                    resultRef.set(null);
                    if (completed.compareAndSet(false, true)) {
                        latch.countDown();
                    }
                    dialog.dispose();
                }
            });

            dialog.pack();
            dialog.setLocationRelativeTo(null);
            dialog.setVisible(true);
        });

        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        return resultRef.get();
    }

    private AskHumanDialog() {} // utility class; prevent instantiation
}
