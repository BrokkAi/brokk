package io.github.jbellis.brokk.gui.mop;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private static final Logger logger = LogManager.getLogger(MarkdownOutputPool.class);
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
            logger.debug("Borrowed MarkdownOutputPanel from pool, {} remaining", idle.size());
            return idle.pop();
        }
        logger.debug("Created new MarkdownOutputPanel (pool is empty)");
        return new MarkdownOutputPanel();
    }

    /** Return a panel to the pool; may dispose if capacity reached. */
    public void giveBack(MarkdownOutputPanel panel) {
        assert SwingUtilities.isEventDispatchThread();
        requireNonNull(panel);

        panel.clear();
        panel.hideSpinner();

        if (idle.size() < MAX_SIZE) {
            logger.debug("Returned MarkdownOutputPanel to pool, {} remaining", idle.size());
            idle.push(panel);
        } else {
            logger.debug("Disposed MarkdownOutputPanel (pool capacity reached)");
            panel.dispose();
        }
    }
}
