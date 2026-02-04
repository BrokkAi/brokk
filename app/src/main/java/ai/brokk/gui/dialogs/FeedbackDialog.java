package ai.brokk.gui.dialogs;

import ai.brokk.concurrent.LoggingFuture;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

/** Modal dialog that gathers feedback details from the user and sends them through Service.sendFeedback(). */
public class FeedbackDialog extends BaseThemedDialog {
    private final Chrome chrome;
    private final JComboBox<CategoryItem> categoryCombo;
    private final JTextArea feedbackArea;
    private final JCheckBox includeDebugLogCheckBox;
    private final JCheckBox includeScreenshotCheckBox;
    private final MaterialButton sendButton;
    private final MaterialButton closeButton;
    private final JLabel statusLabel;

    @Nullable
    private final BufferedImage screenshotImage;

    private final JLabel screenshotPreviewLabel;

    private record CategoryItem(String displayName, String value) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    public FeedbackDialog(Frame owner, Chrome chrome) {
        super(owner, "Send Feedback");
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

        sendButton = new MaterialButton("Send");
        sendButton.setMnemonic(KeyEvent.VK_S);
        sendButton.addActionListener(e -> send());
        // Apply the theme-aware primary button styling so the Send button appears as the primary action
        SwingUtil.applyPrimaryButtonStyle(sendButton);

        statusLabel = new JLabel();

        closeButton = new MaterialButton("Close");
        closeButton.setMnemonic(KeyEvent.VK_C);
        closeButton.addActionListener(e -> dispose());

        // Capture screenshot before the dialog is displayed
        BufferedImage captured = null;
        try {
            captured = captureScreenshotImage(chrome.getFrame());
        } catch (Exception ex) {
            chrome.toolError("Could not take screenshot: " + ex.getMessage());
        }
        screenshotImage = captured;

        // Build thumbnail preview
        screenshotPreviewLabel = new JLabel();
        if (screenshotImage != null) {
            var thumb = screenshotImage.getScaledInstance(
                    Math.min(200, screenshotImage.getWidth() / 4), -1, Image.SCALE_SMOOTH);
            screenshotPreviewLabel.setIcon(new ImageIcon(thumb));
            // Add a thin border that matches the current Look & Feel's focus color
            var focusColor = UIManager.getColor("Focus.color");
            if (focusColor == null) {
                focusColor = new Color(0x8ab4f8); // fallback color
            }
            screenshotPreviewLabel.setBorder(BorderFactory.createLineBorder(focusColor));
            screenshotPreviewLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            screenshotPreviewLabel.setToolTipText("Click to view screenshot");
            screenshotPreviewLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    showScreenshotDialog();
                }
            });
        }

        buildLayout(closeButton);

        pack();
        setLocationRelativeTo(owner);
    }

    private void buildLayout(JButton closeButton) {
        var form = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.FIRST_LINE_START;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Category
        gbc.gridx = 0;
        gbc.gridy = 0;
        form.add(new JLabel("Category:"), gbc);
        gbc.gridx = 1;
        form.add(categoryCombo, gbc);

        // Feedback label
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Feedback:"), gbc);

        // Feedback area
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(feedbackArea), gbc);

        // Checkboxes
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        form.add(includeDebugLogCheckBox, gbc);
        gbc.gridy = 3;
        form.add(includeScreenshotCheckBox, gbc);

        // Thumbnail preview
        gbc.gridy = 4;
        form.add(screenshotPreviewLabel, gbc);

        // Bottom row: status label on left, buttons on right
        var bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(statusLabel, BorderLayout.CENTER);
        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(closeButton);
        buttons.add(sendButton);
        bottomPanel.add(buttons, BorderLayout.EAST);

        JPanel root = getContentRoot();
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(form, BorderLayout.CENTER);
        root.add(bottomPanel, BorderLayout.SOUTH);
    }

    private void setInputsEnabled(boolean enabled) {
        categoryCombo.setEnabled(enabled);
        feedbackArea.setEnabled(enabled);
        includeDebugLogCheckBox.setEnabled(enabled);
        includeScreenshotCheckBox.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        closeButton.setEnabled(enabled);
    }

    private void send() {
        var categoryItem = (CategoryItem) categoryCombo.getSelectedItem();
        var category = categoryItem.value();
        var feedbackText = feedbackArea.getText().trim();
        var includeDebugLog = includeDebugLogCheckBox.isSelected();
        var includeScreenshot = includeScreenshotCheckBox.isSelected();

        if (feedbackText.isEmpty()) {
            statusLabel.setForeground(UIManager.getColor("Label.foreground"));
            statusLabel.setText("Feedback text cannot be empty.");
            return;
        }

        // Disable inputs and show initial status
        setInputsEnabled(false);
        statusLabel.setForeground(UIManager.getColor("Label.foreground"));
        if (includeScreenshot && screenshotImage != null) {
            statusLabel.setText("Processing screenshot...");
        } else {
            statusLabel.setText("Sending...");
        }

        var service = chrome.getContextManager().getService();

        // AtomicReference for safe cross-thread handoff of the temp file for cleanup
        var screenshotFileRef = new AtomicReference<File>();

        LoggingFuture.supplyCallableVirtual(() -> {
                    File screenshotFile = null;
                    if (includeScreenshot && screenshotImage != null) {
                        try {
                            screenshotFile = File.createTempFile("brokk_screenshot_", ".png");
                            screenshotFileRef.set(screenshotFile);
                            ImageIO.write(screenshotImage, "png", screenshotFile);
                        } catch (IOException ex) {
                            // Clean up failed temp file and ensure we don't pass corrupt file to sendFeedback
                            if (screenshotFile != null && screenshotFile.exists()) {
                                //noinspection ResultOfMethodCallIgnored
                                screenshotFile.delete();
                            }
                            screenshotFile = null;
                            SwingUtil.runOnEdt(() -> chrome.toolError("Could not save screenshot: " + ex.getMessage()));
                        }
                    }
                    // Update status to Sending before network call
                    SwingUtil.runOnEdt(() -> {
                        statusLabel.setForeground(UIManager.getColor("Label.foreground"));
                        statusLabel.setText("Sending...");
                    });
                    service.sendFeedback(category, feedbackText, includeDebugLog, screenshotFile);
                    return null;
                })
                .whenComplete((result, ex) -> SwingUtil.runOnEdt(() -> {
                    try {
                        if (ex != null) {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            String msg = Objects.requireNonNullElse(
                                    cause.getMessage(), cause.getClass().getSimpleName());
                            statusLabel.setForeground(new Color(0xCC0000));
                            statusLabel.setText("Failed to send feedback: " + msg);
                            setInputsEnabled(true);
                        } else {
                            statusLabel.setForeground(UIManager.getColor("Label.foreground"));
                            statusLabel.setText("Thank you for your feedback!");
                            closeButton.setEnabled(true);
                        }
                    } finally {
                        var tempFile = screenshotFileRef.get();
                        if (tempFile != null && tempFile.exists()) {
                            //noinspection ResultOfMethodCallIgnored
                            tempFile.delete();
                        }
                    }
                }));
    }

    /** Capture the current frame as a BufferedImage. */
    private static BufferedImage captureScreenshotImage(Frame frame) {
        var img = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g2 = img.createGraphics();
        frame.paint(g2);
        g2.dispose();
        return img;
    }

    /** Show the captured screenshot in a modal dialog at 50% scale. */
    private void showScreenshotDialog() {
        if (screenshotImage == null) {
            return;
        }
        var dialog = new BaseThemedDialog(this, "Screenshot Preview");
        var scaled = screenshotImage.getScaledInstance(
                screenshotImage.getWidth() / 2, screenshotImage.getHeight() / 2, Image.SCALE_SMOOTH);
        var imgLabel = new JLabel(new ImageIcon(scaled));
        var focusColor = UIManager.getColor("Focus.color");
        if (focusColor == null) {
            focusColor = UIManager.getColor("nimbusFocus");
        }
        if (focusColor == null) {
            focusColor = UIManager.getLookAndFeelDefaults().getColor("nimbusFocus");
        }
        if (focusColor == null) {
            focusColor = new Color(0x8ab4f8);
        }
        imgLabel.setBorder(BorderFactory.createLineBorder(focusColor));
        dialog.getContentRoot().add(new JScrollPane(imgLabel));
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
}
