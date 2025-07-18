package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLHandshakeException;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;

/**
 * Modal dialog that prompts the user for a Brokk Key and validates it before closing.
 */
public class BrokkKeyDialog extends JDialog
{
    private static final Logger logger = LogManager.getLogger(BrokkKeyDialog.class);

    private final JTextField keyField = new JTextField(30);
    private @Nullable String validatedKey = null;

    private BrokkKeyDialog(@Nullable Frame owner, @Nullable String initialKey)
    {
        super(owner, "Enter Brokk Key", true);
        io.github.jbellis.brokk.gui.Chrome.applyIcon(this);

        if (initialKey != null && !initialKey.isEmpty())
        {
            keyField.setText(initialKey);
        }

        initComponents();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter()
        {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e)
            {
                cancel();
            }
        });
        pack();
        setLocationRelativeTo(owner);
        setResizable(false);
    }

    private void initComponents()
    {
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Icon
        var iconUrl = Brokk.class.getResource(Brokk.ICON_RESOURCE);
        if (iconUrl != null)
        {
            var icon = new ImageIcon(iconUrl);
            var image = icon.getImage().getScaledInstance(96, 96, Image.SCALE_SMOOTH);
            add(new JLabel(new ImageIcon(image)), BorderLayout.WEST);
        }

        // Center panel with instructions and key field
        var center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.PAGE_AXIS));
        center.add(new JLabel("<html>Please enter your Brokk Key.<br>You can sign up for free at <a href=\"https://brokk.ai\">brokk.ai</a></html>"));
        center.add(Box.createVerticalStrut(8));

        var keyPanel = new JPanel(new BorderLayout(5, 0));
        keyPanel.add(new JLabel("Brokk Key:"), BorderLayout.WEST);
        keyPanel.add(keyField, BorderLayout.CENTER);
        center.add(keyPanel);

        add(center, BorderLayout.CENTER);

        // Buttons
        var okBtn = new JButton("OK");
        var cancelBtn = new JButton("Cancel");

        okBtn.addActionListener(e -> submit());
        cancelBtn.addActionListener(e -> cancel());

        var btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(cancelBtn);
        btnPanel.add(okBtn);
        add(btnPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(okBtn);
    }

    private void submit()
    {
        var key = keyField.getText().trim();
        if (key.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Please enter a Brokk Key.", "Key Required", JOptionPane.WARNING_MESSAGE);
            keyField.requestFocusInWindow();
            return;
        }

        try
        {
            Service.validateKey(key);
            MainProject.setBrokkKey(key);
            validatedKey = key;
            dispose();
        }
        catch (IllegalArgumentException ex)
        {
            logger.warn("Invalid Brokk Key: {}", ex.getMessage());
            JOptionPane.showMessageDialog(this, "Invalid Brokk Key: " + ex.getMessage(), "Invalid Key", JOptionPane.ERROR_MESSAGE);
            keyField.requestFocusInWindow();
            keyField.selectAll();
        }
        catch (SSLHandshakeException ex)
        {
            logger.warn("SSL error validating Brokk Key: {}", ex.getMessage());
            JOptionPane.showMessageDialog(this, """
                                                 Unable to connect to Brokk services. This often happens behind a corporate proxy/firewall that intercepts TLS.
                                                 Ensure your OS trust-store trusts any required corporate certificates.
                                                 """, "Connection Issue", JOptionPane.ERROR_MESSAGE);
        }
        catch (IOException ex)
        {
            logger.warn("Network error validating Brokk Key: {}", ex.getMessage());
            JOptionPane.showMessageDialog(this, "Network error validating key: " + ex.getMessage(), "Network Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cancel()
    {
        validatedKey = null;
        dispose();
    }

    /**
     * Shows the dialog and returns the validated Brokk key, or {@code null} if the user cancelled.
     */
    public static @Nullable String showDialog(@Nullable Frame owner, @Nullable String initialKey)
    {
        var dlg = new BrokkKeyDialog(owner, initialKey);
        dlg.setVisible(true);              // modal; blocks
        return dlg.validatedKey;
    }
}
