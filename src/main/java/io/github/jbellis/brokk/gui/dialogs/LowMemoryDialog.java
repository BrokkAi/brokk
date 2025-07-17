package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.gui.SwingUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.NumberFormat;

/**
 * A dialog to inform the user about low memory conditions.
 */
public class LowMemoryDialog extends JDialog {

    private static final String DOCS_URL = "https://example.com/docs/memory-configuration";

    public LowMemoryDialog(@Nullable Frame owner) {
        super(owner, "Low Memory Warning", true);
        initUI();
    }

    private void initUI() {
        // Main panel with GridBagLayout for flexible component arrangement
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(new EmptyBorder(15, 20, 15, 20));
        GridBagConstraints gbc = new GridBagConstraints();

        // Warning Icon (using a standard Swing icon)
        JLabel iconLabel = new JLabel(UIManager.getIcon("OptionPane.warningIcon"));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridheight = 2; // Span two rows
        gbc.anchor = GridBagConstraints.NORTH;
        gbc.insets = new Insets(0, 0, 0, 15);
        mainPanel.add(iconLabel, gbc);

        // Main Message
        JLabel messageLabel = new JLabel("The IDE is running low on memory.");
        messageLabel.setFont(messageLabel.getFont().deriveFont(Font.BOLD));
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridheight = 1;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 5, 0);
        mainPanel.add(messageLabel, gbc);

        // Max Heap Info
        JLabel heapInfoLabel = new JLabel("Current limit (-Xmx): " + getMaxHeapSize());
        gbc.gridy = 1;
        mainPanel.add(heapInfoLabel, gbc);

        // Link to Documentation
        JLabel docsLink = createHyperlinkLabel();
        gbc.gridy = 2;
        gbc.insets = new Insets(10, 0, 10, 0);
        mainPanel.add(docsLink, gbc);

        // Button Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> dispose());
        buttonPanel.add(okButton);

        // Add panels to the dialog
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(mainPanel, BorderLayout.CENTER);
        getContentPane().add(buttonPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(getOwner()); // Center relative to the parent window
        setResizable(false);
    }

    /**
     * Fetches and formats the maximum heap size from the JVM.
     * @return A human-readable string like "2048 MB".
     */
    private String getMaxHeapSize() {
        try {
            long maxMemoryBytes = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
            if (maxMemoryBytes == Long.MAX_VALUE) {
                return "No Limit";
            }
            long maxMemoryMB = maxMemoryBytes / (1024 * 1024);
            return NumberFormat.getInstance().format(maxMemoryMB) + " MB";
        } catch (Exception e) {
            return "N/A";
        }
    }

    /**
     * Creates a JLabel that looks and acts like a hyperlink.
     */
    private JLabel createHyperlinkLabel() {
        JLabel linkLabel = new JLabel(
                "<html>Learn how to <a href=\"\">increase the memory limit</a>.</html>"
        );
        linkLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        linkLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new URI(DOCS_URL));
                } catch (IOException | URISyntaxException ex) {
                    JOptionPane.showMessageDialog(
                            LowMemoryDialog.this,
                            "Could not open the link.",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        });
        return linkLabel;
    }

    public static void showLowMemoryDialog(@Nullable Frame owner) {
        SwingUtil.runOnEdt(() -> new LowMemoryDialog(owner).setVisible(true));
    }
}