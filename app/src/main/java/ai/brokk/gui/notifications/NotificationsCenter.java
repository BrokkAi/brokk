package ai.brokk.gui.notifications;

import ai.brokk.IConsoleIO;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NotificationsCenter: manages in-app notifications, inline strip, queueing, confirm notifications,
 * and a modeless notifications dialog. UI operations assert/execute on the EDT.
 *
 * Public API is thread-safe: callers may invoke from any thread.
 */
public final class NotificationsCenter implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(NotificationsCenter.class);

    private final NotificationStore store;

    // guarded by this
    private final List<NotificationEntry> notifications = new ArrayList<>();
    private final Deque<NotificationEntry> notificationQueue = new ArrayDeque<>();

    // EDT UI components
    private final JPanel notificationAreaPanel; // single-slot inline notification area (small card)
    private final JFrame ownerFrame; // optional owner for dialogs; may be null
    private final Object lock = new Object();

    // Notifications dialog (created lazily on demand)
    private @SuppressWarnings("FieldMayBeFinal") JFrame notificationsDialog = null;
    private @SuppressWarnings("FieldMayBeFinal") JPanel notificationsListPanel = null;

    private volatile boolean isDisplayingNotification = false;

    // callback to update unread/offline badge in callers (e.g., Chrome)
    private final Consumer<Integer> unreadCountCallback;

    // theme colors provider can be implemented through ThemeAware.applyTheme
    private volatile GuiTheme currentTheme = null;

    /**
     * Create center with a NotificationStore and optional owner frame and unread-count callback.
     *
     * @param store callback-backed store for persistence (non-null)
     * @param ownerFrame optional parent frame for dialogs (may be null)
     * @param unreadCountCallback optional callback to report unread count; may be null
     */
    public NotificationsCenter(NotificationStore store, JFrame ownerFrame, Consumer<Integer> unreadCountCallback) {
        this.store = Objects.requireNonNull(store);
        this.ownerFrame = ownerFrame;
        this.unreadCountCallback = unreadCountCallback != null ? unreadCountCallback : (i -> {});
        // Create inline panel (EDT-safe creation)
        this.notificationAreaPanel = new JPanel(new BorderLayout());
        this.notificationAreaPanel.setOpaque(false);
        this.notificationAreaPanel.setBorder(new EmptyBorder(2, 2, 2, 2));
        // Load persisted notifications asynchronously so constructor doesn't block EDT.
        CompletableFuture.runAsync(this::loadPersistedNotifications);
    }

    public NotificationsCenter(NotificationStore store, JFrame ownerFrame) {
        this(store, ownerFrame, null);
    }

    /**
     * Load persisted notifications (blocking IO) and update UI on EDT.
     */
    public void loadPersistedNotifications() {
        try {
            List<NotificationEntry> loaded = store.load();
            synchronized (lock) {
                notifications.clear();
                notifications.addAll(loaded);
            }
            SwingUtilities.invokeLater(() -> {
                updateUnreadCount();
                // If dialog is open, rebuild its list
                if (notificationsDialog != null && notificationsListPanel != null) {
                    rebuildNotificationsList();
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to load notifications", e);
        }
    }

    /**
     * Persist notifications asynchronously.
     */
    public void persistNotificationsAsync() {
        List<NotificationEntry> snapshot;
        synchronized (lock) {
            snapshot = List.copyOf(notifications);
        }
        store.saveAsync(snapshot).exceptionally(ex -> {
            logger.warn("Asynchronous notifications save failed", ex);
            return null;
        });
    }

    /**
     * Publicly visible inline notification area component. Caller should add this to its toolbar/status area.
     * The returned component is safe to be used from the EDT.
     */
    public JComponent getInlineNotificationComponent() {
        assert SwingUtilities.isEventDispatchThread() : "getInlineNotificationComponent must be called on EDT";
        return notificationAreaPanel;
    }

    /**
     * Post a simple notification (info/error/cost). Safe to call from any thread.
     */
    public void showNotification(IConsoleIO.NotificationRole role, String message) {
        Runnable r = () -> {
            var entry = new NotificationEntry(role, message, System.currentTimeMillis());
            synchronized (lock) {
                notifications.add(entry);
                notificationQueue.offer(entry);
            }
            updateUnreadCount();
            persistNotificationsAsync();
            refreshLatestNotificationCard();
            rebuildNotificationsDialogIfOpen();
        };
        runOnEdt(r);
    }

    /**
     * Post a confirm-style notification with accept/reject callbacks. Safe to call from any thread.
     */
    public void showConfirmNotification(String message, Runnable onAccept, Runnable onReject) {
        Runnable r = () -> {
            var entry = new NotificationEntry(IConsoleIO.NotificationRole.CONFIRM, message, System.currentTimeMillis());
            synchronized (lock) {
                notifications.add(entry);
            }
            updateUnreadCount();
            persistNotificationsAsync();
            rebuildNotificationsDialogIfOpen();

            if (isDisplayingNotification) {
                synchronized (lock) {
                    notificationQueue.offer(entry);
                }
            } else {
                // Immediately show confirm card
                notificationAreaPanel.removeAll();
                isDisplayingNotification = true;
                JPanel card = createNotificationCard(
                        IConsoleIO.NotificationRole.CONFIRM,
                        message,
                        () -> {
                            try {
                                if (onAccept != null) onAccept.run();
                            } finally {
                                removeNotificationCard();
                            }
                        },
                        () -> {
                            try {
                                if (onReject != null) onReject.run();
                            } finally {
                                removeNotificationCard();
                            }
                        });
                notificationAreaPanel.add(card, BorderLayout.CENTER);
                animateNotificationCard(card, true);
                notificationAreaPanel.revalidate();
                notificationAreaPanel.repaint();
            }
        };
        runOnEdt(r);
    }

    /**
     * Remove currently displayed card and attempt to show next queued notification. Safe to call from any thread.
     */
    public void removeNotificationCard() {
        Runnable r = () -> {
            // Dismiss current and try next
            isDisplayingNotification = false;
            refreshLatestNotificationCard();
            updateUnreadCount();
            persistNotificationsAsync();
            rebuildNotificationsDialogIfOpen();
        };
        runOnEdt(r);
    }

    /**
     * Clears all notifications.
     */
    public void clearAll() {
        Runnable r = () -> {
            synchronized (lock) {
                notifications.clear();
                notificationQueue.clear();
            }
            updateUnreadCount();
            persistNotificationsAsync();
            rebuildNotificationsDialogIfOpen();
            // Remove inline card
            notificationAreaPanel.removeAll();
            notificationAreaPanel.revalidate();
            notificationAreaPanel.repaint();
        };
        runOnEdt(r);
    }

    /**
     * Remove a specific notification entry.
     */
    public void remove(NotificationEntry entry) {
        Runnable r = () -> {
            synchronized (lock) {
                notifications.remove(entry);
                notificationQueue.removeIf(e -> e == entry);
            }
            updateUnreadCount();
            persistNotificationsAsync();
            rebuildNotificationsDialogIfOpen();
        };
        runOnEdt(r);
    }

    /**
     * Show the full notifications dialog (modeless). Safe to call from any thread.
     */
    public void showNotificationsDialog() {
        runOnEdt(() -> {
            if (notificationsDialog != null && notificationsDialog.isDisplayable()) {
                rebuildNotificationsList();
                notificationsDialog.toFront();
                notificationsDialog.requestFocus();
                notificationsDialog.setVisible(true);
                return;
            }

            notificationsDialog = new JFrame("Notifications (" + getNotificationsCount() + ")");
            notificationsDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            notificationsDialog.setLayout(new BorderLayout(8, 8));
            notificationsDialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    notificationsDialog = null;
                    notificationsListPanel = null;
                }
            });

            notificationsListPanel = new JPanel();
            notificationsListPanel.setLayout(new BoxLayout(notificationsListPanel, BoxLayout.Y_AXIS));
            notificationsListPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
            rebuildNotificationsList();

            var scroll = new JScrollPane(notificationsListPanel);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

            // Footer
            var footer = new JPanel(new BorderLayout());
            footer.setBorder(new EmptyBorder(8, 8, 8, 8));

            var noteLabel = new JLabel("The most recent 100 notifications are retained.");
            noteLabel.setFont(noteLabel.getFont().deriveFont(Font.ITALIC));
            noteLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            footer.add(noteLabel, BorderLayout.WEST);

            var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            var closeBtn = new JButton("Ok");
            closeBtn.addActionListener(evt -> {
                if (notificationsDialog != null) {
                    notificationsDialog.dispose();
                    notificationsDialog = null;
                    notificationsListPanel = null;
                }
            });
            buttonPanel.add(closeBtn);

            var clearAllBtn = new JButton("Clear All");
            clearAllBtn.addActionListener(evt -> clearAll());
            buttonPanel.add(clearAllBtn);

            footer.add(buttonPanel, BorderLayout.EAST);

            notificationsDialog.add(scroll, BorderLayout.CENTER);
            notificationsDialog.add(footer, BorderLayout.SOUTH);

            notificationsDialog.setSize(640, 480);
            // center relative to owner if set
            if (ownerFrame != null) notificationsDialog.setLocationRelativeTo(ownerFrame);
            notificationsDialog.setVisible(true);
        });
    }

    /**
     * Rebuilds the notificationsListPanel with current notifications (newest-first).
     * Must be called on EDT.
     */
    private void rebuildNotificationsList() {
        assert SwingUtilities.isEventDispatchThread();
        if (notificationsListPanel == null) return;
        notificationsListPanel.removeAll();
        List<NotificationEntry> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(this.notifications);
        }
        snapshot.sort(NotificationEntry.NEWEST_FIRST);
        if (snapshot.isEmpty()) {
            var lbl = new JLabel("No notifications.");
            lbl.setBorder(new EmptyBorder(4, 4, 4, 4));
            notificationsListPanel.add(lbl);
        } else {
            for (NotificationEntry n : snapshot) {
                var card = createListCard(n);
                notificationsListPanel.add(card);
                notificationsListPanel.add(Box.createVerticalStrut(6));
            }
            notificationsListPanel.add(Box.createVerticalGlue());
        }
        notificationsListPanel.revalidate();
        notificationsListPanel.repaint();
        if (notificationsDialog != null) {
            notificationsDialog.setTitle("Notifications (" + getNotificationsCount() + ")");
        }
    }

    /**
     * Create a compact card used inside the notifications dialog's list.
     * Must be called on EDT.
     */
    private JComponent createListCard(NotificationEntry n) {
        assert SwingUtilities.isEventDispatchThread();
        var colors = resolveNotificationColors(n.role);
        Color bg = colors[0], fg = colors[1], border = colors[2];

        JPanel p = new JPanel(new BorderLayout(8, 4));
        p.setBorder(BorderFactory.createLineBorder(border));
        p.setBackground(bg);

        String timeStr = n.formattedTimestamp();
        var label = new JLabel("<html><div style='width:100%; word-wrap: break-word; white-space: normal;'>"
                + escapeHtml(n.message) + " <b>" + escapeHtml(timeStr) + "</b></div></html>");
        label.setForeground(fg);
        p.add(label, BorderLayout.CENTER);

        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        var rm = new JButton("Remove");
        rm.setToolTipText("Remove this notification");
        rm.addActionListener(e -> remove(n));
        actions.add(rm);
        p.add(actions, BorderLayout.EAST);

        // clicking the message shows detail
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showFullNotificationMessage(n);
            }
        });

        return p;
    }

    /**
     * Shows a modal dialog with the full notification message.
     * Must be called on EDT.
     */
    private void showFullNotificationMessage(NotificationEntry n) {
        assert SwingUtilities.isEventDispatchThread();
        JOptionPane.showMessageDialog(ownerFrame, n.message, "Notification", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Try to show the next queued notification in the inline area.
     * Must be called on EDT.
     */
    private void refreshLatestNotificationCard() {
        assert SwingUtilities.isEventDispatchThread();
        if (isDisplayingNotification) return;
        NotificationEntry next;
        synchronized (lock) {
            next = notificationQueue.poll();
        }
        if (next == null) {
            // clear inline
            notificationAreaPanel.removeAll();
            notificationAreaPanel.revalidate();
            notificationAreaPanel.repaint();
            return;
        }
        isDisplayingNotification = true;
        JPanel card = createNotificationCard(next.role, next.message, null, null);
        notificationAreaPanel.removeAll();
        notificationAreaPanel.add(card, BorderLayout.CENTER);
        animateNotificationCard(card, false);
        notificationAreaPanel.revalidate();
        notificationAreaPanel.repaint();
    }

    /**
     * Rebuild dialog list only if dialog is open.
     */
    private void rebuildNotificationsDialogIfOpen() {
        runOnEdt(() -> {
            if (notificationsDialog != null && notificationsListPanel != null) {
                rebuildNotificationsList();
            }
        });
    }

    /**
     * Create a notification card for inline display (and for confirm style).
     * If onAccept/onReject are non-null, they will be wired into Accept/Reject buttons (confirm card).
     * Must be called on EDT.
     */
    private JPanel createNotificationCard(
            IConsoleIO.NotificationRole role, String message, Runnable onAccept, Runnable onReject) {
        assert SwingUtilities.isEventDispatchThread();
        var colors = resolveNotificationColors(role);
        Color bg = colors[0], fg = colors[1], border = colors[2];

        JPanel card = new JPanel(new BorderLayout(8, 4));
        card.setBorder(BorderFactory.createLineBorder(border));
        card.setBackground(bg);
        card.setOpaque(true);
        card.setMinimumSize(new Dimension(200, 32));

        var left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        var label = new JLabel("<html><div style='width:100%; word-wrap: break-word; white-space: normal;'>"
                + escapeHtml(message) + "</div></html>");
        label.setForeground(fg);
        left.add(label, BorderLayout.CENTER);
        card.add(left, BorderLayout.CENTER);

        var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.setOpaque(false);
        if (role == IConsoleIO.NotificationRole.CONFIRM && (onAccept != null || onReject != null)) {
            var accept = new JButton("Accept");
            accept.addActionListener(e -> {
                if (onAccept != null) onAccept.run();
                removeNotificationCard();
            });
            right.add(accept);
            var reject = new JButton("Reject");
            reject.addActionListener(e -> {
                if (onReject != null) onReject.run();
                removeNotificationCard();
            });
            right.add(reject);
        } else {
            var close = new JButton("×");
            close.setToolTipText("Dismiss");
            close.setMargin(new Insets(2, 6, 2, 6));
            close.addActionListener(e -> removeNotificationCard());
            right.add(close);
        }
        card.add(right, BorderLayout.EAST);

        return card;
    }

    /**
     * Animate a notification card: for non-confirm notifications we auto-dismiss after 5s.
     * For confirm cards, we leave it until user action.
     *
     * Must be called on EDT.
     */
    private void animateNotificationCard(JPanel card, boolean isConfirm) {
        assert SwingUtilities.isEventDispatchThread();
        // Simple fade-in/out is non-trivial without extra libs; for now show card and schedule hide.
        if (!isConfirm) {
            javax.swing.Timer t = new javax.swing.Timer(5000, e -> {
                // Dismiss and try next
                isDisplayingNotification = false;
                refreshLatestNotificationCard();
            });
            t.setRepeats(false);
            t.start();
        }
    }

    /**
     * Update unread badge / callback.
     */
    private void updateUnreadCount() {
        int cnt = getNotificationsCount();
        try {
            unreadCountCallback.accept(cnt);
        } catch (Exception ex) {
            logger.warn("Unread callback threw", ex);
        }
    }

    private int getNotificationsCount() {
        synchronized (lock) {
            return notifications.size();
        }
    }

    /**
     * Helper that ensures a Runnable is executed on the EDT.
     */
    private static void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Resolve simple palette for notifications. Returns {background, foreground, border}.
     */
    private Color[] resolveNotificationColors(IConsoleIO.NotificationRole role) {
        boolean darkBg = UIManager.getColor("Panel.background") == null; // best-effort
        switch (role) {
            case ERROR:
                return new Color[] {new Color(0xFFEBEB), Color.BLACK, new Color(0xFF7B7B)};
            case COST:
                return new Color[] {new Color(0xFFF6E0), Color.BLACK, new Color(0xFFC66A)};
            case CONFIRM:
                return new Color[] {new Color(0xF0F0F0), Color.BLACK, new Color(0xCCCCCC)};
            case INFO:
            default:
                return new Color[] {new Color(0xEAF6FF), Color.BLACK, new Color(0xBFE0FF)};
        }
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        this.currentTheme = guiTheme;
        // Propagate to visible dialogs/components (best-effort)
        runOnEdt(() -> {
            SwingUtilities.updateComponentTreeUI(notificationAreaPanel);
            if (notificationsDialog != null) {
                SwingUtilities.updateComponentTreeUI(notificationsDialog);
            }
        });
    }

    /**
     * Convenience factory: create a NotificationsCenter with default file location under given project root.
     */
    public static NotificationsCenter createWithDefaultStore(
            Path projectRoot, JFrame ownerFrame, Consumer<Integer> unreadCallback) {
        NotificationStore s = new NotificationStore(NotificationStore.computeDefaultPath(projectRoot));
        return new NotificationsCenter(s, ownerFrame, unreadCallback);
    }
}
