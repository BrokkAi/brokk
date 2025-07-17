package io.github.jbellis.brokk.gui.components;

import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;

/**
 * A non-modal, IntelliJ-style notification popup using a JWindow.
 */
public class NotificationPopup extends JWindow {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final int hideDelayMs;

    private NotificationPopup(Frame owner, @NonNull String title, @NonNull String body, @NonNull Optional<String> optionalUrlText, @NonNull Optional<URL> optionalUrl, int hideDelayMs) {
        super(owner);
        this.hideDelayMs = hideDelayMs;
        initUI(title, body, optionalUrlText, optionalUrl);
    }

    private void initUI(@NonNull String title, @NonNull String body, @NonNull Optional<String> optionalUrlText, @NonNull Optional<URL> optionalUrl) {
        // Main panel with a border and background color for a modern look
        final var mainPanel = new JPanel(new GridBagLayout());
        final var backgroundColor = UIManager.getColor("Panel.background");
        mainPanel.setBackground(backgroundColor == null ? new Color(144, 144, 144) : backgroundColor);
        // A compound border gives an outer line and inner padding
        mainPanel.setBorder(new CompoundBorder(
                new MatteBorder(1, 1, 1, 1, Color.GRAY), // Outer border
                new EmptyBorder(10, 15, 10, 15)   // Inner padding
        ));

        final var gbc = new GridBagConstraints();

        // Warning Icon
        JLabel iconLabel = new JLabel(UIManager.getIcon("OptionPane.warningIcon"));
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridheight = 2;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.insets = new Insets(5, 0, 0, 15);
        mainPanel.add(iconLabel, gbc);

        // Main Message with HTML for better text wrapping and styling
        // Using HTML with a width style is a classic Swing trick to control component size.
        String message = String.format(
                "<html><body style='width: 280px;'>" +
                        "<b>%s</b><br>" +
                        "%s." +
                        "</body></html>",
                title,
                body
        );
        final var messageLabel = new JLabel(message);
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridheight = 1;
        gbc.weightx = 1.0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 5, 20); // Make room for close button
        mainPanel.add(messageLabel, gbc);

        // Link to Documentation
        if (optionalUrlText.isPresent() && optionalUrl.isPresent()) {
            JLabel docsLink = createHyperlinkLabel(optionalUrlText.get(), optionalUrl.get());
            gbc.gridy = 1;
            gbc.anchor = GridBagConstraints.SOUTHWEST;
            mainPanel.add(docsLink, gbc);
        }

        // Close button
        final var closeButton = new JButton("X");
        closeButton.setMargin(new Insets(1, 4, 1, 4));
        closeButton.setFocusable(false);
        closeButton.addActionListener(e -> dispose());
        gbc.gridx = 2;
        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.NORTHEAST;
        gbc.insets = new Insets(0, 0, 0, 0);
        mainPanel.add(closeButton, gbc);

        // Add the styled panel to the JWindow
        add(mainPanel);

        // --- Positioning and Sizing ---
        pack(); // Size the window based on its content

        // Position in the bottom-right corner of the owner frame
        if (getOwner() != null && getOwner().isVisible()) {
            final int margin = 15;
            int ownerX = getOwner().getX();
            int ownerY = getOwner().getY();
            int ownerWidth = getOwner().getWidth();
            int ownerHeight = getOwner().getHeight();
            int dialogX = ownerX + ownerWidth - getWidth() - margin;
            int dialogY = ownerY + ownerHeight - getHeight() - margin;
            setLocation(dialogX, dialogY);
        } else {
            setLocationRelativeTo(null); // Fallback
        }

        // --- Auto-hide Timer ---
        // The popup will automatically close after a delay
        Timer timer = new Timer(hideDelayMs, e -> dispose());
        timer.setRepeats(false);
        timer.start();
    }

    private JLabel createHyperlinkLabel(String text, @NonNull URL url) {
        JLabel linkLabel = new JLabel(String.format("<html><a href=''>%s</a></html>", text));
        linkLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        linkLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(url.toURI());
                } catch (IOException | URISyntaxException ex) {
                    logger.error("Unable to browse user to the link {}!", url, ex);
                }
            }
        });
        return linkLabel;
    }

    public static class NotificationPopupBuilder {

        private final Logger logger = LoggerFactory.getLogger(this.getClass());

        private final Frame owner;
        private final String title;
        private final String body;
        private Optional<URL> optionalUrl = Optional.empty();
        private Optional<String> optionalUrlText = Optional.empty();
        private int hideDelayMs = Integer.MAX_VALUE; // persist notification by default

        public NotificationPopupBuilder(@NonNull Frame owner, @NonNull String title, @NonNull String body) {
            this.owner = owner;
            this.title = title;
            this.body = body;
        }

        public NotificationPopupBuilder hideAfterDelayMs(int hideDelayMs) {
            this.hideDelayMs = hideDelayMs;
            return this;
        }

        public NotificationPopupBuilder optionalUrl(@NonNull String text, @NonNull URL url) {
            this.optionalUrlText = Optional.of(text);
            this.optionalUrl = Optional.of(url);
            return this;
        }


        public NotificationPopup build() {
            logger.debug("Built and showing notification popup with title '{}'", title);
            return new NotificationPopup(owner, title, body, optionalUrlText, optionalUrl, hideDelayMs);
        }

    }

}
