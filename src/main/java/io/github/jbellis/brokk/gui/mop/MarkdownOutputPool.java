package io.github.jbellis.brokk.gui.mop;

import javax.swing.*;
import java.util.ArrayDeque;
import java.util.Deque;

import static java.util.Objects.requireNonNull;

/**
 * Thread-safe LRU pool for MarkdownOutputPanel instances.
 *
 * The pool may be accessed only from the Swing EDT.
 */
public final class MarkdownOutputPool {

    private static final int MAX_SIZE = 5;
    private static final int WARM_SIZE = 2;

    private final Deque<MarkdownOutputPanel> idle = new ArrayDeque<>();

    private MarkdownOutputPool() {
        // eagerly warm-up a couple of instances
        for (int i = 0; i < WARM_SIZE; i++) {
            var mop = new MarkdownOutputPanel();
            idle.push(mop);
        }
    }

    // ----- singleton holder -------------------------------------------------

    private static final class Holder {
        private static final MarkdownOutputPool INSTANCE = new MarkdownOutputPool();
    }

    public static MarkdownOutputPool instance() {
        return Holder.INSTANCE;
    }

    // ----- API --------------------------------------------------------------

    /** Borrow a panel, creating a new one if pool is empty. */
    public MarkdownOutputPanel borrow() {
        assert SwingUtilities.isEventDispatchThread();
        if (!idle.isEmpty()) {
            return idle.pop();
        }
        return new MarkdownOutputPanel();
    }

    /** Return a panel to the pool; may dispose if capacity reached. */
    public void giveBack(MarkdownOutputPanel panel) {
        assert SwingUtilities.isEventDispatchThread();
        requireNonNull(panel);

        panel.clear();
        panel.hideSpinner();

        if (idle.size() < MAX_SIZE) {
            idle.push(panel);
        } else {
            panel.dispose();
        }
    }
}
