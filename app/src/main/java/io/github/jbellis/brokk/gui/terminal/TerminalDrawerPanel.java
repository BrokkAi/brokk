package io.github.jbellis.brokk.gui.terminal;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Component;
import javax.swing.Box;
import javax.swing.BoxLayout;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import io.github.jbellis.brokk.gui.components.MaterialToggleButton;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * A drawer panel that can host development tools like terminals and task lists. Uses a right-side JTabbedPane
 * (icon-only tabs) to switch between tools. The drawer collapses to the tab strip when no tool content is displayed.
 */
public class TerminalDrawerPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(TerminalDrawerPanel.class);

    // Core components
    private final JPanel drawerContentPanel;
  private final JPanel buttonBar;
  private final MaterialToggleButton terminalToggle;
  private final MaterialToggleButton tasksToggle;
  private @Nullable TerminalPanel activeTerminal;
  private @Nullable TaskListPanel activeTaskList;

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

        // Content area for the drawer (where TerminalPanel and future tools will appear)
        drawerContentPanel = new JPanel(new BorderLayout());
        add(drawerContentPanel, BorderLayout.CENTER);

        // Right-side vertical toggle buttons for tools
        buttonBar = new JPanel();
        buttonBar.setLayout(new BoxLayout(buttonBar, BoxLayout.Y_AXIS));
        buttonBar.setBorder(BorderFactory.createEmptyBorder());
        buttonBar.setPreferredSize(new Dimension(40, 0));

        terminalToggle = new MaterialToggleButton(Icons.TERMINAL);
        terminalToggle.setToolTipText("Terminal");
        terminalToggle.setFocusPainted(false);
        terminalToggle.setBorderHighlightOnly(true);
        terminalToggle.setAlignmentX(Component.CENTER_ALIGNMENT);
        {
            Dimension p = terminalToggle.getPreferredSize();
            terminalToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.height));
        }

        tasksToggle = new MaterialToggleButton(Icons.LIST);
        tasksToggle.setToolTipText("Task List");
        tasksToggle.setFocusPainted(false);
        tasksToggle.setBorderHighlightOnly(true);
        tasksToggle.setAlignmentX(Component.CENTER_ALIGNMENT);
        {
            Dimension p = tasksToggle.getPreferredSize();
            tasksToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.height));
        }

        // Add listeners after fields are initialized
        terminalToggle.addActionListener(e -> {
            if (terminalToggle.isSelected()) {
                tasksToggle.setSelected(false);
                // Show terminal
                drawerContentPanel.removeAll();
                if (activeTerminal == null) {
                    createTerminal();
                } else {
                    drawerContentPanel.add(activeTerminal, BorderLayout.CENTER);
                    drawerContentPanel.revalidate();
                    drawerContentPanel.repaint();
                    showDrawer();
                }
                if (activeTerminal != null) {
                    if (activeTerminal.isReady()) {
                        activeTerminal.requestFocusInTerminal();
                    } else {
                        activeTerminal.whenReady().thenAccept(t -> SwingUtilities.invokeLater(t::requestFocusInTerminal));
                    }
                }
            } else {
                // Hide terminal; if no other tool selected, collapse
                drawerContentPanel.removeAll();
                drawerContentPanel.revalidate();
                drawerContentPanel.repaint();
                if (tasksToggle.isSelected()) {
                    if (activeTaskList != null) {
                        drawerContentPanel.add(activeTaskList, BorderLayout.CENTER);
                        drawerContentPanel.revalidate();
                        drawerContentPanel.repaint();
                        showDrawer();
                    }
                } else {
                    collapseIfEmpty();
                }
            }
        });

        tasksToggle.addActionListener(e -> {
            if (tasksToggle.isSelected()) {
                terminalToggle.setSelected(false);
                // Show task list
                drawerContentPanel.removeAll();
                openTaskList();
            } else {
                // Hide task list; if no other tool selected, collapse
                drawerContentPanel.removeAll();
                drawerContentPanel.revalidate();
                drawerContentPanel.repaint();
                if (terminalToggle.isSelected()) {
                    if (activeTerminal != null) {
                        drawerContentPanel.add(activeTerminal, BorderLayout.CENTER);
                        drawerContentPanel.revalidate();
                        drawerContentPanel.repaint();
                        showDrawer();
                    }
                } else {
                    collapseIfEmpty();
                }
            }
        });

        buttonBar.add(terminalToggle);
        buttonBar.add(tasksToggle);
        buttonBar.add(Box.createVerticalGlue());

        add(buttonBar, BorderLayout.EAST);

        // Ensure drawer is initially collapsed (hides the split divider and reserves space for the button bar).
        SwingUtilities.invokeLater(this::collapseIfEmpty);
    }



    /** Opens the terminal in the drawer. If already open, ensures it has focus. */
    public void openTerminal() {
        openTerminalAsync().exceptionally(ex -> {
            logger.debug("Failed to open terminal", ex);
            return null;
        });
    }

    /** Opens the terminal and returns a future when it's ready (focused). */
    public CompletableFuture<TerminalPanel> openTerminalAsync() {
        var promise = new CompletableFuture<TerminalPanel>();
        SwingUtilities.invokeLater(() -> {
            try {
                terminalToggle.setSelected(true);
                tasksToggle.setSelected(false);

                drawerContentPanel.removeAll();
                if (activeTerminal == null) {
                    createTerminal();
                } else {
                    drawerContentPanel.add(activeTerminal, BorderLayout.CENTER);
                    drawerContentPanel.revalidate();
                    drawerContentPanel.repaint();
                    showDrawer();
                }

                var term = activeTerminal;
                if (term == null) {
                    promise.completeExceptionally(new IllegalStateException("Terminal not available"));
                    return;
                }

                if (term.isReady()) {
                    term.requestFocusInTerminal();
                    promise.complete(term);
                } else {
                    term.whenReady()
                            .thenAccept(t -> SwingUtilities.invokeLater(() -> {
                                t.requestFocusInTerminal();
                                promise.complete(t);
                            }))
                            .exceptionally(ex -> {
                                promise.completeExceptionally(ex);
                                return null;
                            });
                }
            } catch (Exception ex) {
                promise.completeExceptionally(ex);
            }
        });
        return promise;
    }

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

            if (tasksToggle.isSelected() && activeTaskList != null) {
                drawerContentPanel.removeAll();
                drawerContentPanel.add(activeTaskList, BorderLayout.CENTER);
                drawerContentPanel.revalidate();
                drawerContentPanel.repaint();
                showDrawer();
            } else {
                terminalToggle.setSelected(false);
                collapseIfEmpty();
            }
        });
    }

    /** Opens the task list in the drawer. If already open, ensures it has focus. */
    public TaskListPanel openTaskList() {
        assert SwingUtilities.isEventDispatchThread();
        tasksToggle.setSelected(true);
        terminalToggle.setSelected(false);
        if (activeTaskList == null) {
            activeTaskList = new TaskListPanel(console);
        }
        drawerContentPanel.add(activeTaskList, BorderLayout.CENTER);
        drawerContentPanel.revalidate();
        drawerContentPanel.repaint();
        showDrawer();
        return activeTaskList;
    }

    /** Shows the drawer by restoring the divider to its last known position. */
    public void showDrawer() {
        SwingUtilities.invokeLater(() -> {
            // Restore original divider size
            if (originalDividerSize > 0) {
                parentSplitPane.setDividerSize(originalDividerSize);
            }

            // Reset resize weight to default
            parentSplitPane.setResizeWeight(0.5);

            // Remove minimum size constraint from this drawer panel
            setMinimumSize(null);

            // Use pixel-precise divider positioning for the initial 50/50 case to avoid rounding bias
            parentSplitPane.revalidate();
            parentSplitPane.repaint();

            int totalWidth = parentSplitPane.getWidth();
            int dividerSize = parentSplitPane.getDividerSize();
            double locProp = lastDividerLocation;

            if (totalWidth > 0 && Math.abs(locProp - 0.5) < 1e-6) {
                int half = (totalWidth - dividerSize) / 2;
                parentSplitPane.setDividerLocation(half);
            } else {
                if (locProp > 0.0 && locProp < 1.0) {
                    parentSplitPane.setDividerLocation(locProp);
                } else {
                    parentSplitPane.setDividerLocation(0.5);
                }
            }
        });
    }

    /** Collapses the drawer if no tools are active, showing only the tab strip. */
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

                    // Calculate the minimum width needed for the side control bar
                    int tabStripWidth = buttonBar.getPreferredSize().width;
                    final int MIN_COLLAPSE_WIDTH = tabStripWidth;

                    int totalWidth = parentSplitPane.getWidth();
                    if (totalWidth <= 0) {
                        // Not laid out yet; try again on the next event cycle
                        SwingUtilities.invokeLater(this::collapseIfEmpty);
                        return;
                    }

                    // Set resize weight so left panel gets all extra space
                    parentSplitPane.setResizeWeight(1.0);

                    // Set minimum size on this drawer panel to keep control bar visible
                    setMinimumSize(new Dimension(MIN_COLLAPSE_WIDTH, 0));

                    // Position divider to show only the control bar
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

    /** Opens the drawer synchronously before first layout using a saved proportion. */
    public void openInitially(double proportion) {
        // Ensure the TerminalPanel exists
        if (activeTerminal == null) {
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
                activeTerminal = new TerminalPanel(console, this::closeTerminal, true, cwd);
                drawerContentPanel.removeAll();
                drawerContentPanel.add(activeTerminal, BorderLayout.CENTER);
            } catch (Exception ex) {
                logger.warn("Failed to create terminal in drawer: {}", ex.getMessage());
                return;
            }
        }

        // Restore original divider size and sane defaults
        if (originalDividerSize > 0) {
            parentSplitPane.setDividerSize(originalDividerSize);
        }
        parentSplitPane.setResizeWeight(0.5);
        setMinimumSize(null);

        // Apply saved proportion if valid, else fall back to 0.5
        double loc = (proportion > 0.0 && proportion < 1.0) ? proportion : 0.5;
        parentSplitPane.setDividerLocation(loc);

        // Reflect visible content
        terminalToggle.setSelected(true);
        tasksToggle.setSelected(false);

        revalidate();
        repaint();
    }

    public void openTerminalAndPasteText(String text) {
        // Ensure the Terminal is selected so it is visible
        terminalToggle.setSelected(true);
        tasksToggle.setSelected(false);
        openTerminalAsync()
                .thenAccept(tp -> {
                    try {
                        tp.pasteText(text);
                    } catch (Exception e) {
                        logger.debug("Error pasting text into terminal", e);
                    }
                })
                .exceptionally(ex -> {
                    logger.debug("Failed to open terminal and paste text", ex);
                    return null;
                });
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        if (activeTerminal != null) {
            activeTerminal.applyTheme(guiTheme);
        }
        if (activeTaskList != null) {
            activeTaskList.applyTheme(guiTheme);
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
        } catch (Exception ex) {
            logger.warn("Failed to create terminal in drawer: {}", ex.getMessage());
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
