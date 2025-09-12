package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.util.Icons;
import javax.swing.*;
import java.awt.*;

public class WorkspaceDrawerSplit extends JPanel {
    private final JSplitPane splitPane;
    private int lastDrawerWidth = 320;
    private int originalDividerSize;

    public WorkspaceDrawerSplit() {
        super(new BorderLayout());

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(1.0); // Main component gets priority
        originalDividerSize = splitPane.getDividerSize();

        MaterialButton toggleButton = new MaterialButton();
        toggleButton.setIcon(Icons.ADJUST);
        toggleButton.setToolTipText("Manage Dependencies");
        toggleButton.setFocusable(false);
        toggleButton.setOpaque(false);
        toggleButton.addActionListener(e -> toggleDrawer());

        JPanel toggleWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        toggleWrapper.setOpaque(false);
        toggleWrapper.add(toggleButton);

        add(splitPane, BorderLayout.CENTER);
        add(toggleWrapper, BorderLayout.EAST);
    }

    public void setWorkspaceComponent(JComponent component) {
        splitPane.setLeftComponent(component);
    }

    public void setDrawerComponent(JComponent component) {
        splitPane.setRightComponent(component);
        hideDrawer(); // Initially hidden
    }

    public void toggleDrawer() {
        SwingUtilities.invokeLater(() -> {
            boolean hidden = splitPane.getDividerSize() == 0;
            if (hidden) {
                showDrawer();
            } else {
                hideDrawer();
            }
        });
    }

    private void showDrawer() {
        Component drawer = splitPane.getRightComponent();
        if (drawer == null) return;

        drawer.setVisible(true);
        splitPane.setDividerSize(originalDividerSize);

        int total = getWidth();
        int desiredWidth = Math.max(280, lastDrawerWidth);
        int dividerLocation = Math.max(100, total - desiredWidth - splitPane.getDividerSize());
        splitPane.setDividerLocation(dividerLocation);

        revalidate();
        repaint();
    }

    private void hideDrawer() {
        Component drawer = splitPane.getRightComponent();
        if (drawer == null) return;

        lastDrawerWidth = drawer.getWidth();
        splitPane.setDividerLocation(getWidth());
        splitPane.setDividerSize(0);
        drawer.setVisible(false);

        revalidate();
        repaint();
    }
}
