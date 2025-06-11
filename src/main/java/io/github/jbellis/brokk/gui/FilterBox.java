package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.gui.mop.ThemeColors;

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
    private String selected;                        // null == “nothing”

    private final JLabel textLabel;
    private final JLabel iconLabel;

    private static final Icon ARROW = UIManager.getIcon("Tree.expandedIcon");
    private static final Icon CLEAR = UIManager.getIcon("InternalFrame.closeIcon");

    // Colors will be set dynamically based on theme
    private Color unselectedFgColor;
    private Color selectedFgColor;
    private Color iconHoverBgColor;
    private final Chrome chrome;

    public FilterBox(Chrome chrome, String label, Supplier<List<String>> choices) {
        this(chrome, label, choices, null);
    }

    public FilterBox(Chrome chrome, String label, Supplier<List<String>> choices, String initialSelection) {
        this.chrome = chrome;
        this.label = label;
        this.choices = choices;

        // Initialize components
        textLabel = new JLabel(label);
        iconLabel = new JLabel(ARROW);

        // Layout
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6)); // Provides overall padding
        setOpaque(false); // Make panel transparent

        // Colors will be set in applyTheme
        textLabel.setOpaque(false);
        iconLabel.setOpaque(false);

        add(textLabel, BorderLayout.CENTER);
        add(iconLabel, BorderLayout.EAST);

        // Event Listeners
        iconLabel.addMouseListener(new MouseAdapter() {
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
                        iconLabel.setOpaque(true);
                        iconLabel.setBackground(iconHoverBgColor);
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (isEnabled()) {
                    iconLabel.setCursor(Cursor.getDefaultCursor());
                    if (selected != null) {
                        iconLabel.setOpaque(false);
                        iconLabel.setBackground(null);
                    }
                }
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!isEnabled()) return;

                // Only handle clicks outside the icon area - icon handles its own clicks
                Point p = e.getPoint();
                Rectangle iconBounds = iconLabel.getBounds();
                if (!iconBounds.contains(p)) {
                    showPopup();
                }
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                if (selected == null && isEnabled()) {
                    textLabel.setForeground(selectedFgColor);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (selected == null && isEnabled()) {
                    textLabel.setForeground(unselectedFgColor);
                }
            }
        });

        // Initial selection and icon state
        if (initialSelection != null) {
            List<String> actualChoices = choices.get();
            if (actualChoices != null && actualChoices.contains(initialSelection)) {
                this.selected = initialSelection;
                textLabel.setText(this.selected);
                // Color will be set in applyTheme
                iconLabel.setIcon(CLEAR);
                iconLabel.setBorder(BorderFactory.createEmptyBorder(0, Constants.H_GLUE, 0, 0));
            } else {
                // initialSelection not valid, leave as unselected
                iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            }
        } else {
            iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        }

        // Apply initial theme
        if (chrome.themeManager != null) {
            applyTheme(chrome.themeManager);
        }
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
            if (currentChoices == null) {
                currentChoices = List.of();
            }

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
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(search::requestFocusInWindow);
            }
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
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
        iconLabel.setIcon(CLEAR);
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, Constants.H_GLUE, 0, 0));
        // Reset hover state
        iconLabel.setOpaque(false);
        iconLabel.setBackground(null);
        firePropertyChange("value", old, v);
        // Swing components usually repaint correctly, explicit repaint might not be needed
        // repaint();
    }

    private void clear() {
        String old = selected;
        selected = null;
        textLabel.setText(label);
        iconLabel.setIcon(ARROW);
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
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
        // repaint();
    }

    public String getSelected() {
        return selected;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        boolean isDark = guiTheme.isDarkTheme();

        unselectedFgColor = ThemeColors.getColor(isDark, "filter_unselected_foreground");
        selectedFgColor = ThemeColors.getColor(isDark, "filter_selected_foreground");
        iconHoverBgColor = ThemeColors.getColor(isDark, "filter_icon_hover_background");

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
