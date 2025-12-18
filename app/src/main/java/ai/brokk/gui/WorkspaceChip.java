package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ComputedSubscription;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.SpecialTextType;
import ai.brokk.gui.ChipColorUtils.ChipKind;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.util.Icons;
import ai.brokk.project.MainProject;
import ai.brokk.util.Messages;
import ai.brokk.util.StyleGuideResolver;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Strongly typed chip component representing one or more ContextFragments.
 * <p>
 * Encapsulates UI, tooltips, theming, hover behavior, and context menus, avoiding brittle
 * clientProperty string keys except for ComputedValue subscription bookkeeping.
 */
public class WorkspaceChip extends JPanel {

    private static final Logger logger = LogManager.getLogger(WorkspaceChip.class);

    /**
     * Simple value object for fragment text metrics so we can cache expensive work.
     */
    private record FragmentMetrics(int loc, int tokens) {}

    /**
     * Global cache mapping a ContextFragment identity to its computed metrics.
     * This avoids re-running tokenization/line counting on every tooltip refresh.
     *
     * Entries are cleared when fragments are explicitly dropped or when bound
     * computed fragments are disposed via ComputedSubscription.
     */
    private static final ConcurrentMap<ContextFragment, FragmentMetrics> metricsCache = new ConcurrentHashMap<>();

    protected final Chrome chrome;
    protected final ContextManager contextManager;
    protected final Supplier<Boolean> readOnlySupplier;
    protected final @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback;
    protected final @Nullable Consumer<ContextFragment> onRemoveFragment;
    protected final ChipKind kind;

    protected Color borderColor = Color.GRAY;
    protected static final int arc = 12;

    // Maximum characters to display on a chip label before truncating.
    // This keeps the chip (and its close button) visible even for very long descriptions.
    private static final int MAX_LABEL_CHARS = 50;

    protected final JLabelWithAccessible label;
    protected final JLabelWithAccessible readOnlyIcon;
    protected final MaterialButton closeButton;
    protected final JPanel separator;

    protected boolean closeEnabled = true;
    private Set<ContextFragment> fragments = Set.of();

    /**
     * For synthetic summary chip; use SummaryChip instead of this constructor directly.
     */
    protected WorkspaceChip(
            Chrome chrome,
            ContextManager contextManager,
            Supplier<Boolean> readOnlySupplier,
            @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback,
            @Nullable Consumer<ContextFragment> onRemoveFragment,
            Set<ContextFragment> fragments,
            ChipKind kind) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.readOnlySupplier = readOnlySupplier;
        this.hoverCallback = hoverCallback;
        this.onRemoveFragment = onRemoveFragment;
        this.kind = kind;

        setFragmentsInternal(fragments);

        this.readOnlyIcon = new JLabelWithAccessible();
        this.readOnlyIcon.setBorder(new EmptyBorder(0, 0, 0, 2));

        this.label = new JLabelWithAccessible();
        this.closeButton = new MaterialButton("");
        this.separator = new JPanel();

        initUi();
        applyTheme();
        updateReadOnlyIcon();
        bindComputed();
    }

    private void initUi() {
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        readOnlyIcon.setVisible(false);
        readOnlyIcon.setIcon(null);

        ContextFragment fragment = getPrimaryFragment();
        String safeShortDescription = fragment.shortDescription().renderNowOr("");
        if (safeShortDescription.isBlank()) {
            safeShortDescription = "(no description)";
        }

        // Initial label text (may be updated once computed values are ready).
        // Truncate to keep the chip compact so the close button remains visible.
        if (fragment instanceof ContextFragment.AbstractComputedFragment) {
            label.setText("Loading...");
        } else if (kind == ChipKind.OTHER) {
            label.setText(truncateForDisplay(capitalizeFirst(safeShortDescription)));
        } else {
            label.setText(truncateForDisplay(safeShortDescription));
        }

        refreshLabelAndTooltip();

        // Close button styling
        closeButton.setFocusable(false);
        closeButton.setOpaque(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setBorderPainted(false);
        closeButton.setFocusPainted(false);
        closeButton.setMargin(new Insets(0, 0, 0, 0));
        closeButton.setPreferredSize(new Dimension(14, 14));
        closeButton.setToolTipText("Remove from Workspace");

        closeButton.getAccessibleContext().setAccessibleName("Remove " + safeShortDescription);

        // Separator between label and close button
        separator.setOpaque(true);
        separator.setPreferredSize(new Dimension(1, Math.max(label.getPreferredSize().height - 6, 10)));
        separator.setMinimumSize(new Dimension(1, 10));
        separator.setMaximumSize(new Dimension(1, Integer.MAX_VALUE));

        add(readOnlyIcon);
        add(label);

        boolean isDroppable = true;
        if (fragment instanceof ContextFragment.StringFragment sf) {
            try {
                isDroppable = sf.droppable();
            } catch (Exception ex) {
                // Default to droppable on any unexpected error determining droppability
                isDroppable = true;
            }
        }

        if (isDroppable) {
            add(separator);
            add(closeButton);
        } else {
            separator.setVisible(false);
            closeButton.setVisible(false);
        }

        installInteractionHandlers();
        installHoverListeners();
    }

    private void installInteractionHandlers() {
        MouseAdapter labelMouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handlePopup(e, label);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handlePopup(e, label);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    onPrimaryClick();
                    e.consume();
                }
            }
        };
        label.addMouseListener(labelMouse);

        MouseAdapter closeMouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handlePopup(e, closeButton);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handlePopup(e, closeButton);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                onCloseClick();
                e.consume();
            }
        };
        closeButton.addMouseListener(closeMouse);

        MouseAdapter chipMouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handlePopup(e, WorkspaceChip.this);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handlePopup(e, WorkspaceChip.this);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.isConsumed()) return;
                int clickX = e.getX();
                int separatorEndX = separator.getX() + separator.getWidth();
                if (clickX > separatorEndX) {
                    onCloseClick();
                    e.consume();
                } else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    onPrimaryClick();
                    e.consume();
                }
            }
        };
        this.addMouseListener(chipMouse);
    }

    private void handlePopup(MouseEvent e, Component invoker) {
        if (isPanelReadOnly()) {
            chrome.systemNotify(WorkspaceItemsChipPanel.READ_ONLY_TIP, "Workspace", JOptionPane.INFORMATION_MESSAGE);
            e.consume();
            return;
        }
        JPopupMenu menu = createContextMenu();
        if (menu != null) {
            SwingUtilities.invokeLater(() -> menu.show(invoker, e.getX(), e.getY()));
        }
        e.consume();
    }

    protected void onPrimaryClick() {
        // Open unified preview via Chrome for consistent behavior across all entry points
        chrome.openFragmentPreview(getPrimaryFragment());
    }

    protected void onCloseClick() {
        if (!closeEnabled) {
            chrome.systemNotify(WorkspaceItemsChipPanel.READ_ONLY_TIP, "Workspace", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!ensureMutatingAllowed()) {
            return;
        }
        dropSingleFragment(getPrimaryFragment());
    }

    protected void installHoverListeners() {
        if (hoverCallback == null) {
            return;
        }
        final int[] hoverCounter = {0};
        MouseAdapter hoverAdapter = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (hoverCounter[0]++ == 0) {
                    ContextFragment fragment = getPrimaryFragment();
                    try {
                        hoverCallback.accept(fragment, true);
                    } catch (Exception ex) {
                        logger.trace("onHover callback threw", ex);
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverCounter[0] > 0 && --hoverCounter[0] == 0) {
                    ContextFragment fragment = getPrimaryFragment();
                    try {
                        hoverCallback.accept(fragment, false);
                    } catch (Exception ex) {
                        logger.trace("onHover callback threw", ex);
                    }
                }
            }
        };
        this.addMouseListener(hoverAdapter);
        label.addMouseListener(hoverAdapter);
        closeButton.addMouseListener(hoverAdapter);
        separator.addMouseListener(hoverAdapter);
    }

    // Painting: rounded background + dimming for read-only / non-hovered chips
    @Override
    public void paint(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            float alpha = 1.0f;
            if (getParent() instanceof WorkspaceItemsChipPanel parentPanel) {
                if (parentPanel.isReadOnlyMode()) {
                    alpha = Math.min(alpha, 0.6f);
                }
                var hoveredIds = parentPanel.getHoveredFragmentIds();
                if (!hoveredIds.isEmpty() && !fragments.isEmpty()) {
                    boolean isHovered =
                            fragments.stream().map(ContextFragment::id).anyMatch(hoveredIds::contains);
                    boolean isDimmed = !isHovered;
                    if (isDimmed) {
                        alpha = Math.min(alpha, 0.5f);
                    }
                }
            }
            if (alpha < 1.0f) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            }
            super.paint(g2);
        } finally {
            g2.dispose();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color bg = getBackground();
            if (bg == null) {
                bg = getParent() != null ? getParent().getBackground() : Color.LIGHT_GRAY;
            }
            int w = getWidth();
            int h = getHeight();
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    @Override
    protected void paintBorder(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(borderColor);
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
        } finally {
            g2.dispose();
        }
    }

    // Public API for panel

    public void setCloseEnabled(boolean enabled) {
        this.closeEnabled = enabled;
        closeButton.setEnabled(enabled);
    }

    public Set<ContextFragment> getFragments() {
        return fragments;
    }

    public void updateFragment(ContextFragment fragment) {
        // Rebind to updated fragment instance with same id
        ComputedSubscription.disposeAll(this);
        setFragmentsInternal(Set.of(fragment));
        refreshLabelAndTooltip();
        updateReadOnlyIcon();
        bindComputed();
    }

    public void applyTheme() {
        boolean isDarkTheme = UIManager.getBoolean("laf.dark");
        Color bg = ChipColorUtils.getBackgroundColor(kind, isDarkTheme);
        Color fg = ChipColorUtils.getForegroundColor(kind, isDarkTheme);
        Color border = ChipColorUtils.getBorderColor(kind, isDarkTheme);

        // Special styling for Task List: dedicated color scheme
        ContextFragment fragment = getPrimaryFragment();
        if (fragment instanceof ContextFragment.StringFragment sf) {
            if (SpecialTextType.TASK_LIST.description().equals(sf.description().renderNowOrNull())) {
                bg = ThemeColors.getColor(ThemeColors.CHIP_TASKLIST_BACKGROUND);
                fg = ThemeColors.getColor(ThemeColors.CHIP_TASKLIST_FOREGROUND);
                border = ThemeColors.getColor(ThemeColors.CHIP_TASKLIST_BORDER);
            }
        }

        setBackground(bg);
        label.setForeground(fg);

        borderColor = border;

        // Adjust separator to match label height
        int h = Math.max(label.getPreferredSize().height - 6, 10);
        separator.setBackground(border);
        separator.setPreferredSize(new Dimension(separator.getPreferredSize().width, h));
        separator.revalidate();
        separator.repaint();

        // Update close button icon with chip background
        closeButton.setIcon(buildCloseIcon(bg));

        updateReadOnlyIcon();

        revalidate();
        repaint();
    }

    public void updateReadOnlyIcon() {
        ContextFragment fragment = getPrimaryFragment();
        boolean show = fragment.getType().isEditable() && isFragmentReadOnly(fragment);

        if (show) {
            Icon icon = fitIconToChip(Icons.EDIT_OFF, label);
            readOnlyIcon.setIcon(icon);
            readOnlyIcon.setVisible(true);
        } else {
            readOnlyIcon.setVisible(false);
            readOnlyIcon.setIcon(null);
        }
        readOnlyIcon.revalidate();
        readOnlyIcon.repaint();
    }

    // Internal helpers

    protected void setFragmentsInternal(Set<ContextFragment> fragments) {
        assert !fragments.isEmpty();
        this.fragments = fragments;
    }

    protected ContextFragment getPrimaryFragment() {
        return fragments.iterator().next();
    }

    private boolean isFragmentReadOnly(ContextFragment fragment) {
        var ctx = contextManager.selectedContext();
        if (ctx == null) {
            return false;
        }
        return ctx.isMarkedReadonly(fragment);
    }

    private boolean isPanelReadOnly() {
        return readOnlySupplier.get();
    }

    private boolean isOnLatestContext() {
        return Objects.equals(contextManager.selectedContext(), contextManager.liveContext());
    }

    protected boolean ensureMutatingAllowed() {
        if (isPanelReadOnly() || !isOnLatestContext()) {
            chrome.systemNotify(WorkspaceItemsChipPanel.READ_ONLY_TIP, "Workspace", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    protected void dropSingleFragment(ContextFragment fragment) {
        contextManager.submitContextTask(() -> {
            try {
                // Clear any cached metrics for this fragment as it is being dropped.
                metricsCache.remove(fragment);

                if (fragment.getType() == ContextFragment.FragmentType.HISTORY || onRemoveFragment == null) {
                    contextManager.dropWithHistorySemantics(List.of(fragment));
                } else {
                    onRemoveFragment.accept(fragment);
                }
            } catch (Exception ex) {
                logger.error("Failed to drop fragment {}", fragment, ex);
            }
        });
    }

    protected void bindComputed() {
        ComputedSubscription.bind(getPrimaryFragment(), this, this::refreshLabelAndTooltip);
    }

    protected void refreshLabelAndTooltip() {
        ContextFragment fragment = getPrimaryFragment();
        // Ensure UI updates occur on the EDT and avoid scheduling background tasks.
        SwingUtilities.invokeLater(() -> {
            updateTextAndTooltip(fragment);
            applyTheme();
        });
    }

    protected void updateTextAndTooltip(ContextFragment fragment) {
        String newLabelText;
        if (kind == ChipKind.SUMMARY) {
            // Base WorkspaceChip is not used for summaries; SummaryChip overrides this.
            String sd = fragment.shortDescription().renderNowOr("Loading...");
            newLabelText = truncateForDisplay(sd);
        } else if (kind == ChipKind.OTHER) {
            String sd = fragment.shortDescription().renderNowOr("Loading...");
            newLabelText = truncateForDisplay(capitalizeFirst(sd));
        } else {
            String sd = fragment.shortDescription().renderNowOr("Loading...");
            if (sd.isBlank()) {
                // Keep whatever label text we already had (e.g., "Loading...")
                newLabelText = label.getText();
            } else {
                // Always truncate to keep chip width bounded so the close icon remains visible.
                newLabelText = truncateForDisplay(sd);
            }
        }
        // Only update label if it actually changed to avoid flicker
        String currText = label.getText();
        if (!Objects.equals(currText, newLabelText)) {
            label.setText(newLabelText);
        }

        String newTooltip = buildDefaultTooltip(fragment);
        String currentTooltip = label.getToolTipText();
        if (!Objects.equals(currentTooltip, newTooltip)) {
            label.setToolTipText(newTooltip);
        }

        // Update accessible description only if it changed
        String description = fragment.description().renderNowOr("");
        var ac = label.getAccessibleContext();
        if (ac != null) {
            String currentDesc = ac.getAccessibleDescription();
            if (!Objects.equals(currentDesc, newLabelText)) {
                ac.setAccessibleDescription(description);
            }
        }
    }

    protected Icon buildCloseIcon(Color chipBackground) {
        int targetW = 10;
        int targetH = 10;

        boolean isHighContrast = GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());
        if (isHighContrast) {
            BufferedImage icon = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = icon.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color iconColor = ai.brokk.difftool.utils.ColorUtil.contrastingText(chipBackground);
                g2.setColor(iconColor);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(2, 2, targetW - 3, targetH - 3);
                g2.drawLine(2, targetH - 3, targetW - 3, 2);
            } finally {
                g2.dispose();
            }
            return new ImageIcon(icon);
        }

        Icon uiIcon = javax.swing.UIManager.getIcon("Brokk.close");
        if (uiIcon == null) {
            uiIcon = Icons.CLOSE;
        }

        Icon source = uiIcon;
        Image scaled;
        if (source instanceof ImageIcon ii) {
            scaled = ii.getImage().getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
        } else {
            int w = Math.max(1, source.getIconWidth());
            int h = Math.max(1, source.getIconHeight());
            BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = buf.createGraphics();
            try {
                source.paintIcon(null, g2, 0, 0);
            } finally {
                g2.dispose();
            }
            scaled = buf.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
        }

        if (scaled == null) {
            BufferedImage fallback = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = fallback.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Color.GRAY);
                g2.drawLine(1, 1, targetW - 2, targetH - 2);
                g2.drawLine(1, targetH - 2, targetW - 2, 1);
            } finally {
                g2.dispose();
            }
            return new ImageIcon(fallback);
        }

        return new ImageIcon(scaled);
    }

    private Icon fitIconToChip(Icon base, JComponent reference) {
        int target = Math.max(12, reference.getPreferredSize().height - 4);
        try {
            if (base instanceof SwingUtil.ThemedIcon themed) {
                return themed.withSize(target);
            }
            int w = Math.max(1, base.getIconWidth());
            int h = Math.max(1, base.getIconHeight());
            BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = buf.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            base.paintIcon(null, g2, 0, 0);
            g2.dispose();
            Image scaled = buf.getScaledInstance(target, target, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Throwable t) {
            return base;
        }
    }

    // Context menu helpers

    private static boolean isDropAction(Object actionOrItem) {
        try {
            if (actionOrItem instanceof JMenuItem mi) {
                String text = mi.getText();
                return "Drop".equals(text);
            }
            if (actionOrItem instanceof Action a) {
                Object name = a.getValue(Action.NAME);
                return name instanceof String s && "Drop".equals(s);
            }
        } catch (Exception ex) {
            logger.debug("Error inspecting action/menu item for 'Drop'", ex);
        }
        return false;
    }

    protected @Nullable JPopupMenu createContextMenu() {
        ContextFragment fragment = getPrimaryFragment();
        JPopupMenu menu = new JPopupMenu();

        // Read-only toggle for editable fragments
        if (fragment.getType().isEditable()) {
            boolean onLatest = isOnLatestContext();
            String labelText = isFragmentReadOnly(fragment) ? "Unset Read-Only" : "Set Read-Only";
            JMenuItem toggleRo = new JMenuItem(labelText);
            toggleRo.setEnabled(onLatest && !isPanelReadOnly());
            toggleRo.addActionListener(e -> {
                if (!ensureMutatingAllowed()) {
                    return;
                }
                contextManager.pushContext(curr -> curr.setReadonly(fragment, !curr.isMarkedReadonly(fragment)));
            });
            menu.add(toggleRo);
            menu.addSeparator();
        }

        var scenario = new WorkspacePanel.SingleFragment(fragment);
        var actions = scenario.getActions(chrome.getContextPanel());
        boolean addedAnyAction = false;
        for (var action : actions) {
            if (isDropAction(action)) {
                continue;
            }
            menu.add(action);
            addedAnyAction = true;
        }

        JMenuItem dropOther = new JMenuItem("Drop Others");
        dropOther.getAccessibleContext().setAccessibleName("Drop Others");

        var selected = contextManager.selectedContext();
        if (selected == null) {
            dropOther.setEnabled(false);
        } else {
            var possible = selected.getAllFragmentsInDisplayOrder().stream()
                    .filter(f -> !Objects.equals(f, fragment))
                    .filter(f -> f.getType() != ContextFragment.FragmentType.HISTORY)
                    .toList();
            dropOther.setEnabled(!possible.isEmpty());
            dropOther.addActionListener(e -> {
                if (!ensureMutatingAllowed()) {
                    return;
                }

                var toDrop = selected.getAllFragmentsInDisplayOrder().stream()
                        .filter(f -> !Objects.equals(f, fragment))
                        .filter(f -> f.getType() != ContextFragment.FragmentType.HISTORY)
                        .toList();

                if (toDrop.isEmpty()) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No other non-history fragments to drop");
                    return;
                }

                contextManager.submitContextTask(() -> {
                    contextManager.dropWithHistorySemantics(toDrop);
                });
            });
        }

        if (addedAnyAction) {
            menu.addSeparator();
        }
        menu.add(dropOther);

        chrome.getThemeManager().registerPopupMenu(menu);
        return menu;
    }

    // Tooltip helpers

    private static String wrapTooltipHtml(String innerHtml, int maxWidthPx) {
        return "<html><body style='width: " + maxWidthPx + "px'>" + innerHtml + "</body></html>";
    }

    private static String formatCount(int count) {
        if (count < 1000) {
            return String.format("%,d", count);
        }
        return String.format("%.1fk", count / 1000.0);
    }

    private static String buildMetricsHtml(ContextFragment fragment) {
        if (fragment.isText() || fragment.getType().isOutput()) {
            FragmentMetrics metrics = getOrComputeMetrics(fragment);
            return String.format(
                    "<div>%s LOC • ~%s tokens</div><br/>", formatCount(metrics.loc()), formatCount(metrics.tokens()));
        }
        return "";
    }

    private static FragmentMetrics getOrComputeMetrics(ContextFragment fragment) {
        return metricsCache.computeIfAbsent(fragment, f -> {
            String text = f.text().renderNowOr("");
            int loc = text.split("\\r?\\n", -1).length;
            int tokens = Messages.getApproximateTokens(text);
            return new FragmentMetrics(loc, tokens);
        });
    }

    private static String buildDefaultTooltip(ContextFragment fragment) {
        String d = fragment.description().renderNowOr("");

        String descriptionHtml = StringEscapeUtils.escapeHtml4(d)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br/>");

        StringBuilder body = new StringBuilder();

        String metrics = buildMetricsHtml(fragment);
        if (!metrics.isEmpty()) {
            body.append(metrics);
        }

        body.append(descriptionHtml);
        body.append("<br/><br/><i>Click to preview contents</i>");

        return wrapTooltipHtml(body.toString(), 420);
    }

    // Utility

    private static String capitalizeFirst(String s) {
        if (s.isEmpty()) {
            return s;
        }
        int first = s.codePointAt(0);
        int upper = Character.toUpperCase(first);
        if (upper == first) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        sb.appendCodePoint(upper);
        sb.append(s.substring(Character.charCount(first)));
        return sb.toString();
    }

    /**
     * Truncate text for chip labels to ensure the close button remains visible when
     * descriptions are very long. The full text is still available in tooltips.
     */
    private static String truncateForDisplay(String text) {
        if (text.length() <= MAX_LABEL_CHARS) {
            return text;
        }
        // Reserve 3 characters for "..."
        int end = Math.max(0, MAX_LABEL_CHARS - 3);
        return text.substring(0, end) + "...";
    }

    /**
     * JLabel subclass that is safe to use in this package without extra imports in this file.
     */
    private static final class JLabelWithAccessible extends javax.swing.JLabel {
        JLabelWithAccessible() {
            super();
        }
    }

    /**
     * Synthetic chip that represents multiple summary fragments as one combined "Summaries" chip.
     */
    public static final class SummaryChip extends WorkspaceChip {

        /**
         * Enum representing the validity state of summary fragments in this chip.
         */
        private enum ValidityState {
            ALL_VALID,
            MIXED,
            ALL_INVALID
        }

        @SuppressWarnings("NullAway.Init") // Initialized in constructor
        private List<ContextFragment> summaryFragments;

        private int invalidSummaryCount = 0;

        /** Tracks validity state for painting */
        private ValidityState validityState = ValidityState.ALL_VALID;

        public SummaryChip(
                Chrome chrome,
                ContextManager contextManager,
                Supplier<Boolean> readOnlySupplier,
                @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback,
                @Nullable Consumer<ContextFragment> onRemoveFragment,
                List<ContextFragment> summaries) {
            super(
                    chrome,
                    contextManager,
                    readOnlySupplier,
                    hoverCallback,
                    onRemoveFragment,
                    new LinkedHashSet<>(summaries),
                    ChipKind.SUMMARY);

            // Rebind for all summaries instead of a single primary fragment
            ComputedSubscription.disposeAll(this);
            bindComputed();
            refreshLabelAndTooltip();
        }

        @Override
        protected void setFragmentsInternal(Set<ContextFragment> fragments) {
            super.setFragmentsInternal(fragments);
            this.summaryFragments = List.copyOf(fragments);
            updateValidityState();
        }

        @Override
        protected ContextFragment getPrimaryFragment() {
            return summaryFragments.getFirst();
        }

        public void updateSummaries(List<ContextFragment> newSummaries) {
            // Dispose existing subscriptions and clear metrics for fragments
            // that will no longer be represented by this chip.
            var oldFragments = new ArrayList<>(this.summaryFragments);
            ComputedSubscription.disposeAll(this);
            for (var f : oldFragments) {
                if (!newSummaries.contains(f)) {
                    metricsCache.remove(f);
                }
            }

            this.summaryFragments = new ArrayList<>(newSummaries);
            this.invalidSummaryCount =
                    (int) newSummaries.stream().filter(f -> !f.isValid()).count();
            super.setFragmentsInternal(new LinkedHashSet<>(newSummaries));
            updateValidityState();
            bindComputed();
            refreshLabelAndTooltip();
        }

        private void updateValidityState() {
            long invalidCount =
                    summaryFragments.stream().filter(f -> !f.isValid()).count();
            if (invalidCount == 0) {
                validityState = ValidityState.ALL_VALID;
            } else if (invalidCount == summaryFragments.size()) {
                validityState = ValidityState.ALL_INVALID;
            } else {
                validityState = ValidityState.MIXED;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (validityState == ValidityState.ALL_VALID || validityState == ValidityState.ALL_INVALID) {
                // All valid or all invalid: use standard painting from parent
                super.paintComponent(g);
                return;
            }

            // Mixed case: diagonal split painting with 45-degree line through center
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();

                // Get colors for both halves
                Color summaryBg = ThemeColors.getColor(ThemeColors.CHIP_SUMMARY_BACKGROUND);
                Color invalidBg = ThemeColors.getColor(ThemeColors.CHIP_INVALID_BACKGROUND);

                // Calculate 45-degree line endpoints through center
                // Line goes from bottom-left toward top-right (forward slash shape)
                int centerX = w / 2;
                int centerY = h / 2;
                int halfDiag = Math.max(w, h); // Ensure line extends beyond chip bounds

                // 45-degree line: for each unit right, go one unit up
                int x1 = centerX - halfDiag;
                int y1 = centerY + halfDiag;
                int x2 = centerX + halfDiag;
                int y2 = centerY - halfDiag;

                Polygon rightPoly = new Polygon();
                rightPoly.addPoint(x1, y1);
                rightPoly.addPoint(x2, y2);
                rightPoly.addPoint(w + halfDiag, h + halfDiag);
                rightPoly.addPoint(-halfDiag, h + halfDiag);

                Polygon leftPoly = new Polygon();
                leftPoly.addPoint(x1, y1);
                leftPoly.addPoint(x2, y2);
                leftPoly.addPoint(w + halfDiag, -halfDiag);
                leftPoly.addPoint(-halfDiag, -halfDiag);

                // Draw bottom-left half with valid (summary) color
                g2.setClip(leftPoly);
                g2.setColor(summaryBg);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);

                // Draw top-right half with invalid color
                g2.setClip(rightPoly);
                g2.setColor(invalidBg);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);

                // Reset clip for child painting
                g2.setClip(null);
            } finally {
                g2.dispose();
            }

            // Paint children (label, close button, etc.) - skip JPanel's paintComponent to avoid
            // overwriting our custom background
            paintChildren(g);
        }

        @Override
        protected void paintBorder(Graphics g) {
            if (validityState == ValidityState.ALL_VALID || validityState == ValidityState.ALL_INVALID) {
                super.paintBorder(g);
                return;
            }

            // Mixed case: draw border with a 45-degree diagonal line through center
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();

                // Use summary border color for consistency
                Color summaryBorder = ThemeColors.getColor(ThemeColors.CHIP_SUMMARY_BORDER);
                g2.setColor(summaryBorder);
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

                // Draw the 45-degree diagonal line through center (forward slash shape)
                int centerX = w / 2;
                int centerY = h / 2;
                int halfLen = Math.min(w, h) / 2;

                // Line from bottom-left to top-right, clipped to chip bounds
                int x1 = centerX - halfLen;
                int y1 = centerY + halfLen;
                int x2 = centerX + halfLen;
                int y2 = centerY - halfLen;

                Color invalidBorder = ThemeColors.getColor(ThemeColors.CHIP_INVALID_BORDER);
                g2.setColor(invalidBorder);
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawLine(x1, y1, x2, y2);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public void applyTheme() {
            updateValidityState();

            if (validityState == ValidityState.ALL_INVALID) {
                // All invalid: use invalid colors
                Color bg = ThemeColors.getColor(ThemeColors.CHIP_INVALID_BACKGROUND);
                Color fg = ThemeColors.getColor(ThemeColors.CHIP_INVALID_FOREGROUND);
                Color border = ThemeColors.getColor(ThemeColors.CHIP_INVALID_BORDER);

                setBackground(bg);
                label.setForeground(fg);
                borderColor = border;

                int h = Math.max(label.getPreferredSize().height - 6, 10);
                separator.setBackground(border);
                separator.setPreferredSize(new Dimension(separator.getPreferredSize().width, h));
                separator.revalidate();
                separator.repaint();

                closeButton.setIcon(buildCloseIconForChip(bg));
                updateReadOnlyIcon();
                revalidate();
                repaint();
            } else if (validityState == ValidityState.MIXED) {
                // Mixed: use summary foreground for text readability, but background is painted custom
                Color summaryBg = ThemeColors.getColor(ThemeColors.CHIP_SUMMARY_BACKGROUND);
                Color summaryFg = ThemeColors.getColor(ThemeColors.CHIP_SUMMARY_FOREGROUND);
                Color summaryBorder = ThemeColors.getColor(ThemeColors.CHIP_SUMMARY_BORDER);

                setBackground(summaryBg); // Base background for any unpainted areas
                label.setForeground(summaryFg);
                borderColor = summaryBorder;

                int h = Math.max(label.getPreferredSize().height - 6, 10);
                separator.setBackground(summaryBorder);
                separator.setPreferredSize(new Dimension(separator.getPreferredSize().width, h));
                separator.revalidate();
                separator.repaint();

                closeButton.setIcon(buildCloseIconForChip(summaryBg));
                updateReadOnlyIcon();
                revalidate();
                repaint();
            } else {
                // All valid: use standard summary colors via parent
                super.applyTheme();
            }
        }

        private Icon buildCloseIconForChip(Color chipBackground) {
            int targetW = 10;
            int targetH = 10;

            boolean isHighContrast = GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());
            if (isHighContrast) {
                BufferedImage icon = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = icon.createGraphics();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color iconColor = ai.brokk.difftool.utils.ColorUtil.contrastingText(chipBackground);
                    g2.setColor(iconColor);
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawLine(2, 2, targetW - 3, targetH - 3);
                    g2.drawLine(2, targetH - 3, targetW - 3, 2);
                } finally {
                    g2.dispose();
                }
                return new ImageIcon(icon);
            }

            Icon uiIcon = javax.swing.UIManager.getIcon("Brokk.close");
            if (uiIcon == null) {
                uiIcon = Icons.CLOSE;
            }

            Icon source = uiIcon;
            Image scaled;
            if (source instanceof ImageIcon ii) {
                scaled = ii.getImage().getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
            } else {
                int w = Math.max(1, source.getIconWidth());
                int h = Math.max(1, source.getIconHeight());
                BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = buf.createGraphics();
                try {
                    source.paintIcon(null, g2, 0, 0);
                } finally {
                    g2.dispose();
                }
                scaled = buf.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
            }

            if (scaled == null) {
                BufferedImage fallback = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = fallback.createGraphics();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(Color.GRAY);
                    g2.drawLine(1, 1, targetW - 2, targetH - 2);
                    g2.drawLine(1, targetH - 2, targetW - 2, 1);
                } finally {
                    g2.dispose();
                }
                return new ImageIcon(fallback);
            }

            return new ImageIcon(scaled);
        }

        @Override
        protected void bindComputed() {
            for (var f : summaryFragments) {
                ComputedSubscription.bind(f, this, this::refreshLabelAndTooltip);
            }
        }

        @Override
        protected void installHoverListeners() {
            if (hoverCallback == null) {
                return;
            }
            final int[] hoverCounter = {0};
            MouseAdapter hoverAdapter = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (hoverCounter[0]++ == 0) {
                        for (var summary : summaryFragments) {
                            try {
                                hoverCallback.accept(summary, true);
                            } catch (Exception ex) {
                                logger.trace("onHover callback threw", ex);
                            }
                        }
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (hoverCounter[0] > 0 && --hoverCounter[0] == 0) {
                        for (var summary : summaryFragments) {
                            try {
                                hoverCallback.accept(summary, false);
                            } catch (Exception ex) {
                                logger.trace("onHover callback threw", ex);
                            }
                        }
                    }
                }
            };

            this.addMouseListener(hoverAdapter);
            label.addMouseListener(hoverAdapter);
            closeButton.addMouseListener(hoverAdapter);
            separator.addMouseListener(hoverAdapter);
        }

        @Override
        protected void onPrimaryClick() {
            previewSyntheticChip();
        }

        @Override
        protected void onCloseClick() {
            if (!closeEnabled) {
                chrome.systemNotify(
                        WorkspaceItemsChipPanel.READ_ONLY_TIP, "Workspace", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (!ensureMutatingAllowed()) {
                return;
            }
            executeSyntheticChipDrop();
        }

        @Override
        protected void updateTextAndTooltip(ContextFragment fragment) {
            // Non-blocking reads; values refine as underlying ComputedValues complete.
            String text = computeSummaryLabel();
            String toolTip = computeAggregateSummaryTooltip();

            // Only update label text if changed
            String currentText = label.getText();
            if (!Objects.equals(currentText, text)) {
                label.setText(text);
            }

            // Only update tooltip if changed
            String currentTooltip = label.getToolTipText();
            if (!Objects.equals(currentTooltip, toolTip)) {
                label.setToolTipText(toolTip);
            }

            var ac = label.getAccessibleContext();
            if (ac != null) {
                String currentDesc = ac.getAccessibleDescription();
                String newDesc = "All summaries combined";
                if (!Objects.equals(currentDesc, newDesc)) {
                    ac.setAccessibleDescription(newDesc);
                }
            }
        }

        private String computeSummaryLabel() {
            return summaryFragments.size() > 0 ? "Summaries (" + summaryFragments.size() + ")" : "Summaries";
        }

        private String computeAggregateSummaryTooltip() {
            var allFiles = summaryFragments.stream()
                    .flatMap(f -> f.files().renderNowOr(Set.of()).stream())
                    .map(ProjectFile::toString)
                    .distinct()
                    .sorted()
                    .toList();

            StringBuilder body = new StringBuilder();

            // Best-effort metrics using non-blocking renders
            int totalLoc = 0;
            int totalTokens = 0;
            for (var summary : summaryFragments) {
                FragmentMetrics metrics = getOrComputeMetrics(summary);
                totalLoc += metrics.loc();
                totalTokens += metrics.tokens();
            }
            body.append("<div>")
                    .append(formatCount(totalLoc))
                    .append(" LOC • ~")
                    .append(formatCount(totalTokens))
                    .append(" tokens</div><br/>");

            body.append("<div><b>Summaries</b></div>");
            body.append("<hr style='border:0;border-top:1px solid #ccc;margin:4px 0 6px 0;'/>");

            if (allFiles.isEmpty()) {
                body.append("[No summaries with valid filenames]");
            } else {
                body.append("<ul style='margin:0;padding-left:16px'>");
                for (var f : allFiles) {
                    body.append("<li>").append(StringEscapeUtils.escapeHtml4(f)).append("</li>");
                }
                body.append("</ul>");
            }

            body.append("<br/><i>Click to preview all contents</i>");
            return wrapTooltipHtml(body.toString(), 420);
        }

        @Override
        protected JPopupMenu createContextMenu() {
            JPopupMenu menu = new JPopupMenu();
            var scenario = new WorkspacePanel.MultiFragment(summaryFragments);
            var actions = scenario.getActions(chrome.getContextPanel());
            boolean addedAnyAction = false;
            for (var action : actions) {
                if (action != null) {
                    String actionName = (String) action.getValue(Action.NAME);
                    if ("Summarize all References".equals(actionName)) {
                        continue;
                    }
                    if (isDropAction(action)) {
                        continue;
                    }
                }
                menu.add(action);
                addedAnyAction = true;
            }

            if (addedAnyAction) {
                menu.addSeparator();
            }

            // Add "Drop Invalid Summaries" if there are any invalid summaries
            if (invalidSummaryCount > 0) {
                var invalidSummaries =
                        summaryFragments.stream().filter(f -> !f.isValid()).toList();
                String dropInvalidLabel = invalidSummaryCount == 1
                        ? "Drop Invalid Summary"
                        : "Drop Invalid Summaries (" + invalidSummaryCount + ")";
                JMenuItem dropInvalidItem = new JMenuItem(dropInvalidLabel);
                dropInvalidItem.addActionListener(e -> {
                    if (!ensureMutatingAllowed()) {
                        return;
                    }
                    contextManager.submitContextTask(() -> {
                        for (var f : invalidSummaries) {
                            metricsCache.remove(f);
                        }
                        contextManager.dropWithHistorySemantics(invalidSummaries);
                    });
                });
                menu.add(dropInvalidItem);
                menu.addSeparator();
            }

            for (var fragment : summaryFragments) {
                String labelText = buildIndividualDropLabel(fragment);
                JMenuItem item = new JMenuItem(labelText);
                item.addActionListener(e -> dropSingleFragment(fragment));
                menu.add(item);
            }

            chrome.getThemeManager().registerPopupMenu(menu);
            return menu;
        }

        private String buildIndividualDropLabel(ContextFragment fragment) {
            return "Drop: " + fragment.shortDescription().renderNowOr("[loading]");
        }

        private void previewSyntheticChip() {
            int totalFiles = (int) summaryFragments.stream()
                    .flatMap(f -> f.files().renderNowOr(Set.of()).stream())
                    .map(ProjectFile::toString)
                    .distinct()
                    .count();
            String title = totalFiles > 0 ? "Summaries (" + totalFiles + ")" : "Summaries";

            StringBuilder combinedText = new StringBuilder();
            for (var summary : summaryFragments) {
                var txt = summary.text().renderNowOr("");
                combinedText.append(txt).append("\n\n");
            }

            // Use the most common syntax style among fragments
            var syntaxStyle = summaryFragments.stream()
                    .map(f -> f.syntaxStyle().renderNowOrNull())
                    .filter(s -> s != null)
                    .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                    .entrySet()
                    .stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(SyntaxConstants.SYNTAX_STYLE_NONE);

            var syntheticFragment = new ContextFragment.StringFragment(
                    chrome.getContextManager(), combinedText.toString(), title, syntaxStyle);
            chrome.openFragmentPreview(syntheticFragment);
        }

        private void executeSyntheticChipDrop() {
            contextManager.submitContextTask(() -> {
                try {
                    // Clear cached metrics for all fragments represented by this chip.
                    for (var f : summaryFragments) {
                        metricsCache.remove(f);
                    }
                    contextManager.dropWithHistorySemantics(summaryFragments);
                } catch (Exception ex) {
                    logger.error("Failed to drop summary fragments", ex);
                }
            });
        }
    }

    /**
     * Synthetic, non-droppable chip that represents the merged AGENTS.md style guide.
     * It is informational only and cannot be removed by the user.
     *
     * UI-only: shows system instructions visibility for the project Style Guide. It is not part of the
     * editable Workspace and will not appear in TokenUsageBar.
     */
    public static final class StyleGuideChip extends WorkspaceChip {

        private static final String LABEL_TEXT = "AGENTS.md";
        private static final String ACCESSIBLE_DESC =
                "Project style guide (AGENTS.md). Informational; cannot be removed.";
        private static final String TOOLTIP_HTML = wrapTooltipHtml(
                "<b>AGENTS.md</b> — informational Style Guide<br/>"
                        + "It is always applied automatically to prompts.<br/><br/>"
                        + "<i>This chip cannot be removed.</i>",
                420);

        public StyleGuideChip(
                Chrome chrome,
                ContextManager contextManager,
                Supplier<Boolean> readOnlySupplier,
                @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback,
                @Nullable Consumer<ContextFragment> onRemoveFragment,
                ContextFragment fragment) {
            super(
                    chrome,
                    contextManager,
                    readOnlySupplier,
                    hoverCallback,
                    onRemoveFragment,
                    Set.of(fragment),
                    ChipKind.OTHER);

            setCloseEnabled(false);
            closeButton.setVisible(false);
            separator.setVisible(false);
            label.setText(LABEL_TEXT);

            var ac = label.getAccessibleContext();
            if (ac != null) {
                ac.setAccessibleName(LABEL_TEXT);
                ac.setAccessibleDescription(ACCESSIBLE_DESC);
            }

            label.setToolTipText(TOOLTIP_HTML);
            closeButton.setToolTipText("Informational; cannot be removed");
        }

        @Override
        public void applyTheme() {
            // UI-only informational chip showing system instructions visibility; not part of the editable
            // Workspace and therefore intentionally omitted from TokenUsageBar.
            Color bg = ThemeColors.getColor(ThemeColors.NOTIF_INFO_BG);
            Color fg = ThemeColors.getColor(ThemeColors.NOTIF_INFO_FG);
            Color border = ThemeColors.getColor(ThemeColors.NOTIF_INFO_BORDER);

            setBackground(bg);
            label.setForeground(fg);
            borderColor = border;

            int h = Math.max(label.getPreferredSize().height - 6, 10);
            separator.setBackground(border);
            separator.setPreferredSize(new Dimension(separator.getPreferredSize().width, h));
            separator.revalidate();
            separator.repaint();

            // Ensure icon has sufficient contrast with background and keeps consistent sizing.
            closeButton.setIcon(buildCloseIcon(bg));
            updateReadOnlyIcon();

            revalidate();
            repaint();
        }

        @Override
        protected void onCloseClick() {
            chrome.systemNotify(
                    "AGENTS.md is informational and cannot be removed.", "Workspace", JOptionPane.INFORMATION_MESSAGE);
        }

        @Override
        protected void updateTextAndTooltip(ContextFragment fragment) {
            if (!Objects.equals(label.getText(), LABEL_TEXT)) {
                label.setText(LABEL_TEXT);
            }

            if (!Objects.equals(label.getToolTipText(), TOOLTIP_HTML)) {
                label.setToolTipText(TOOLTIP_HTML);
            }

            var ac = label.getAccessibleContext();
            if (ac != null) {
                if (!Objects.equals(ac.getAccessibleDescription(), ACCESSIBLE_DESC)) {
                    ac.setAccessibleDescription(ACCESSIBLE_DESC);
                }
            }
        }

        @Override
        protected void onPrimaryClick() {
            var selected = contextManager.selectedContext();

            final List<ProjectFile> candidateFiles;
            if (selected == null) {
                candidateFiles = List.of();
            } else {
                candidateFiles = selected.getAllFragmentsInDisplayOrder().stream()
                        .filter(f -> !(f instanceof ContextFragment.SummaryFragment))
                        .filter(f -> f.getType() != ContextFragment.FragmentType.SKELETON)
                        .flatMap(f -> f.files().renderNowOr(Set.of()).stream())
                        .distinct()
                        .collect(Collectors.toList());
            }

            contextManager
                    .submitBackgroundTask("Compute AGENTS.md", () -> {
                        try {
                            return StyleGuideResolver.resolve(candidateFiles, contextManager.getProject());
                        } catch (Throwable t) {
                            logger.warn("Failed to resolve style guide; using fallback", t);
                            return contextManager.getProject().getStyleGuide();
                        }
                    })
                    .thenAccept(content -> SwingUtilities.invokeLater(() -> {
                        var syntheticFragment = new ContextFragment.StringFragment(
                                chrome.getContextManager(), content, LABEL_TEXT, SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
                        chrome.openFragmentPreview(syntheticFragment);
                    }));
        }

        @Override
        protected @Nullable JPopupMenu createContextMenu() {
            ContextFragment fragment = getPrimaryFragment();
            JPopupMenu menu = new JPopupMenu();

            JMenuItem showContentsItem = new JMenuItem("Show Contents");
            showContentsItem.addActionListener(e -> onPrimaryClick());
            menu.add(showContentsItem);

            var scenario = new WorkspacePanel.SingleFragment(fragment);
            var actions = scenario.getActions(chrome.getContextPanel());
            for (var action : actions) {
                if (action == null) {
                    continue;
                }
                if (isDropAction(action)) {
                    continue;
                }
                Object nameObj = action.getValue(Action.NAME);
                if (nameObj instanceof String s) {
                    if ("Show Contents".equals(s)) {
                        // Replace default "Show Contents" with our custom item above
                        continue;
                    }
                    if ("Drop".equals(s) || "Drop Others".equals(s)) {
                        continue;
                    }
                }
                menu.add(action);
            }

            chrome.getThemeManager().registerPopupMenu(menu);
            return menu;
        }
    }
}
