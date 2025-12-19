package ai.brokk.gui;

import java.awt.Component;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper that defers expensive updates when a Swing component is not visible.
 * Usage: create with (component, updateAction). Call requestUpdate() when an update
 * is desired; if the component is showing the updateAction will run immediately
 * (on the EDT). If not showing, a dirty flag is set and the update will run once
 * the component becomes showing again.
 */
public final class DeferredUpdateHelper {
    private static final Logger logger = LogManager.getLogger(DeferredUpdateHelper.class);

    private final Component component;
    private final Runnable updateAction;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public DeferredUpdateHelper(Component component, Runnable updateAction) {
        this.component = component;
        this.updateAction = updateAction;
        // Install a HierarchyListener on the EDT to listen for SHOWING changes.
        SwingUtil.runOnEdt(() -> {
            component.addHierarchyListener(new HierarchyListener() {
                @Override
                public void hierarchyChanged(HierarchyEvent e) {
                    if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                        if (component.isShowing() && dirty.getAndSet(false)) {
                            try {
                                updateAction.run();
                            } catch (Throwable t) {
                                logger.warn("Deferred update action threw", t);
                            }
                        }
                    }
                }
            });
        });
    }

    /**
     * Request an update. If the component is showing, the updateAction will be invoked
     * on the EDT immediately. Otherwise the helper will mark the component dirty and
     * the update will be invoked once the component becomes visible.
     */
    public void requestUpdate() {
        SwingUtil.runOnEdt(() -> {
            if (component.isShowing()) {
                // Ensure dirty flag cleared, then run the action
                dirty.set(false);
                try {
                    updateAction.run();
                } catch (Throwable t) {
                    logger.warn("Immediate update action threw", t);
                }
            } else {
                dirty.set(true);
            }
        });
    }

    /** Returns whether an update is pending (component hidden when requested). */
    public boolean isDirty() {
        return dirty.get();
    }

    /** Clears the dirty flag without invoking the update action. */
    public void clearDirty() {
        dirty.set(false);
    }
}
