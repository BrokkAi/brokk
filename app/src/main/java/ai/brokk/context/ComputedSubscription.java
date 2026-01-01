package ai.brokk.context;

import ai.brokk.util.ComputedValue;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

/**
 * Central helper for managing ComputedValue subscriptions that are associated with a Swing component.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register subscriptions against a JComponent.</li>
 *   <li>Dispose all subscriptions when requested or when the component is removed from its ancestor hierarchy.</li>
 * </ul>
 *
 * <p>Subscriptions are stored on the component via a private clientProperty key to keep the calling
 * code simple and avoid duplicated bookkeeping logic across UI classes.</p>
 */
public final class ComputedSubscription {
    // ClientProperty keys; use Object instances to avoid collisions and magic strings.
    private static final Object SUBS_KEY = new Object();
    private static final Object LISTENER_KEY = new Object();

    private ComputedSubscription() {}

    /**
     * Register a subscription for the given component. The subscription will be disposed when
     * {@link #disposeAll(JComponent)} is called or when the component is removed from its ancestor
     * hierarchy.
     */
    private static void register(JComponent owner, ComputedValue.Subscription subscription) {
        synchronized (owner) {
            @SuppressWarnings("unchecked")
            List<ComputedValue.Subscription> subs =
                    (List<ComputedValue.Subscription>) owner.getClientProperty(SUBS_KEY);
            if (subs == null) {
                subs = new ArrayList<>();
                owner.putClientProperty(SUBS_KEY, subs);

                // Install a single AncestorListener per component to clean up when removed
                var listener = new OwnerAncestorListener(owner);
                owner.putClientProperty(LISTENER_KEY, listener);
                owner.addAncestorListener(listener);
            }
            subs.add(subscription);
        }
    }

    /**
     * Bind a fragment's computed values to a Swing component, automatically managing subscriptions
     * and running UI updates on the EDT. If the fragment is asynchronous (ComputedFragment),
     * registers completion handlers that run uiUpdate on the EDT when the computation completes.
     * Subscriptions are automatically disposed when the owner component is removed from its parent.
     *
     * @param fragment the ContextFragment whose values will be bound
     * @param owner the Swing component that owns these subscriptions
     * @param uiUpdate a runnable to execute on the EDT when any computed value completes
     */
    public static void bind(ContextFragment fragment, JComponent owner, Runnable uiUpdate) {
        // Helper to run UI update, coalesced onto EDT
        Runnable scheduleUpdate = () -> SwingUtilities.invokeLater(uiUpdate);

        if (fragment instanceof ContextFragment.ComputedFragment cf) {
            // Subscribe to completion for asynchronous fragments
            var sub = cf.onComplete(scheduleUpdate);
            if (sub != null) {
                register(owner, sub);
            }
        } else {
            // For synchronous fragments, run the update immediately (on EDT)
            scheduleUpdate.run();
        }
    }

    /**
     * Bind a {@link ComputedValue} to a Swing component, automatically managing the subscription
     * and running the UI update on the EDT when the value completes.
     * Subscriptions are automatically disposed when the owner component is removed from its parent.
     *
     * @param cv the ComputedValue to bind
     * @param owner the Swing component that owns this subscription
     * @param uiUpdate a runnable to execute on the EDT when the value completes
     */
    public static void bind(ComputedValue<?> cv, JComponent owner, Runnable uiUpdate) {
        var sub = cv.onComplete((val, ex) -> SwingUtilities.invokeLater(uiUpdate));
        register(owner, sub);
    }

    /**
     * Dispose all subscriptions associated with the given component and remove the internal
     * AncestorListener, if any.
     */
    public static void disposeAll(JComponent owner) {
        synchronized (owner) {
            @SuppressWarnings("unchecked")
            List<ComputedValue.Subscription> subs =
                    (List<ComputedValue.Subscription>) owner.getClientProperty(SUBS_KEY);
            if (subs != null) {
                for (var sub : subs) {
                    sub.dispose();
                }
                subs.clear();
                owner.putClientProperty(SUBS_KEY, null);
            }

            Object listenerObj = owner.getClientProperty(LISTENER_KEY);
            if (listenerObj instanceof AncestorListener listener) {
                owner.removeAncestorListener(listener);
            }
            owner.putClientProperty(LISTENER_KEY, null);
        }
    }

    /**
     * AncestorListener that disposes all subscriptions when the component is removed from the
     * ancestor hierarchy.
     */
    private static final class OwnerAncestorListener implements AncestorListener {
        private final JComponent owner;
        private boolean disposed = false;

        OwnerAncestorListener(JComponent owner) {
            this.owner = owner;
        }

        @Override
        public void ancestorAdded(AncestorEvent event) {
            // no-op
        }

        @Override
        public void ancestorRemoved(AncestorEvent event) {
            if (disposed) {
                return;
            }
            disposed = true;
            ComputedSubscription.disposeAll(owner);
            owner.removeAncestorListener(this);
        }

        @Override
        public void ancestorMoved(AncestorEvent event) {
            // no-op
        }
    }
}
