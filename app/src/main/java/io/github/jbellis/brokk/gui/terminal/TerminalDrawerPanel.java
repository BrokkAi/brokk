package io.github.jbellis.brokk.gui.terminal;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.components.MaterialToggleButton;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
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
    private final MaterialToggleButton terminalToggle;
    private final MaterialToggleButton taskListToggle;
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

        // Create vertical icon bar on the EAST side
        drawerToolBar = new JPanel();
        drawerToolBar.setLayout(new BoxLayout(drawerToolBar, BoxLayout.Y_AXIS));
        drawerToolBar.setOpaque(false);
        drawerToolBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        terminalToggle = new MaterialToggleButton(Icons.TERMINAL);
        terminalToggle.setToolTipText("Toggle Terminal");
        terminalToggle.setBorderHighlightOnly(true);
        terminalToggle.setFocusPainted(false);
        terminalToggle.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        terminalToggle.setAlignmentX(Component.CENTER_ALIGNMENT);

        // Edge selection highlight is applied by MaterialToggleButton; LAF handles hover background.

        drawerToolBar.add(terminalToggle);

        // Task List toggle placed just south of the terminal toggle
        taskListToggle = new MaterialToggleButton(Icons.LIST);
        taskListToggle.setToolTipText("Toggle Task List");
        taskListToggle.setBorderHighlightOnly(true);
        taskListToggle.setFocusPainted(false);
        taskListToggle.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        taskListToggle.setAlignmentX(Component.CENTER_ALIGNMENT);

        drawerToolBar.add(taskListToggle);

        add(drawerToolBar, BorderLayout.EAST);

        // Content area for the drawer (where TerminalPanel and future tools will appear)
        drawerContentPanel = new JPanel(new BorderLayout());
        add(drawerContentPanel, BorderLayout.CENTER);

        // Wire the toggle to create/show/hide a TerminalPanel
        terminalToggle.addActionListener(ev -> {
            if (terminalToggle.isSelected()) {
                // Only one tool at a time
                if (taskListToggle.isSelected()) {
                    taskListToggle.setSelected(false);
                    hideTaskListDrawer();
                }
                openTerminal();
            } else {
                if (activeTerminal != null) {
                    hideTerminalDrawer();
                }
            }
        });

        // Wire the Task List toggle to create/show/hide the TaskListPanel
        taskListToggle.addActionListener(ev -> {
            if (taskListToggle.isSelected()) {
                // Only one tool at a time
                if (terminalToggle.isSelected()) {
                    terminalToggle.setSelected(false);
                    hideTerminalDrawer();
                }
                openTaskList();
            } else {
                if (activeTaskList != null) {
                    hideTaskListDrawer();
                }
            }
        });

        // Ensure drawer is initially collapsed (hides the split divider and reserves space for the toolbar).
        // Use invokeLater so parentSplitPane has valid size when collapseIfEmpty() runs.
        SwingUtilities.invokeLater(this::collapseIfEmpty);
    }

    /** Opens the terminal in the drawer. If already open, ensures it has focus. */
    public void openTerminal() {
        // Ensure only one tool is active at a time
        taskListToggle.setSelected(false);
        openTerminalAsync().exceptionally(ex -> {
            logger.debug("Failed to open terminal", ex);
            return null;
        });
    }

    /** Closes the terminal and collapses the drawer if empty. */
    public CompletableFuture<TerminalPanel> openTerminalAsync() {
        var promise = new CompletableFuture<TerminalPanel>();
        SwingUtilities.invokeLater(() -> {
            try {
                if (activeTerminal == null) {
                    createTerminal();
                } else {
                    showDrawer();
                    terminalToggle.setSelected(true);
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

            terminalToggle.setSelected(false);
            collapseIfEmpty();
        });
    }

    /** Opens the task list in the drawer. If already open, ensures it has focus. */
    public void openTaskList() {
        SwingUtilities.invokeLater(() -> {
            try {
                if (activeTaskList == null) {
                    activeTaskList = new TaskListPanel(console);
                }
                // Ensure mutual exclusivity with the terminal
                terminalToggle.setSelected(false);

                showDrawer();
                taskListToggle.setSelected(true);
            } catch (Exception ex) {
                logger.debug("Failed to open task list", ex);
                taskListToggle.setSelected(false);
            }
        });
    }

    /** Closes the task list and collapses the drawer if empty. */
    public void closeTaskList() {
        SwingUtilities.invokeLater(() -> {
            if (activeTaskList != null) {
                try {
                    drawerContentPanel.remove(activeTaskList);
                    drawerContentPanel.revalidate();
                    drawerContentPanel.repaint();
                } catch (Exception ex) {
                    logger.debug("Error removing task list panel", ex);
                }
            }
            taskListToggle.setSelected(false);
            collapseIfEmpty();
        });
    }

    private void hideTaskListDrawer() {
        SwingUtilities.invokeLater(() -> {
            if (activeTaskList != null) {
                drawerContentPanel.remove(activeTaskList);
                drawerContentPanel.revalidate();
                drawerContentPanel.repaint();
                collapseIfEmpty();
            }
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
            // Ensure only the selected tool is shown
            drawerContentPanel.removeAll();

            if (terminalToggle.isSelected() && activeTerminal != null) {
                drawerContentPanel.add(activeTerminal, BorderLayout.CENTER);
            } else if (taskListToggle.isSelected() && activeTaskList != null) {
                drawerContentPanel.add(activeTaskList, BorderLayout.CENTER);
            }

            drawerContentPanel.revalidate();
            drawerContentPanel.repaint();

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
                // Ensure the two sides are exactly equal (excluding divider)
                int half = (totalWidth - dividerSize) / 2;
                parentSplitPane.setDividerLocation(half);
            } else {
                // Use the stored proportion if available, otherwise default to 0.5
                if (locProp > 0.0 && locProp < 1.0) {
                    parentSplitPane.setDividerLocation(locProp);
                } else {
                    parentSplitPane.setDividerLocation(0.5);
                }
            }
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
                    final int MIN_COLLAPSE_WIDTH = toolbarWidth;

                    int totalWidth = parentSplitPane.getWidth();
                    if (totalWidth <= 0) {
                        // Not laid out yet; try again on the next event cycle
                        SwingUtilities.invokeLater(this::collapseIfEmpty);
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

    public void openTerminalAndPasteText(String text) {
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
            terminalToggle.setSelected(true);
            taskListToggle.setSelected(false);
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
