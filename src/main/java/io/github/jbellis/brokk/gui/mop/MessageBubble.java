package io.github.jbellis.brokk.gui.mop;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Base component for chat message panels with common styling and structure.
 * Provides a standardized layout with header (icon + title) and content area.
 */
public class MessageBubble extends JPanel {

    /**
     * A panel that draws a rounded background and a highlight bar on the left.
     * It contains the actual content component.
     */
        private static class RoundedHighlightPanel extends JPanel {
        private final Color backgroundColor;
        private final Color highlightColor;
        private final int arcSize;
        private final int highlightThickness;

        public RoundedHighlightPanel(Component content, Color backgroundColor, Color highlightColor,
                                           int arcSize, int highlightThickness, int padding) {
            super(new BorderLayout()); // Use BorderLayout to manage the content
            this.backgroundColor = backgroundColor;
            this.highlightColor = highlightColor;
            this.arcSize = arcSize;
            this.highlightThickness = highlightThickness;

            setOpaque(false); // We paint our own background + highlight

            // Set border to create padding *inside* the highlight bar and around content
            setBorder(BorderFactory.createEmptyBorder(padding, padding + highlightThickness, padding, padding));

            // Allow content to expand properly for centering
            if (content instanceof JComponent jComponent) {
                jComponent.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            }
            
            // Center JLabel
            if (content instanceof JLabel jLabel) {
                jLabel.setHorizontalAlignment(SwingConstants.CENTER);
            }
            
            add(content, BorderLayout.CENTER); // Add original content
        }

        @Override
        protected void paintComponent(Graphics g) {
            // Don't call super.paintComponent() because we are opaque=false and paint everything.
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
              int height = getHeight();
              
              // 1. Paint the rounded background using the painter (no stroke here)
              RoundRectPainter.paint(g2d, 0, 0, width, height, arcSize, backgroundColor, null, 0);
              
              // 2. Paint the left highlight bar, clipped to the rounded rectangle shape
              // Need to re-create the shape for clipping
              var roundRectClip = new RoundRectangle2D.Float(0, 0, width, height, arcSize, arcSize);
              g2d.setColor(highlightColor);
            // Use clip for safety at corners
              Shape oldClip = g2d.getClip();
              g2d.clip(roundRectClip);
              g2d.fillRect(0, 0, highlightThickness, height);
              g2d.setClip(oldClip); // Restore original clip
  
              g2d.dispose();

            // Children are painted after this method returns by the Swing painting mechanism
        }
    }

    /**
     * Creates a new base chat message panel with custom background and message colors.
     *
     * @param title            The title text to display in the header
     * @param iconText         Unicode icon text to display
     * @param contentComponent The main content component to display
     * @param isDarkTheme      Whether dark theme is active
     * @param highlightColor   The color to use for the left highlight bar
     * @param messageBgColor   The background color for the message (optional)
     * @param chatBgColor      The background color for the chat panel (optional)
     */
    public MessageBubble(String title, Icon iconText, Component contentComponent,
                         boolean isDarkTheme, Color highlightColor,
                         Color messageBgColor, Color chatBgColor)
    {
        initialize(title, iconText, contentComponent, isDarkTheme, highlightColor, 
                   messageBgColor, chatBgColor);
    }

    /**
     * Creates a new base chat message panel with custom message background color.
     *
     * @param title            The title text to display in the header
     * @param iconText         Unicode icon text to display
     * @param contentComponent The main content component to display
     * @param isDarkTheme      Whether dark theme is active
     * @param highlightColor   The color to use for the left highlight bar
     * @param messageBgColor   The background color for the message (optional)
     */
    public MessageBubble(String title, Icon iconText, Component contentComponent,
                         boolean isDarkTheme, Color highlightColor, Color messageBgColor)
    {
        initialize(title, iconText, contentComponent, isDarkTheme, highlightColor, 
                   messageBgColor, null);
    }

    /**
     * Creates a new base chat message panel with default colors.
     *
     * @param title            The title text to display in the header
     * @param iconText         Unicode icon text to display
     * @param contentComponent The main content component to display
     * @param isDarkTheme      Whether dark theme is active
     * @param highlightColor   The color to use for the left highlight bar
     */
    public MessageBubble(String title, Icon iconText, Component contentComponent,
                         boolean isDarkTheme, Color highlightColor)
    {
        initialize(title, iconText, contentComponent, isDarkTheme, highlightColor, 
                   null, null);
    }

    /**
     * Common initialization method for all constructors.
     */
    private void initialize(String title, Icon iconText, Component contentComponent,
                            boolean isDarkTheme, Color highlightColor,
                            Color customMessageBgColor, Color customChatBgColor)
    {
        setLayout(new BorderLayout());
        Color chatBgColor = customChatBgColor != null ? 
            customChatBgColor : 
            ThemeColors.getColor(isDarkTheme, "chat_background");
        setBackground(chatBgColor);
        // Overall padding for the entire message panel (header + content area)
        setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        setAlignmentX(Component.LEFT_ALIGNMENT);  // Also ensure *this* panel is left-aligned in its parent
        setAlignmentY(Component.TOP_ALIGNMENT);

        // Get theme colors
        Color messageBgColor = customMessageBgColor != null ? 
            customMessageBgColor : 
            ThemeColors.getColor(isDarkTheme, "message_background");

        // Create a panel for the content area
        JPanel contentArea = new JPanel();
        contentArea.setLayout(new BoxLayout(contentArea, BoxLayout.Y_AXIS));
        contentArea.setOpaque(false);
        contentArea.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Only add header if both title and iconText are not null
        if (title != null && iconText != null) {
            // Add a header row with icon and label
            JPanel headerPanel = new JPanel(new BorderLayout());
            headerPanel.setBackground(getBackground());
            headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

            // Create a simple panel with BoxLayout for the icon and title
            JPanel iconTitlePanel = new JPanel();
            iconTitlePanel.setLayout(new BoxLayout(iconTitlePanel, BoxLayout.X_AXIS));
            iconTitlePanel.setOpaque(false);

            // Icon
            JLabel iconLabel = new JLabel(iconText);
            iconLabel.setForeground(highlightColor);
            iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, 16f));
            iconTitlePanel.add(iconLabel);

            // Title
            JLabel titleLabel = new JLabel(" " + title);  // Add a space after the icon
            titleLabel.setForeground(highlightColor);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
            iconTitlePanel.add(titleLabel);

            // Add the panel with icon+title to the WEST position of the BorderLayout
            headerPanel.add(iconTitlePanel, BorderLayout.WEST);

            // Set minimum and preferred size for header panel to ensure it spans the full width
            headerPanel.setMinimumSize(new Dimension(Integer.MAX_VALUE, headerPanel.getPreferredSize().height));
            headerPanel.setPreferredSize(new Dimension(Integer.MAX_VALUE, headerPanel.getPreferredSize().height));

            // Add a horizontal glue to push everything to the left
            headerPanel.add(Box.createHorizontalGlue(), BorderLayout.CENTER);

            // Add header to the content area
            contentArea.add(headerPanel);

            // Add a small gap between header and content
            contentArea.add(Box.createRigidArea(new Dimension(0, 5)));
        }

        // Wrap the content component in our custom panel for rounded background + highlight
        int arcSize = 15;
        int highlightThickness = 4;
        int padding = 8;
        var contentWrapper = new RoundedHighlightPanel(
                contentComponent,
                messageBgColor,
                highlightColor,
                arcSize,
                highlightThickness,
                padding
        );
        // Center JLabel
        if (contentComponent instanceof JLabel) {
            contentWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Don't set a fixed preferred size - let it be determined by the content
            // Add a wrapper panel to handle centering
            JPanel centeringPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            centeringPanel.setOpaque(false);
            centeringPanel.add(contentWrapper);
            contentArea.add(centeringPanel);
        } else {
            contentArea.add(contentWrapper);
        }
        
        // Add the content area to the main panel
        add(contentArea, BorderLayout.CENTER);

        // Let this entire panel grow in both width and height if needed
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

}
