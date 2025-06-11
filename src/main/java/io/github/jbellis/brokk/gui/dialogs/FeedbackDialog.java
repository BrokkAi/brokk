package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.gui.Chrome;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Modal dialog that gathers feedback details from the user and sends
 * them through Service.sendFeedback().
 */
public class FeedbackDialog extends JDialog {
    private final Chrome chrome;
    private final JComboBox<CategoryItem> categoryCombo;
    private final JTextArea feedbackArea;
    private final JCheckBox includeDebugLogCheckBox;
    private final JCheckBox includeScreenshotCheckBox;
    private final JButton sendButton;

    private record CategoryItem(String displayName, String value) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    public FeedbackDialog(Frame owner, Chrome chrome) {
        super(owner, "Send Feedback", true);
        this.chrome = chrome;

        categoryCombo = new JComboBox<>(new CategoryItem[] {
                new CategoryItem("Bug", "bug"),
                new CategoryItem("Feature Request", "feature_request"),
                new CategoryItem("Other", "other")
        });

        feedbackArea = new JTextArea(5, 40);
        feedbackArea.setLineWrap(true);
        feedbackArea.setWrapStyleWord(true);

        includeDebugLogCheckBox = new JCheckBox("Include debug log (~/.brokk/debug.log)");
        includeScreenshotCheckBox = new JCheckBox("Include screenshot");

        sendButton = new JButton("Send");
        sendButton.setMnemonic(KeyEvent.VK_S);
        sendButton.addActionListener(e -> send());

        var cancelButton = new JButton("Cancel");
        cancelButton.setMnemonic(KeyEvent.VK_C);
        cancelButton.addActionListener(e -> dispose());

        buildLayout(cancelButton);

        pack();
        setLocationRelativeTo(owner);
    }

    private void buildLayout(JButton cancelButton) {
        var form = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.FIRST_LINE_START;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Category
        gbc.gridx = 0; gbc.gridy = 0;
        form.add(new JLabel("Category:"), gbc);
        gbc.gridx = 1;
        form.add(categoryCombo, gbc);

        // Feedback label
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Feedback:"), gbc);

        // Feedback area
        gbc.gridx = 1;
        gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(feedbackArea), gbc);

        // Checkboxes
        gbc.gridx = 1; gbc.gridy = 2;
        gbc.weighty = 0; gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(includeDebugLogCheckBox, gbc);
        gbc.gridy = 3;
        form.add(includeScreenshotCheckBox, gbc);

        // Buttons
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancelButton);
        buttons.add(sendButton);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
    }

    private void send() {
        sendButton.setEnabled(false);

        var categoryItem = (CategoryItem) categoryCombo.getSelectedItem();
        var category = categoryItem.value();
        var feedbackText = feedbackArea.getText().trim();
        var includeDebugLog = includeDebugLogCheckBox.isSelected();
        var includeScreenshot = includeScreenshotCheckBox.isSelected();

        if (feedbackText.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                                          "Feedback text cannot be empty.",
                                          "Validation Error",
                                          JOptionPane.WARNING_MESSAGE);
            sendButton.setEnabled(true);
            return;
        }

        var service = chrome.getContextManager().getService();

        // Close dialog first, then capture screenshot and send feedback
        dispose();

        SwingUtilities.invokeLater(() -> {
            final File screenshotFile;
            if (includeScreenshot) {
                File tmp = null;
                try {
                    tmp = captureScreenshot(chrome.getFrame());
                } catch (IOException ex) {
                    chrome.toolError("Could not take screenshot: " + ex.getMessage());
                }
                screenshotFile = tmp;
            } else {
                screenshotFile = null;
            }

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    service.sendFeedback(category, feedbackText, includeDebugLog, screenshotFile);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get(); // propagate exception if any
                        JOptionPane.showMessageDialog(chrome.getFrame(),
                                                      "Thank you for your feedback!",
                                                      "Feedback Sent",
                                                      JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        chrome.toolError("Failed to send feedback: " + ex.getMessage());
                    } finally {
                        if (screenshotFile != null && screenshotFile.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            screenshotFile.delete();
                        }
                    }
                }
            }.execute();
        });
    }

    /**
     * Captures a screenshot of the given frame into a temporary PNG file.
     */
    private File captureScreenshot(Frame frame) throws IOException {
        var img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g2 = img.createGraphics();
        frame.paint(g2);
        g2.dispose();

        var tmp = File.createTempFile("brokk_screenshot_", ".png");
        ImageIO.write(img, "png", tmp);
        return tmp;
    }
}
