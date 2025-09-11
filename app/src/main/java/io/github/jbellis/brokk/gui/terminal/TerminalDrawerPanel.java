package io.github.jbellis.brokk.gui.terminal;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A drawer panel that can host development tools like terminals. Currently supports a terminal that can be toggled
 * on/off. The drawer can be collapsed when no tools are active.
 */
public class TerminalDrawerPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(TerminalDrawerPanel.class);

    // Core components
    private final JPanel drawerContentPanel;
    private final JPanel drawerToolBar;
    private final JToggleButton terminalToggle;
    private @Nullable TerminalPanel activeTerminal;

    // Drawer state management
    private double lastDividerLocation = 0.5;
    private int originalDividerSize;

    // Dependencies
    private final IConsoleIO console;
    private final JSplitPane parentSplitPane;

    /**
     * Creates a new terminal drawer panel.
     *
     * @param console Console IO for terminal operations
     * @param parentSplitPane The split pane this drawer is part of
     */
    public TerminalDrawerPanel(IConsoleIO console, JSplitPane parentSplitPane) {
        super(new BorderLayout());
        this.console = console;
        this.parentSplitPane = parentSplitPane;
        this.originalDividerSize = parentSplitPane.getDividerSize();

        setBorder(BorderFactory.createEmptyBorder());

        // Create vertical icon bar on the EAST side
        drawerToolBar = new JPanel();
        drawerToolBar.setLayout(new BoxLayout(drawerToolBar, BoxLayout.Y_AXIS));
        drawerToolBar.setOpaque(false);
        drawerToolBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        terminalToggle = new JToggleButton(Icons.TERMINAL);
        terminalToggle.setToolTipText("Toggle Terminal");
        terminalToggle.setContentAreaFilled(false);
        terminalToggle.setFocusPainted(false);
        terminalToggle.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        terminalToggle.setAlignmentX(Component.CENTER_ALIGNMENT);
        drawerToolBar.add(terminalToggle);

        add(drawerToolBar, BorderLayout.EAST);

        // Content area for the drawer (where TerminalPanel and future tools will appear)
        drawerContentPanel = new JPanel(new BorderLayout());
        add(drawerContentPanel, BorderLayout.CENTER);

        // Wire the toggle to create/show/hide a TerminalPanel
        terminalToggle.addActionListener(ev -> {
            if (terminalToggle.isSelected()) {
                if (activeTerminal == null) {
                    createTerminal();
                } else {
                    showDrawer();
                    activeTerminal.requestFocusInTerminal();
                }
            } else {
                if (activeTerminal != null) {
                    hideTerminalDrawer();
                }
            }
        });
    }

    /** Opens the terminal in the drawer. If already open, ensures it has focus. */
    public void openTerminal() {
        SwingUtilities.invokeLater(() -> {
            if (!terminalToggle.isSelected()) {
                terminalToggle.doClick();
                return;
            }

            if (activeTerminal == null) {
                createTerminal();
            } else {
                showDrawer();
                activeTerminal.requestFocusInTerminal();
            }
        });
    }

    /** Closes the terminal and collapses the drawer if empty. */
    public void closeTerminal() {
        SwingUtilities.invokeLater(() -> {
            if (activeTerminal != null) {
                try {
                    activeTerminal.dispose();
                } catch (Exception ex) {
                    logger.debug("Error disposing drawer terminal", ex);
                }
                drawerContentPanel.remove(activeTerminal);
                drawerContentPanel.revalidate();
                drawerContentPanel.repaint();
                activeTerminal = null;
            }

            terminalToggle.setSelected(false);
            collapseIfEmpty();
        });
    }

    private void hideTerminalDrawer() {
        SwingUtilities.invokeLater(() -> {
            if (activeTerminal != null) {
                drawerContentPanel.remove(activeTerminal);
                drawerContentPanel.revalidate();
                drawerContentPanel.repaint();
                collapseIfEmpty();
            }
        });
    }

    /** Shows the drawer by restoring the divider to its last known position. */
    public void showDrawer() {
        SwingUtilities.invokeLater(() -> {
            if (activeTerminal != null && activeTerminal.getParent() == null) {
                drawerContentPanel.add(activeTerminal, BorderLayout.CENTER);
                drawerContentPanel.revalidate();
                drawerContentPanel.repaint();
            }

            // Restore original divider size
            if (originalDividerSize > 0) {
                parentSplitPane.setDividerSize(originalDividerSize);
            }

            // Reset resize weight to default
            parentSplitPane.setResizeWeight(0.5);

            // Remove minimum size constraint from this drawer panel
            setMinimumSize(null);

            // Use saved location if reasonable, otherwise default to 50/50 split
            double loc = lastDividerLocation;
            if (loc <= 0.0 || loc >= 1.0) {
                loc = 0.5;
            }

            parentSplitPane.setDividerLocation(loc);
        });
    }

    /** Collapses the drawer if no tools are active, showing only the toolbar. */
    public void collapseIfEmpty() {
        SwingUtilities.invokeLater(() -> {
            if (drawerContentPanel.getComponentCount() == 0) {
                try {
                    // Remember last divider location only if not already collapsed
                    int current = parentSplitPane.getDividerLocation();
                    int total = parentSplitPane.getWidth();
                    if (total > 0) {
                        double currentProp = (double) current / (double) total;
                        if (currentProp > 0.0 && currentProp < 0.95) {
                            lastDividerLocation = currentProp;
                        }
                    }

                    // Calculate the minimum width needed for the toolbar
                    int toolbarWidth = drawerToolBar.getPreferredSize().width;
                    final int MIN_COLLAPSE_WIDTH = Math.max(32, toolbarWidth + 8);

                    int totalWidth = parentSplitPane.getWidth();
                    if (totalWidth <= 0) {
                        return;
                    }

                    // Set resize weight so left panel gets all extra space
                    parentSplitPane.setResizeWeight(1.0);

                    // Set minimum size on this drawer panel to keep toolbar visible
                    setMinimumSize(new Dimension(MIN_COLLAPSE_WIDTH, 0));

                    // Position divider to show only the toolbar
                    int dividerLocation = totalWidth - MIN_COLLAPSE_WIDTH;
                    parentSplitPane.setDividerLocation(dividerLocation);

                    // Hide the divider
                    parentSplitPane.setDividerSize(0);

                    // Force layout update
                    parentSplitPane.revalidate();
                    parentSplitPane.repaint();
                } catch (Exception ex) {
                    logger.debug("Error collapsing drawer", ex);
                }
            }
        });
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        // Apply theme to active terminal if present
        if (activeTerminal != null) {
            activeTerminal.applyTheme(guiTheme);
        }
    }

    private void createTerminal() {
        try {
            Path cwd = null;
            if (console instanceof Chrome c) {
                var project = c.getProject();
                if (project != null) {
                    cwd = project.getRoot();
                }
            }
            if (cwd == null) {
                cwd = Path.of(System.getProperty("user.dir"));
            }
            var terminal = new TerminalPanel(console, this::closeTerminal, true, cwd);
            activeTerminal = terminal;
            drawerContentPanel.add(terminal, BorderLayout.CENTER);
            drawerContentPanel.revalidate();
            drawerContentPanel.repaint();
            showDrawer();
            terminalToggle.setSelected(true);
            terminal.requestFocusInTerminal();
        } catch (Exception ex) {
            logger.warn("Failed to create terminal in drawer: {}", ex.getMessage());
            terminalToggle.setSelected(false);
        }
    }

    /** Updates the terminal font size for the active terminal. */
    public void updateTerminalFontSize() {
        SwingUtilities.invokeLater(() -> {
            if (activeTerminal != null) {
                activeTerminal.updateTerminalFontSize();
            }
        });
    }
}
