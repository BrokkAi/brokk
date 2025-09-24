package io.github.jbellis.brokk.gui.terminal;

import io.github.jbellis.brokk.IConsoleIO;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
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
    private final JTabbedPane sideTabs;
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

        // Side tab strip on the EAST edge with icon-only tabs
        sideTabs = new JTabbedPane(JTabbedPane.RIGHT);
        sideTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        sideTabs.addTab(null, Icons.TERMINAL, createTabPlaceholder());
        sideTabs.setToolTipTextAt(0, "Terminal");
        sideTabs.addTab(null, Icons.LIST, createTabPlaceholder());
        sideTabs.setToolTipTextAt(1, "Task List");
        sideTabs.addChangeListener(e -> handleTabSelection());
        add(sideTabs, BorderLayout.EAST);

        // Content area for the drawer (where TerminalPanel and future tools will appear)
        drawerContentPanel = new JPanel(new BorderLayout());
        add(drawerContentPanel, BorderLayout.CENTER);

        // Ensure drawer is initially collapsed (hides the split divider and reserves space for the tab strip).
        SwingUtilities.invokeLater(this::collapseIfEmpty);
    }

    private JPanel createTabPlaceholder() {
        JPanel p = new JPanel();
        p.setPreferredSize(new Dimension(0, 0));
        p.setMinimumSize(new Dimension(0, 0));
        p.setMaximumSize(new Dimension(0, 0));
        return p;
    }

    private void handleTabSelection() {
        int idx = sideTabs.getSelectedIndex();
        if (idx == 0) {
            // Terminal
            if (activeTerminal == null) {
                createTerminal();
            } else {
                showDrawer();
                if (activeTerminal.isReady()) {
                    activeTerminal.requestFocusInTerminal();
                } else {
                    activeTerminal.whenReady().thenAccept(t -> SwingUtilities.invokeLater(t::requestFocusInTerminal));
                }
            }
        } else if (idx == 1) {
            // Task list
            openTaskList();
        }
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
                if (activeTerminal == null) {
                    createTerminal();
                } else {
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

            if (activeTaskList != null) {
                sideTabs.setSelectedIndex(1);
                showDrawer();
            } else {
                collapseIfEmpty();
            }
        });
    }

    /** Opens the task list in the drawer. If already open, ensures it has focus. */
    public TaskListPanel openTaskList() {
        assert SwingUtilities.isEventDispatchThread();
        if (activeTaskList == null) {
            activeTaskList = new TaskListPanel(console);
        }
        showDrawer();
        return activeTaskList;
    }

    /** Shows the drawer by restoring the divider to its last known position. */
    public void showDrawer() {
        SwingUtilities.invokeLater(() -> {
            // Ensure only the selected tool is shown
            drawerContentPanel.removeAll();

            int idx = sideTabs.getSelectedIndex();
            if (idx == 0 && activeTerminal != null) {
                drawerContentPanel.add(activeTerminal, BorderLayout.CENTER);
            } else if (idx == 1 && activeTaskList != null) {
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

                    // Calculate the minimum width needed for the side tab strip
                    int tabStripWidth = sideTabs.getPreferredSize().width;
                    final int MIN_COLLAPSE_WIDTH = tabStripWidth;

                    int totalWidth = parentSplitPane.getWidth();
                    if (totalWidth <= 0) {
                        // Not laid out yet; try again on the next event cycle
                        SwingUtilities.invokeLater(this::collapseIfEmpty);
                        return;
                    }

                    // Set resize weight so left panel gets all extra space
                    parentSplitPane.setResizeWeight(1.0);

                    // Set minimum size on this drawer panel to keep tab strip visible
                    setMinimumSize(new Dimension(MIN_COLLAPSE_WIDTH, 0));

                    // Position divider to show only the tab strip
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
        // Ensure the TerminalPanel exists without invoking the 50% defaults
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

        // Select Terminal tab to reflect visible content
        sideTabs.setSelectedIndex(0);

        revalidate();
        repaint();
    }

    public void openTerminalAndPasteText(String text) {
        // Ensure the Terminal tab is selected so the terminal is visible
        sideTabs.setSelectedIndex(0);
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
