package io.github.jbellis.brokk.gui.dependencies;

import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.util.Icons;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/** A drawer panel that can host development tools. The drawer can be collapsed when no tools are active. */
public class DependenciesDrawerPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(DependenciesDrawerPanel.class);

    // Core components
    private final JPanel drawerContentPanel;
    private final JPanel drawerToolBar;
    private final JToggleButton dependenciesToggle;
    private @Nullable DependenciesPanel activeDependenciesPanel;

    // Drawer state management
    private double lastDividerLocation = 0.5;
    private int originalDividerSize;

    // Dependencies
    private final Chrome chrome;
    private final JSplitPane parentSplitPane;

    /**
     * Creates a new terminal drawer panel.
     *
     * @param chrome main ui
     * @param parentSplitPane The split pane this drawer is part of
     */
    public DependenciesDrawerPanel(Chrome chrome, JSplitPane parentSplitPane) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.parentSplitPane = parentSplitPane;
        this.originalDividerSize = parentSplitPane.getDividerSize();

        setBorder(BorderFactory.createEmptyBorder());

        // Create vertical icon bar on the EAST side
        drawerToolBar = new JPanel();
        drawerToolBar.setLayout(new BoxLayout(drawerToolBar, BoxLayout.Y_AXIS));
        drawerToolBar.setOpaque(false);
        drawerToolBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        dependenciesToggle = new JToggleButton(Icons.MANAGE_DEPENDENCIES);
        dependenciesToggle.setToolTipText("Toggle Manage Dependencies");
        dependenciesToggle.setContentAreaFilled(false);
        dependenciesToggle.setFocusPainted(false);
        dependenciesToggle.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        dependenciesToggle.setAlignmentX(Component.CENTER_ALIGNMENT);
        drawerToolBar.add(dependenciesToggle);

        add(drawerToolBar, BorderLayout.EAST);

        // Content area for the drawer (where TerminalPanel and future tools will appear)
        drawerContentPanel = new JPanel(new BorderLayout());
        add(drawerContentPanel, BorderLayout.CENTER);

        // Wire the toggle to create/show/hide a Depedencies
        dependenciesToggle.addActionListener(ev -> {
            if (dependenciesToggle.isSelected()) {
                if (activeDependenciesPanel == null) {
                    createPanel();
                } else {
                    showDrawer();
                }
            } else {
                if (activeDependenciesPanel != null) {
                    hideDepedenciesDrawer();
                }
            }
        });
    }

    /** Opens the panel in the drawer */
    public void openPanel() {
        SwingUtilities.invokeLater(() -> {
            if (!dependenciesToggle.isSelected()) {
                dependenciesToggle.doClick();
                return;
            }

            if (activeDependenciesPanel == null) {
                createPanel();
            } else {
                showDrawer();
            }
        });
    }

    private void hideDepedenciesDrawer() {
        SwingUtilities.invokeLater(() -> {
            if (activeDependenciesPanel != null) {
                drawerContentPanel.remove(activeDependenciesPanel);
                drawerContentPanel.revalidate();
                drawerContentPanel.repaint();
                collapseIfEmpty();
            }
        });
    }

    /** Shows the drawer by restoring the divider to its last known position. */
    public void showDrawer() {
        SwingUtilities.invokeLater(() -> {
            if (activeDependenciesPanel != null && activeDependenciesPanel.getParent() == null) {
                drawerContentPanel.add(activeDependenciesPanel, BorderLayout.CENTER);
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

    private void createPanel() {
        var dependenciesPanel = new DependenciesPanel(chrome);
        activeDependenciesPanel = dependenciesPanel;
        
        // Add title label at the same level as workspace title
        JLabel titleLabel = new JLabel("Dependencies");
        titleLabel.setBorder(new EmptyBorder(2, 5, 2, 5)); // Match workspace title border
        
        drawerContentPanel.add(titleLabel, BorderLayout.NORTH);
        drawerContentPanel.add(dependenciesPanel, BorderLayout.CENTER);
        drawerContentPanel.revalidate();
        drawerContentPanel.repaint();
        showDrawer();
        dependenciesToggle.setSelected(true);
    }
}
