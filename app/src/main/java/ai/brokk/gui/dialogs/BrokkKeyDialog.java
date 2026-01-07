package ai.brokk.gui.dialogs;

import ai.brokk.Brokk;
import ai.brokk.Service;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.components.BrowserLabel;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.project.MainProject;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLHandshakeException;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** Modal dialog that prompts the user for a Brokk Key and validates it before closing. */
public class BrokkKeyDialog extends BaseThemedDialog {
    private static final Logger logger = LogManager.getLogger(BrokkKeyDialog.class);
    private static final String ERROR_INVALID_KEY = "Invalid Brokk Key";
    private static final String ERROR_NETWORK = "Network error - please check your connection";
    private static final String ERROR_SSL = "SSL/TLS connection error - check proxy/firewall settings";

    private final JTextField keyField = new JTextField(30);
    private @Nullable String validatedKey = null;
    private @Nullable JLabel statusLabel;
    private @Nullable MaterialButton okBtn;
    private @Nullable MaterialButton cancelBtn;

    private BrokkKeyDialog(@Nullable Frame owner, @Nullable String initialKey, @Nullable String errorMessage) {
        super(owner, "Enter Brokk Key");
        Chrome.applyIcon(this);

        if (initialKey != null && !initialKey.isEmpty()) {
            keyField.setText(initialKey);
        }

        initComponents(errorMessage);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });
        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
    }

    private void initComponents(@Nullable String errorMessage) {
        JPanel root = getContentRoot();
        root.setLayout(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Icon
        var iconUrl = Brokk.class.getResource(Brokk.ICON_RESOURCE);
        if (iconUrl != null) {
            var icon = new ImageIcon(iconUrl);
            var image = icon.getImage().getScaledInstance(96, 96, Image.SCALE_SMOOTH);
            root.add(new JLabel(new ImageIcon(image)), BorderLayout.WEST);
        }

        // Center panel with instructions and key field
        var center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.PAGE_AXIS));
        var instructionLabel = new JLabel("Please enter your Brokk Key.");
        instructionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(instructionLabel);
        var linkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        linkRow.add(new JLabel("You can sign up for free at "));
        linkRow.add(new BrowserLabel("https://brokk.ai", "brokk.ai"));
        linkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(linkRow);
        center.add(Box.createVerticalStrut(8));

        var keyPanel = new JPanel(new BorderLayout(5, 0));
        keyPanel.add(new JLabel("Brokk Key:"), BorderLayout.WEST);
        keyPanel.add(keyField, BorderLayout.CENTER);
        keyPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(keyPanel);

        center.add(Box.createVerticalStrut(4));
        statusLabel = new JLabel(" ");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.ITALIC, 11f));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (errorMessage != null && !errorMessage.isBlank()) {
            statusLabel.setText(errorMessage);
            statusLabel.setForeground(Color.RED);
        }
        center.add(statusLabel);

        root.add(center, BorderLayout.CENTER);

        // Buttons
        okBtn = new MaterialButton("OK");
        cancelBtn = new MaterialButton("Cancel");

        okBtn.addActionListener(e -> submit());
        cancelBtn.addActionListener(e -> cancel());

        var btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);
        root.add(btnPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(okBtn);

        // Close dialog on ESC
        var esc = KeyStroke.getKeyStroke("ESCAPE");
        var inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var actionMap = getRootPane().getActionMap();
        inputMap.put(esc, "brokk.cancel");
        actionMap.put("brokk.cancel", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                cancel();
            }
        });
    }

    private void submit() {
        var key = keyField.getText().trim();
        if (key.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this, "Please enter a Brokk Key.", "Key Required", JOptionPane.WARNING_MESSAGE);
            keyField.requestFocusInWindow();
            return;
        }

        setInputEnabled(false);
        if (statusLabel != null) {
            statusLabel.setText("Validating...");
        }

        CompletableFuture.supplyAsync(() -> {
                    try {
                        Service.validateKey(key);
                        return null;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .whenComplete((result, error) -> SwingUtilities.invokeLater(() -> {
                    if (statusLabel != null) {
                        statusLabel.setText(" ");
                    }
                    if (error == null) {
                        MainProject.setBrokkKey(key);
                        validatedKey = key;
                        dispose();
                    } else {
                        logger.debug("Validation error", error);
                        var cause = error;
                        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                                && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        handleValidationError(cause);
                        setInputEnabled(true);
                    }
                }));
    }

    private void setInputEnabled(boolean enabled) {
        keyField.setEnabled(enabled);
        if (okBtn != null) okBtn.setEnabled(enabled);
        if (cancelBtn != null) cancelBtn.setEnabled(enabled);
    }

    private void handleValidationError(Throwable ex) {
        var root = ex;
        while (root.getCause() != null && root instanceof RuntimeException) {
            root = root.getCause();
        }

        String errorMessage;
        if (root instanceof IllegalArgumentException) {
            logger.warn("Invalid Brokk Key: {}", root.getMessage());
            errorMessage = ERROR_INVALID_KEY;
            keyField.requestFocusInWindow();
            keyField.selectAll();
        } else if (root instanceof SSLHandshakeException) {
            logger.warn("SSL error validating Brokk Key: {}", root.getMessage());
            errorMessage = ERROR_SSL;
        } else if (root instanceof IOException) {
            logger.warn("Network error validating Brokk Key: {}", root.getMessage());
            errorMessage = ERROR_NETWORK;
        } else {
            logger.error("Unexpected error validating Brokk Key", ex);
            var msg = root.getMessage();
            errorMessage = (msg == null || msg.isBlank())
                    ? "Unexpected error: " + root.getClass().getSimpleName()
                    : msg;
        }

        if (statusLabel != null) {
            statusLabel.setText(errorMessage);
            statusLabel.setForeground(Color.RED);
        }
    }

    private void cancel() {
        validatedKey = null;
        dispose();
    }

    /** Shows the dialog and returns the validated Brokk key, or {@code null} if the user cancelled. */
    public static @Nullable String showDialog(@Nullable Frame owner, @Nullable String initialKey) {
        return showDialog(owner, initialKey, null);
    }

    /**
     * Shows the dialog with an optional error message and returns the validated Brokk key,
     * or {@code null} if the user cancelled.
     */
    public static @Nullable String showDialog(
            @Nullable Frame owner, @Nullable String initialKey, @Nullable String errorMessage) {
        var dlg = new BrokkKeyDialog(owner, initialKey, errorMessage);
        dlg.setVisible(true); // modal; blocks
        return dlg.validatedKey;
    }
}
