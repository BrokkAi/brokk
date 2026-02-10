package ai.brokk.gui.dialogs;

import ai.brokk.gui.BorderUtils;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.MaterialOptionPane;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.mop.MarkdownOutputPanel;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
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

        SwingUtil.runOnEdt(() -> {
            // Question (Markdown)
            var questionPanel = new MarkdownOutputPanel(true);
            questionPanel.setContextForLookups(chrome.getContextManager(), chrome);
            questionPanel.setStaticDocument(question);
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

            int result = MaterialOptionPane.showOptionDialog(
                    null,
                    content,
                    "Ask Human",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    new String[] {"OK", "Cancel"},
                    "OK");

            if (result == 0) { // OK
                String answer = answerArea.getText().trim();
                if (!answer.isEmpty()) {
                    resultRef.set(answer);
                }
            }
            latch.countDown();
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

        SwingUtil.runOnEdt(() -> {
            // Question (Markdown)
            var questionPanel = new MarkdownOutputPanel(true);
            questionPanel.setContextForLookups(chrome.getContextManager(), chrome);
            questionPanel.setStaticDocument(question);
            questionPanel.applyTheme(chrome.getTheme());

            var questionScroll = new JScrollPane(questionPanel);
            questionScroll.setPreferredSize(new Dimension(800, 300));
            questionScroll.setBorder(new EmptyBorder(10, 10, 10, 10));
            questionScroll.getVerticalScrollBar().setUnitIncrement(16);

            // Choice group
            var choicePanel = new JPanel();
            choicePanel.setLayout(new BoxLayout(choicePanel, BoxLayout.Y_AXIS));
            choicePanel.setBorder(new EmptyBorder(0, 20, 10, 20));

            var buttonGroup = new ButtonGroup();

            for (String choice : choices) {
                var radioButton = new JRadioButton(choice);
                radioButton.setActionCommand(choice);
                buttonGroup.add(radioButton);
                choicePanel.add(radioButton);
                choicePanel.add(Box.createVerticalStrut(4));
            }

            // Compose content
            var content = new JPanel(new BorderLayout(0, 8));
            content.add(questionScroll, BorderLayout.CENTER);
            content.add(choicePanel, BorderLayout.SOUTH);

            int result = MaterialOptionPane.showOptionDialog(
                    null,
                    content,
                    "Ask Human",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    new String[] {"OK", "Cancel"},
                    "OK");

            if (result == 0) { // OK
                var selection = buttonGroup.getSelection();
                if (selection != null) {
                    resultRef.set(selection.getActionCommand());
                }
            }
            latch.countDown();
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
     * Shows a simple text editing dialog and returns the result.
     * Blocks until the user clicks OK or Cancel.
     */
    @Blocking
    public static @Nullable String showEditDialog(Chrome chrome, String title, String initialText) {
        assert !SwingUtilities.isEventDispatchThread();

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> resultRef = new AtomicReference<>(null);

        SwingUtil.runOnEdt(() -> {
            var textArea = new JTextArea(initialText, 10, 50);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            var scroll = new JScrollPane(textArea);

            int result = MaterialOptionPane.showOptionDialog(
                    null,
                    scroll,
                    title,
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    new String[] {"OK", "Cancel"},
                    "OK");

            if (result == 0) { // OK
                resultRef.set(textArea.getText().trim());
            }
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return resultRef.get();
    }

    private AskHumanDialog() {} // utility class; prevent instantiation
}
