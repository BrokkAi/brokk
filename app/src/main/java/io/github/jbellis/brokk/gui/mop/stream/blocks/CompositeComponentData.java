package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.mop.stream.Reconciler;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents a composite component that contains multiple child components.
 * Used for handling nested elements like code fences inside list items.
 */
public record CompositeComponentData(
    int id,
    List<ComponentData> children
) implements ComponentData {

    @Override
    public String fp() {
        // Combine child fingerprints to create a composite fingerprint
        return children.stream()
               .map(ComponentData::fp)
               .collect(Collectors.joining("-"));
    }

    private static final String THEME_FLAG = "brokk.darkTheme";

    @Override
    public JComponent createComponent(boolean darkTheme) {
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(true);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        var bgColor = ThemeColors.getColor(darkTheme, "message_background");
        panel.setBackground(bgColor);
        
        // Store the theme flag for later use in updateComponent
        panel.putClientProperty(THEME_FLAG, darkTheme);
        
        // Create and add each child component in order
        for (ComponentData child : children) {
            var childComp = child.createComponent(darkTheme);
            childComp.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(childComp);
        }
        
        return panel;
    }

    private static final String REGISTRY_KEY = "brokk.registry";

    @SuppressWarnings("unchecked")
    private static Map<Integer, Reconciler.BlockEntry> registryOf(JPanel panel) {
        var map = (Map<Integer, Reconciler.BlockEntry>) panel.getClientProperty(REGISTRY_KEY);
        if (map == null) {
            map = new LinkedHashMap<>();
            panel.putClientProperty(REGISTRY_KEY, map);
        }
        return map;
    }

    @Override
    public void updateComponent(JComponent component) {
        if (!(component instanceof JPanel panel)) {
            return;
        }
        
        boolean darkTheme = Boolean.TRUE.equals(panel.getClientProperty(THEME_FLAG));
        var registry = registryOf(panel);
        Reconciler.reconcile(panel, children, registry, darkTheme);
    }
}
