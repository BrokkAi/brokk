package io.github.jbellis.brokk.gui.terminal;

import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.components.MaterialToggleButton;
import io.github.jbellis.brokk.gui.theme.GuiTheme;
import io.github.jbellis.brokk.gui.theme.ThemeAware;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.util.GlobalUiSettings;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
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
    private boolean isCollapsed = false;
    private boolean suppressPersist = false;
    private int originalDividerSize;
    private static final int MIN_OPEN_WIDTH = 200;

    // Dependencies
    private final Chrome chrome;
    private final JSplitPane parentSplitPane;

    /**
     * Creates a new terminal drawer panel.
     *
     * @param chrome Console IO for terminal operations
     * @param parentSplitPane The split pane this drawer is part of
     */
    public TerminalDrawerPanel(Chrome chrome, JSplitPane parentSplitPane) {
        super(new BorderLayout());
        this.chrome = chrome;
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
                persistLastTab("terminal");
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
                        activeTerminal
                                .whenReady()
                                .thenAccept(t -> SwingUtilities.invokeLater(t::requestFocusInTerminal));
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
                persistLastTab("tasks");
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

        // Persist split proportion when user moves the divider
        parentSplitPane.addPropertyChangeListener("dividerLocation", evt -> {
            if (parentSplitPane.getDividerSize() > 0 && !isCollapsed && !suppressPersist) {
                persistProportionFromSplit();
            }
        });

        // Restore drawer state (per-project or global), or collapse if none is configured
        SwingUtilities.invokeLater(this::restoreInitialState);
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
            activeTaskList = new TaskListPanel(chrome);
        } else {
            // Panel already exists (and may already be showing). Make sure it reloads from the manager
            // so newly appended tasks are visible immediately.
            activeTaskList.refreshFromManager();
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
            isCollapsed = false;
            // Restore original divider size
            if (originalDividerSize > 0) {
                parentSplitPane.setDividerSize(originalDividerSize);
            }

            // Reset resize weight to default
            parentSplitPane.setResizeWeight(0.5);

            // Enforce a minimum open width for the drawer
            setMinimumSize(new Dimension(MIN_OPEN_WIDTH, 0));

            // Use pixel-precise divider positioning for the initial 50/50 case to avoid rounding bias
            parentSplitPane.revalidate();
            parentSplitPane.repaint();

            int totalWidth = parentSplitPane.getWidth();
            int dividerSize = parentSplitPane.getDividerSize();
            double locProp = lastDividerLocation;

            suppressPersist = true;
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
            suppressPersist = false;

            // Persist state after showing
            persistOpen(true);
            persistProportionFromSplit();
        });
    }

    /** Collapses the drawer if no tools are active, showing only the tab strip. */
    public void collapseIfEmpty() {
        SwingUtilities.invokeLater(() -> {
            isCollapsed = true;
            if (drawerContentPanel.getComponentCount() == 0) {
                try {
                    // Remember last divider location only if not already collapsed
                    int total = parentSplitPane.getWidth();
                    int dividerSize = parentSplitPane.getDividerSize();
                    int current = parentSplitPane.getDividerLocation();
                    if (total > 0) {
                        int effective = Math.max(1, total - dividerSize);
                        double currentProp = Math.max(0.0, Math.min(1.0, (double) current / (double) effective));
                        if (currentProp > 0.0 && currentProp < 0.95) {
                            lastDividerLocation = currentProp;
                        }
                    }

                    // Calculate the minimum width needed for the side control bar
                    int tabStripWidth = buttonBar.getPreferredSize().width;
                    final int MIN_COLLAPSE_WIDTH = tabStripWidth;

                    int totalWidth = total;
                    if (totalWidth <= 0) {
                        // Not laid out yet; try again on the next event cycle
                        SwingUtilities.invokeLater(this::collapseIfEmpty);
                        return;
                    }

                    // Prevent persisting while programmatically collapsing
                    suppressPersist = true;
                    // Hide the divider before moving it so we don't persist the collapsed position
                    parentSplitPane.setDividerSize(0);

                    // Set resize weight so left panel gets all extra space
                    parentSplitPane.setResizeWeight(1.0);

                    // Set minimum size on this drawer panel to keep control bar visible
                    setMinimumSize(new Dimension(MIN_COLLAPSE_WIDTH, 0));

                    // Position divider to show only the control bar
                    int dividerLocation = totalWidth - MIN_COLLAPSE_WIDTH;
                    parentSplitPane.setDividerLocation(dividerLocation);

                    // Force layout update
                    parentSplitPane.revalidate();
                    parentSplitPane.repaint();
                    suppressPersist = false;

                    // Persist collapsed state
                    persistOpen(false);
                } catch (Exception ex) {
                    logger.debug("Error collapsing drawer", ex);
                }
            }
        });
    }

    /** Opens the drawer synchronously before first layout using a saved proportion. */
    public void openInitially(double proportion) {
        isCollapsed = false;
        // Ensure the TerminalPanel exists
        if (activeTerminal == null) {
            try {
                var project = chrome.getProject();
                Path cwd = project.getRoot();
                activeTerminal = new TerminalPanel(chrome, this::closeTerminal, true, cwd);
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
        setMinimumSize(new Dimension(MIN_OPEN_WIDTH, 0));

        // Apply saved proportion if valid, else fall back to 0.5
        double loc = (proportion > 0.0 && proportion < 0.90) ? proportion : 0.5;
        suppressPersist = true;
        parentSplitPane.setDividerLocation(loc);
        suppressPersist = false;

        // Reflect visible content
        terminalToggle.setSelected(true);
        tasksToggle.setSelected(false);

        // Update internal and persist
        lastDividerLocation = loc;
        persistLastTab("terminal");
        persistOpen(true);
        persistProportion(loc);

        revalidate();
        repaint();
    }

    // --- Persistence helpers and restore ---

    private boolean isUsingPerProjectPersistence() {
        return GlobalUiSettings.isPersistPerProjectBounds();
    }

    private void restoreInitialState() {
        try {
            var usePerProject = isUsingPerProjectPersistence();
            var ap = chrome.getProject();

            // Last tab
            var lastTab = usePerProject ? ap.getTerminalDrawerLastTab() : null;
            if (lastTab == null) {
                lastTab = GlobalUiSettings.getTerminalDrawerLastTab();
            }
            if (lastTab == null) {
                lastTab = "terminal";
            }

            // Open flag
            boolean open = usePerProject
                    ? Boolean.TRUE.equals(ap.getTerminalDrawerOpen()) || GlobalUiSettings.isTerminalDrawerOpen()
                    : GlobalUiSettings.isTerminalDrawerOpen();

            // Proportion
            double prop = usePerProject
                    ? ap.getTerminalDrawerProportion() > 0.0
                            ? ap.getTerminalDrawerProportion()
                            : GlobalUiSettings.getTerminalDrawerProportion()
                    : GlobalUiSettings.getTerminalDrawerProportion();
            if (!(prop > 0.0 && prop < 0.90)) {
                prop = 0.5;
            }

            if (open) {
                if ("tasks".equalsIgnoreCase(lastTab)) {
                    openTaskList();
                    applyProportion(prop);
                } else {
                    openInitially(prop);
                }
            } else {
                collapseIfEmpty();
            }
        } catch (Exception e) {
            logger.debug("Failed to restore terminal drawer state", e);
            collapseIfEmpty();
        }
    }

    private void applyProportion(double proportion) {
        isCollapsed = false;
        if (originalDividerSize > 0) {
            parentSplitPane.setDividerSize(originalDividerSize);
        }
        parentSplitPane.setResizeWeight(0.5);
        setMinimumSize(new Dimension(MIN_OPEN_WIDTH, 0));

        double loc = (proportion > 0.0 && proportion < 0.90) ? proportion : 0.5;
        suppressPersist = true;
        parentSplitPane.setDividerLocation(loc);
        suppressPersist = false;
        lastDividerLocation = loc;

        parentSplitPane.revalidate();
        parentSplitPane.repaint();

        // Persist immediately
        persistProportion(loc);
        persistOpen(true);
    }

    private void persistLastTab(String tab) {
        var ap = chrome.getProject();
        if (isUsingPerProjectPersistence()) {
            ap.setTerminalDrawerLastTab(tab);
            GlobalUiSettings.saveTerminalDrawerLastTab(tab);
        } else {
            GlobalUiSettings.saveTerminalDrawerLastTab(tab);
        }
    }

    private void persistOpen(boolean open) {
        var ap = chrome.getProject();
        if (isUsingPerProjectPersistence()) {
            ap.setTerminalDrawerOpen(open);
            GlobalUiSettings.saveTerminalDrawerOpen(open);
        } else {
            GlobalUiSettings.saveTerminalDrawerOpen(open);
        }
    }

    private void persistProportionFromSplit() {
        if (isCollapsed || suppressPersist) return;
        int total = parentSplitPane.getWidth();
        int dividerSize = parentSplitPane.getDividerSize();
        if (total <= 0) return;
        int effective = Math.max(1, total - dividerSize);
        // If the drawer is effectively collapsed (only button bar visible), skip persisting
        int barW = buttonBar.getPreferredSize().width;
        int drawerW = getWidth();
        if (drawerW <= barW + 2) return;
        int locPx = parentSplitPane.getDividerLocation();
        double prop = Math.max(0.0, Math.min(1.0, (double) locPx / (double) effective));
        persistProportion(prop);
    }

    private void persistProportion(double prop) {
        if (isCollapsed || suppressPersist) return;
        double clamped = (prop > 0.0 && prop < 1.0) ? Math.max(0.05, Math.min(0.95, prop)) : -1.0;
        // Treat near-collapsed positions as "collapsed" and do not overwrite the last open proportion
        if (!(clamped > 0.0 && clamped < 1.0) || clamped >= 0.90) return;
        lastDividerLocation = clamped;

        var ap = chrome.getProject();
        if (isUsingPerProjectPersistence()) {
            ap.setTerminalDrawerProportion(clamped);
            GlobalUiSettings.saveTerminalDrawerProportion(clamped);
        } else {
            GlobalUiSettings.saveTerminalDrawerProportion(clamped);
        }
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
            var project = chrome.getProject();
            Path cwd = project.getRoot();
            var terminal = new TerminalPanel(chrome, this::closeTerminal, true, cwd);
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
