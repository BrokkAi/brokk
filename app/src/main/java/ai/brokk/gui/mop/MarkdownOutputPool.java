package ai.brokk.gui.mop;

import java.util.ArrayDeque;
import java.util.Deque;
import javax.swing.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Thread-safe LRU pool for MarkdownOutputPanel instances.
 *
 * <p>The pool may be accessed only from the Swing EDT.
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
