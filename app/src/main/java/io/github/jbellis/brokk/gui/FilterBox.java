package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.gui.mop.ThemeColors;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;

public final class FilterBox extends JPanel implements ThemeAware {

    private final String label;                     // e.g. "Author"
    private final Supplier<List<String>> choices;   // lazy source of values
    private @Nullable String selected;             // null == “nothing”

    private final JLabel textLabel;
    private final JLabel iconLabel;

    private static final Icon ARROW_BASE = createConsistentSizedIcon(UIManager.getIcon("Tree.expandedIcon"));
    private static final Icon CLEAR_BASE = createConsistentSizedIcon(UIManager.getIcon("InternalFrame.closeIcon"));
    private static final Icon HOVER_ARROW = createHoverIcon(ARROW_BASE);
    private static final Icon HOVER_CLEAR = createHoverIcon(CLEAR_BASE);


    /**
     * Wraps an icon to ensure consistent 12x12 sizing for alignment
     */
    private static Icon createConsistentSizedIcon(Icon originalIcon) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                // Center the original icon within our consistent size
                int offsetX = (getIconWidth() - originalIcon.getIconWidth()) / 2;
                int offsetY = (getIconHeight() - originalIcon.getIconHeight()) / 2;

                // Use the component's foreground color for the icon
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(c.getForeground());
                originalIcon.paintIcon(c, g2, x + offsetX, y + offsetY);
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return 12; }

            @Override
            public int getIconHeight() { return 12; }
        };
    }

    /**
     * Creates a slightly larger version of an icon for hover effect
     */
    private static Icon createHoverIcon(Icon originalIcon) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setColor(c.getForeground());

                // Scale factor to make icon slightly larger
                double scaleFactor = 1.17; // 12px -> ~14px

                // Calculate center position for the scaled icon
                int iconWidth = getIconWidth();
                int iconHeight = getIconHeight();

                // Center the scaled icon
                int centerX = x + iconWidth / 2;
                int centerY = y + iconHeight / 2;

                // Apply scaling and draw centered
                g2.translate(centerX, centerY);
                g2.scale(scaleFactor, scaleFactor);
                g2.translate(-iconWidth/2, -iconHeight/2);

                originalIcon.paintIcon(c, g2, 0, 0);
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return 12; }

            @Override
            public int getIconHeight() { return 12; }
        };
    }

    // Colors will be set dynamically based on theme
    private Color unselectedFgColor = Color.BLACK; // Default initialization
    private Color selectedFgColor = Color.BLUE; // Default initialization
    private final Chrome chrome;

    public FilterBox(Chrome chrome, String label, Supplier<List<String>> choices) {
        this(chrome, label, choices, null);
    }

    public FilterBox(Chrome chrome, String label, Supplier<List<String>> choices, @Nullable String initialSelection) {
        this.chrome = chrome;
        this.label = label;
        this.choices = choices;

        // Initialize components
        textLabel = new JLabel(label);
        iconLabel = new JLabel(ARROW_BASE);

        // Layout - Use BorderLayout with proper spacing
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6)); // Provides overall padding
        setOpaque(false); // Make panel transparent

        // Colors will be set in applyTheme
        textLabel.setOpaque(false);
        iconLabel.setOpaque(false);

        // Set a minimum width for the text label to ensure consistent icon positioning
        textLabel.setPreferredSize(new Dimension(60, textLabel.getPreferredSize().height));
        textLabel.setHorizontalAlignment(SwingConstants.LEFT);

        // Set fixed size for icon label to ensure consistent positioning
        iconLabel.setPreferredSize(new Dimension(16, iconLabel.getPreferredSize().height));
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);

        // Add consistent spacing between text and icon
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, Constants.H_GLUE, 0, 0));

        add(textLabel, BorderLayout.CENTER);
        add(iconLabel, BorderLayout.EAST);

        // Event Listeners
        MouseAdapter iconLabelMouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!isEnabled()) return;

                if (selected != null) { // CLEAR icon is showing
                    clear();
                } else { // ARROW icon is showing
                    showPopup();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                if (isEnabled()) {
                    iconLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    if (selected != null) {
                        // Make icon slightly bigger on hover
                        iconLabel.setIcon(HOVER_CLEAR);
                        iconLabel.repaint();
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (isEnabled()) {
                    iconLabel.setCursor(Cursor.getDefaultCursor());
                    if (selected != null) {
                        // Restore normal icon size
                        iconLabel.setIcon(CLEAR_BASE);
                        iconLabel.repaint();
                    }
                }
            }
        };
        iconLabel.addMouseListener(iconLabelMouseAdapter);

        var textLabelMouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!isEnabled()) return;

                Point clickPointInPanel = e.getPoint();
                if (iconLabel.getBounds().contains(clickPointInPanel)) {
                    return; // Let iconLabelMouseAdapter handle icon clicks.
                }

                // If click was on text/panel area (not icon):
                if (selected == null) {
                    showPopup();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                if (isEnabled()) {
                    if (selected == null) {
                        textLabel.setForeground(selectedFgColor);
                        // Make arrow icon slightly bigger on hover
                        iconLabel.setIcon(HOVER_ARROW);
                        iconLabel.repaint();
                    } else {
                        // Make text color change more noticeable when filter is selected
                        textLabel.setForeground(unselectedFgColor);
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (isEnabled()) {
                    if (selected == null) {
                        textLabel.setForeground(unselectedFgColor);
                        // Restore normal arrow icon size
                        iconLabel.setIcon(ARROW_BASE);
                        iconLabel.repaint();
                    } else {
                        // Restore normal selected text color
                        textLabel.setForeground(selectedFgColor);
                    }
                }
            }
        };
        addMouseListener(textLabelMouseAdapter);

        // Initial selection and icon state
        if (initialSelection != null) {
            List<String> actualChoices = choices.get();
            if (actualChoices.contains(initialSelection)) {
                this.selected = initialSelection;
                textLabel.setText(this.selected);
                iconLabel.setIcon(CLEAR_BASE);
            }
        }
        // Apply initial theme
        applyTheme(chrome.themeManager);
    }

    /* ----------  popup logic  ---------- */

    private void showPopup() {
        JPopupMenu pop = new JPopupMenu();
        chrome.themeManager.registerPopupMenu(pop);

        JTextField search = new JTextField();
        // Add search field first, so it's always at the top
        pop.add(search);

        // Method to populate/re-populate menu items based on search query
        Runnable populateMenuItems = () -> {
            // Remove all items except the search field
            Component[] components = pop.getComponents();
            for (int i = components.length - 1; i >= 0; i--) {
                if (components[i] != search) {
                    pop.remove(i);
                }
            }

            String q = search.getText().toLowerCase(Locale.ROOT);
            List<String> currentChoices = choices.get();

            currentChoices.stream()
                    .filter(s -> s.toLowerCase(Locale.ROOT).contains(q))
                    .forEach(choice -> {
                        JMenuItem item = new JMenuItem(choice);
                        item.addActionListener(ae -> {
                            choose(choice);
                            pop.setVisible(false);
                            // Ensure focus returns to a sensible component, e.g., the FilterBox itself
                            // or allow the system to decide. Forcing focus can sometimes be tricky.
                            // requestFocusInWindow(); // Or another component if more appropriate
                        });
                        pop.add(item);
                    });

            // If the popup is visible, repack and repaint to reflect changes
            if (pop.isVisible()) {
                pop.pack();
                pop.repaint();
                // Ensure the popup remains within screen bounds after repacking
                Point location = pop.getLocationOnScreen();
                Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
                int newX = location.x;
                int newY = location.y;
                if (location.x + pop.getWidth() > screenSize.width) {
                    newX = screenSize.width - pop.getWidth();
                }
                if (location.y + pop.getHeight() > screenSize.height) {
                    newY = screenSize.height - pop.getHeight();
                }
                if (newX < 0) newX = 0;
                if (newY < 0) newY = 0;
                if (newX != location.x || newY != location.y) {
                    pop.setLocation(newX, newY);
                }
            }
        };

        // Initial population
        populateMenuItems.run();

        // Live filtering
        search.getDocument().addDocumentListener((SimpleListener) e -> populateMenuItems.run());

        // Handle Escape key to close popup and Enter key in search field to select first item (if any)
        search.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    pop.setVisible(false);
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    // Find the first JMenuItem after the search field
                    Optional<JMenuItem> firstItem = Arrays.stream(pop.getComponents())
                                                          .filter(JMenuItem.class::isInstance)
                                                          .map(JMenuItem.class::cast)
                                                          .findFirst();
                    if (firstItem.isPresent()) {
                        firstItem.get().doClick(); // Simulate a click
                    } else {
                        // If no items match, pressing enter might just close the popup or do nothing
                        pop.setVisible(false);
                    }
                }
            }
        });

        // Add a popup menu listener to request focus on the search field when the popup becomes visible.
        pop.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(search::requestFocusInWindow);
            }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        pop.show(this, 0, getHeight());
        // requestFocusInWindow() is now handled by the PopupMenuListener
    }

    private void choose(String v) {
        String old = selected;
        selected = v;
        textLabel.setText(v);
        textLabel.setForeground(selectedFgColor);
        iconLabel.setIcon(CLEAR_BASE);
        // Border remains consistent - already set in constructor
        // Reset hover state
        iconLabel.setOpaque(false);
        iconLabel.setBackground(null);
        firePropertyChange("value", old, v);
    }

    private void clear() {
        String old = selected;
        selected = null;
        textLabel.setText(label);
        iconLabel.setIcon(ARROW_BASE);
        // Border remains consistent - already set in constructor
        // Reset hover state
        iconLabel.setOpaque(false);
        iconLabel.setBackground(null);

        // Check if mouse is currently over the component to set hover color correctly
        Point mousePos = getMousePosition(true); // Get position relative to this component, even if not directly over
        boolean isMouseOver = mousePos != null && contains(mousePos);

        if (isMouseOver && isEnabled()) {
            textLabel.setForeground(selectedFgColor); // Hover color
        } else {
            textLabel.setForeground(unselectedFgColor); // Default unselected color
        }
        firePropertyChange("value", old, null);
    }

    public @Nullable String getSelected() {
        return selected;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        boolean isDark = guiTheme.isDarkTheme();

        unselectedFgColor = ThemeColors.getColor(isDark, "filter_unselected_foreground");
        selectedFgColor = ThemeColors.getColor(isDark, "filter_selected_foreground");

        // Apply current state colors
        if (selected == null) {
            // Check if mouse is over for hover state
            Point mousePos = getMousePosition(true);
            boolean isMouseOver = mousePos != null && contains(mousePos);
            textLabel.setForeground(isMouseOver && isEnabled() ? selectedFgColor : unselectedFgColor);
        } else {
            textLabel.setForeground(selectedFgColor);
        }

        SwingUtilities.updateComponentTreeUI(this);
    }

    // SimpleListener is now a top-level package-private interface in its own file.
}
