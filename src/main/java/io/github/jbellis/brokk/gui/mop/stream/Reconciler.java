package io.github.jbellis.brokk.gui.mop.stream;

import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentData;

import javax.swing.*;
import java.util.HashSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for reconciling Swing components with ComponentData in a JPanel.
 * Reuses existing components when possible to minimize UI flickering and maintain scroll/caret positions.
 */
public final class Reconciler {
    private static final Logger logger = LogManager.getLogger(Reconciler.class);

    /**
     * Tracks a rendered component and its current fingerprint.
     */
    public record BlockEntry(JComponent comp, String fp) {
    }

    /**
     * Reconciles the components in the given container with the desired list of ComponentData.
     * Reuses existing components when possible, updates them if their fingerprint changes,
     * removes stale components, and ensures correct order.
     *
     * @param container The JPanel to update
     * @param desired   The list of desired ComponentData objects
     * @param registry  The map tracking existing components and their fingerprints
     * @param darkTheme Whether to use dark theme styling for new components
     */
    public static void reconcile(JPanel container, List<ComponentData> desired, Map<Integer, BlockEntry> registry, boolean darkTheme) {
        Set<Integer> seen = new HashSet<>();

        // Process each desired component
        for (ComponentData cd : desired) {
            BlockEntry entry = registry.get(cd.id());

            if (entry == null) {
                // Create new component
                JComponent comp = cd.createComponent(darkTheme);
                entry = new BlockEntry(comp, cd.fp());
                registry.put(cd.id(), entry);
                container.add(comp);
                // logger.debug("Created new component with id {}: {}", cd.id(), cd.getClass().getSimpleName());
            } else {
                // logger.debug("cd.fp()={} vs. entry.fp={}", cd.fp(), entry.fp);
                if (!cd.fp().equals(entry.fp)) {
                    // Update existing component
                    cd.updateComponent(entry.comp);
                    entry = new BlockEntry(entry.comp, cd.fp());
                    registry.put(cd.id(), entry);
                    // logger.debug("Updated component with id {}: {}", cd.id(), cd.getClass().getSimpleName());
                }
            }

            seen.add(cd.id());
        }

        // Remove components that are no longer present
        registry.keySet().removeIf(id -> {
            if (!seen.contains(id)) {
                // logger.debug("Removing component with id {}", id);
                var entry = registry.get(id);
                if (entry != null) {
                    container.remove(entry.comp);
                }
                return true;
            }
            return false;
        });

        // Ensure components are in the correct order
        for (int i = 0; i < desired.size(); i++) {
            var cd = desired.get(i);
            var entry = registry.get(cd.id());
            if (entry == null) continue; // should not happen
            var current = (i < container.getComponentCount()) ? container.getComponent(i) : null;
            if (current != entry.comp) {
                int targetIndex = i;
                int currentCount = container.getComponentCount();
                if (targetIndex > currentCount || targetIndex < 0) {
                    logger.warn("Reconciler: Invalid targetIndex {} for component id {} (container count: {}). "
                                + "Correcting by appending.", targetIndex, cd.id(), currentCount);
                    targetIndex = currentCount; // append at end
                }
                container.add(entry.comp, targetIndex); // inserts or moves in-place
            }
        }
        // Trim extras (if any)
        while (container.getComponentCount() > desired.size()) {
            container.remove(desired.size());
        }

        // Revalidate and repaint
        container.revalidate();
        container.repaint();
    }
}
