package io.github.jbellis.brokk.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * AutoScroller provides tail-following behavior for a JScrollPane, automatically scrolling to the bottom
 * when new content is added, unless the user manually scrolls away. It supports various component types
 * by selecting the appropriate growth detection mechanism at runtime.
 */
public final class AutoScroller {
    private static final Logger logger = LogManager.getLogger(AutoScroller.class);

    private AutoScroller() {
        // Utility class, no instances
    }

    /**
     * Installs tail-following behavior on the supplied JScrollPane. Safe to call multiple times
     * (no-op if already installed).
     *
     * @param pane the JScrollPane to manage scrolling for
     */
    public static void install(JScrollPane pane) {
        // Prevent double-installation
        if (Boolean.TRUE.equals(pane.getClientProperty("AutoScroller"))) {
            return;
        }
        pane.putClientProperty("AutoScroller", true);

        var view = pane.getViewport().getView();
        var bar = pane.getVerticalScrollBar();
        var state = new AutoFollowState(bar);

        // User scroll detector
        bar.addAdjustmentListener(e -> state.userScrolled());

        // Growth detector - dispatch by type
        if (view instanceof JTable table) {
            table.getModel().addTableModelListener(new TableModelAdapter(state));
            logger.trace("[AutoScroller] Installed TableModelListener for JTable");
        } else if (view instanceof JList<?> list) {
            list.getModel().addListDataListener(new ListDataAdapter(state));
            logger.trace("[AutoScroller] Installed ListDataListener for JList");
        } else if (view.getClass().getName().equals("io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel")) {
            try {
                var mopClass = Class.forName("io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel");
                var addTextChangeListenerMethod = mopClass.getMethod("addTextChangeListener", Runnable.class);
                addTextChangeListenerMethod.invoke(view, (Runnable) state::contentGrew);
                logger.trace("[AutoScroller] Installed text change listener for MarkdownOutputPanel");
            } catch (Exception e) {
                logger.error("[AutoScroller] Failed to install text change listener for MarkdownOutputPanel", e);
            }
        } else {
            // Fallback for custom components
            final int[] lastHeight = {view.getHeight()};
            view.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    int newH = e.getComponent().getHeight();
                    if (newH > lastHeight[0]) {
                        state.contentGrew();
                    }
                    lastHeight[0] = newH;
                }
            });
            logger.trace("[AutoScroller] Installed ComponentListener fallback for {}", view.getClass().getSimpleName());
        }
    }

    /**
     * Inner class to manage the auto-follow state and scrolling logic.
     */
    private static final class AutoFollowState {
        private final JScrollBar bar;
        private volatile boolean autoFollow = true;
        private static final int REATTACH_TOLERANCE = 30; // Tolerance for re-enabling auto-follow

        AutoFollowState(JScrollBar bar) {
            this.bar = bar;
            // Add mouse wheel listener to detect user intent to scroll away
            bar.getParent().addMouseWheelListener(e -> {
                autoFollow = false;
                logger.trace("[AutoScroller] Mouse wheel event detected, autoFollow=false");
            });
            // Add mouse listener for thumb drag start
            bar.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    autoFollow = false;
                    logger.trace("[AutoScroller] Mouse drag on scrollbar detected, autoFollow=false");
                }
            });
        }

        void userScrolled() {
            var m = bar.getModel();
            int currentValue = m.getValue();
            int currentMax = m.getMaximum();
            // Check if user scrolled back to bottom (within reattach tolerance)
            if (!autoFollow && currentValue + m.getExtent() >= currentMax - REATTACH_TOLERANCE) {
                autoFollow = true;
                logger.trace("[AutoScroller] User scrolled back to bottom, autoFollow=true (value={}, extent={}, max={})", currentValue, m.getExtent(), currentMax);
            }
            logger.trace("[AutoScroller] autoFollow={} (value={}, extent={}, max={})", autoFollow, currentValue, m.getExtent(), currentMax);
        }

        void contentGrew() {
            if (!autoFollow) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                var m = bar.getModel();
                bar.setValue(m.getMaximum() - m.getExtent());
                logger.trace("[AutoScroller] auto-scroll to {}", bar.getValue());
            });
        }
    }

    /**
     * Adapter for JTable growth detection.
     */
    private static final class TableModelAdapter implements TableModelListener {
        private final AutoFollowState state;

        TableModelAdapter(AutoFollowState state) {
            this.state = state;
        }

        @Override
        public void tableChanged(TableModelEvent e) {
            if (e.getType() == TableModelEvent.INSERT) {
                state.contentGrew();
            }
        }
    }

    /**
     * Adapter for JList growth detection.
     */
    private static final class ListDataAdapter implements ListDataListener {
        private final AutoFollowState state;

        ListDataAdapter(AutoFollowState state) {
            this.state = state;
        }

        @Override
        public void intervalAdded(ListDataEvent e) {
            state.contentGrew();
        }

        @Override
        public void intervalRemoved(ListDataEvent e) {
            // No action needed
        }

        @Override
        public void contentsChanged(ListDataEvent e) {
            // No action needed
        }
    }
}
