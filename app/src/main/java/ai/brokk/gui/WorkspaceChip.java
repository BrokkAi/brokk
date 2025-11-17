package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.MainProject;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.PreviewTextPanel;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.util.Icons;
import ai.brokk.util.ComputedSubscription;
import ai.brokk.util.Messages;
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
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Strongly typed chip component representing one or more ContextFragments.
 *
 * Encapsulates UI, tooltips, theming, hover behavior, and context menus, avoiding brittle
 * clientProperty string keys except for ComputedValue subscription bookkeeping.
 */
public class WorkspaceChip extends JPanel {

    public enum ChipKind {
        EDIT,
        SUMMARY,
        HISTORY,
        OTHER
    }

    private static final Logger logger = LogManager.getLogger(WorkspaceChip.class);

    protected final Chrome chrome;
    protected final ContextManager contextManager;
    protected final Supplier<Boolean> readOnlySupplier;
    protected final @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback;
    protected final @Nullable Consumer<ContextFragment> onRemoveFragment;
    protected final ChipKind kind;

    private Color borderColor = Color.GRAY;
    private final int arc = 12;

    protected final JLabelWithAccessible label;
    protected final JLabelWithAccessible readOnlyIcon;
    protected final MaterialButton closeButton;
    protected final JPanel separator;

    protected boolean closeEnabled = true;
    private Set<ContextFragment> fragments = Set.of();

    public WorkspaceChip(
            Chrome chrome,
            ContextManager contextManager,
            Supplier<Boolean> readOnlySupplier,
            @Nullable BiConsumer<ContextFragment, Boolean> hoverCallback,
            @Nullable Consumer<ContextFragment> onRemoveFragment,
            ContextFragment fragment,
            ChipKind kind) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.readOnlySupplier = readOnlySupplier;
        this.hoverCallback = hoverCallback;
        this.onRemoveFragment = onRemoveFragment;
        this.kind = kind;

        setFragmentsInternal(Set.of(fragment));

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
        String safeShortDescription = fragment != null ? fragment.shortDescription() : "(no description)";
        if (safeShortDescription.isBlank()) {
            safeShortDescription = "(no description)";
        }

        // Initial label text (may be updated once computed values are ready)
        if (fragment instanceof ContextFragment.ComputedFragment) {
            label.setText("Loading...");
        } else if (kind == ChipKind.OTHER) {
            label.setText(capitalizeFirst(safeShortDescription));
        } else {
            label.setText(safeShortDescription);
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
        add(separator);
        add(closeButton);

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
            menu.show(invoker, e.getX(), e.getY());
            e.consume();
        }
    }

    protected void onPrimaryClick() {
        ContextFragment fragment = getPrimaryFragment();
        if (fragment == null) {
            return;
        }
        // Ensure a single preview window that starts with "Loading..." and updates in-place.
        try {
            var panel = chrome.getContextPanel();
            panel.showFragmentPreview(fragment);
        } catch (Exception ex) {
            logger.error("Failed to open preview via WorkspacePanel; falling back to Chrome", ex);
            // Fallback (should not normally be needed)
            chrome.openFragmentPreview(fragment);
        }
    }

    protected void onCloseClick() {
        if (!closeEnabled) {
            chrome.systemNotify(WorkspaceItemsChipPanel.READ_ONLY_TIP, "Workspace", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!ensureMutatingAllowed()) {
            return;
        }
        ContextFragment fragment = getPrimaryFragment();
        if (fragment != null) {
            dropSingleFragment(fragment);
        }
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
                    if (fragment != null) {
                        try {
                            hoverCallback.accept(fragment, true);
                        } catch (Exception ex) {
                            logger.trace("onHover callback threw", ex);
                        }
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverCounter[0] > 0 && --hoverCounter[0] == 0) {
                    ContextFragment fragment = getPrimaryFragment();
                    if (fragment != null) {
                        try {
                            hoverCallback.accept(fragment, false);
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
                Set<ContextFragment> hovered = parentPanel.getHoveredFragments();
                if (!hovered.isEmpty() && !fragments.isEmpty()) {
                    boolean isHovered = !Collections.disjoint(fragments, hovered);
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
        Color bg;
        Color fg;
        Color border;

        switch (kind) {
            case EDIT -> {
                bg = ThemeColors.getColor(ThemeColors.CHIP_EDIT_BACKGROUND);
                fg = ThemeColors.getColor(ThemeColors.CHIP_EDIT_FOREGROUND);
                border = ThemeColors.getColor(ThemeColors.CHIP_EDIT_BORDER);
            }
            case SUMMARY -> {
                bg = ThemeColors.getColor(ThemeColors.CHIP_SUMMARY_BACKGROUND);
                fg = ThemeColors.getColor(ThemeColors.CHIP_SUMMARY_FOREGROUND);
                border = ThemeColors.getColor(ThemeColors.CHIP_SUMMARY_BORDER);
            }
            case HISTORY -> {
                bg = ThemeColors.getColor(ThemeColors.CHIP_HISTORY_BACKGROUND);
                fg = ThemeColors.getColor(ThemeColors.CHIP_HISTORY_FOREGROUND);
                border = ThemeColors.getColor(ThemeColors.CHIP_HISTORY_BORDER);
            }
            default -> {
                bg = ThemeColors.getColor(ThemeColors.CHIP_OTHER_BACKGROUND);
                fg = ThemeColors.getColor(ThemeColors.CHIP_OTHER_FOREGROUND);
                border = ThemeColors.getColor(ThemeColors.CHIP_OTHER_BORDER);
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
        boolean show = fragment != null && fragment.getType().isEditable() && isFragmentReadOnly(fragment);

        if (show) {
            JComponent ref = label;
            Icon icon = fitIconToChip(Icons.EDIT_OFF, ref);
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
        this.fragments = Set.copyOf(fragments);
    }

    protected @Nullable ContextFragment getPrimaryFragment() {
        if (fragments.isEmpty()) {
            return null;
        }
        return fragments.iterator().next();
    }

    private boolean isFragmentReadOnly(ContextFragment fragment) {
        var ctx = contextManager.selectedContext();
        if (ctx == null) {
            return false;
        }
        return ctx.isReadOnly(fragment);
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
        ContextFragment fragment = getPrimaryFragment();
        if (fragment instanceof ContextFragment.ComputedFragment cf) {
            ComputedSubscription.bind(cf, this, this::refreshLabelAndTooltip);
        }
    }

    protected void refreshLabelAndTooltip() {
        ContextFragment fragment = getPrimaryFragment();
        if (fragment == null) {
            return;
        }
        updateTextAndTooltip(fragment);
        applyTheme();
    }

    protected void updateTextAndTooltip(ContextFragment fragment) {
        String newLabelText;
        if (kind == ChipKind.SUMMARY) {
            // Base WorkspaceChip is not used for summaries; SummaryChip overrides this.
            newLabelText = fragment.shortDescription();
        } else if (kind == ChipKind.OTHER) {
            String sd;
            try {
                sd = fragment.shortDescription();
            } catch (Exception e) {
                logger.warn("Unable to obtain short description from {}!", fragment, e);
                sd = "<Error obtaining description>";
            }
            newLabelText = capitalizeFirst(sd);
        } else {
            String sd;
            try {
                sd = fragment.shortDescription();
            } catch (Exception e) {
                logger.warn("Unable to obtain short description from {}!", fragment, e);
                sd = "<Error obtaining description>";
            }
            newLabelText = sd.isBlank() ? label.getText() : sd;
        }
        label.setText(newLabelText);

        try {
            label.setToolTipText(buildDefaultTooltip(fragment));
            label.getAccessibleContext().setAccessibleDescription(fragment.description());
        } catch (Exception ex) {
            logger.debug("Failed to refresh chip tooltip for fragment {}", fragment, ex);
        }
    }

    private Icon buildCloseIcon(Color chipBackground) {
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
        if (fragment == null) {
            return null;
        }

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
                contextManager.pushContext(curr -> curr.setReadonly(fragment, !curr.isReadOnly(fragment)));
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

        try {
            JMenuItem dropOther = new JMenuItem("Drop Others");
            try {
                dropOther.getAccessibleContext().setAccessibleName("Drop Others");
            } catch (Exception ex) {
                logger.trace("Failed to set accessible name for 'Drop Others' menu item", ex);
            }

            try {
                var selected = contextManager.selectedContext();
                if (selected == null) {
                    dropOther.setEnabled(false);
                } else {
                    var possible = selected.getAllFragmentsInDisplayOrder().stream()
                            .filter(f -> !Objects.equals(f, fragment))
                            .filter(f -> f.getType() != ContextFragment.FragmentType.HISTORY)
                            .toList();
                    dropOther.setEnabled(!possible.isEmpty());
                }
            } catch (Exception ex) {
                dropOther.setEnabled(true);
            }

            dropOther.addActionListener(e -> {
                if (!ensureMutatingAllowed()) {
                    return;
                }
                var selected = contextManager.selectedContext();
                if (selected == null) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No context available");
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
                    try {
                        contextManager.dropWithHistorySemantics(toDrop);
                    } catch (Exception ex) {
                        logger.error("Drop Others action failed", ex);
                    }
                });
            });

            if (addedAnyAction) {
                menu.addSeparator();
            }
            menu.add(dropOther);
        } catch (Exception ex) {
            logger.debug("Failed to add 'Drop Others' action to chip popup", ex);
        }

        try {
            chrome.themeManager.registerPopupMenu(menu);
        } catch (Exception ex) {
            logger.debug("Failed to register chip popup menu with theme manager", ex);
        }

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
        try {
            if (fragment.isText() || fragment.getType().isOutput()) {
                String text;
                if (fragment instanceof ContextFragment.ComputedFragment cf) {
                    text = cf.computedText().renderNowOr("");
                } else {
                    text = fragment.text();
                }
                int loc = text.split("\\r?\\n", -1).length;
                int tokens = Messages.getApproximateTokens(text);
                return String.format("<div>%s LOC \u2022 ~%s tokens</div><br/>", formatCount(loc), formatCount(tokens));
            }
        } catch (Exception ex) {
            logger.trace("Failed to compute metrics for fragment {}", fragment, ex);
        }
        return "";
    }

    private static String buildDefaultTooltip(ContextFragment fragment) {
        String d;
        if (fragment instanceof ContextFragment.ComputedFragment cf) {
            d = cf.computedDescription().renderNowOr("");
        } else {
            d = fragment.description();
        }

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

        private List<ContextFragment> summaryFragments;

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
            this.summaryFragments = new ArrayList<>(summaries);

            // Rebind for all summaries instead of a single primary fragment
            ComputedSubscription.disposeAll(this);
            bindComputed();
            refreshLabelAndTooltip();
        }

        @Override
        protected void setFragmentsInternal(Set<ContextFragment> fragments) {
            super.setFragmentsInternal(fragments);
            this.summaryFragments = new ArrayList<>(fragments);
        }

        @Override
        protected @Nullable ContextFragment getPrimaryFragment() {
            if (summaryFragments.isEmpty()) return null;
            return summaryFragments.getFirst();
        }

        public void updateSummaries(List<ContextFragment> newSummaries) {
            ComputedSubscription.disposeAll(this);
            this.summaryFragments = new ArrayList<>(newSummaries);
            super.setFragmentsInternal(new LinkedHashSet<>(newSummaries));
            bindComputed();
            refreshLabelAndTooltip();
        }

        @Override
        protected void bindComputed() {
            for (var f : summaryFragments) {
                if (f instanceof ContextFragment.ComputedFragment cf) {
                    ComputedSubscription.bind(cf, this, this::refreshLabelAndTooltip);
                }
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
            String text = buildSummaryLabel();
            label.setText(text);
            try {
                label.setToolTipText(buildAggregateSummaryTooltip());
                label.getAccessibleContext().setAccessibleDescription("All summaries combined");
            } catch (Exception ex) {
                logger.warn("Failed to set tooltip for synthetic summary chip", ex);
            }
        }

        private String buildSummaryLabel() {
            int totalFiles = (int) summaryFragments.stream()
                    .flatMap(f -> {
                        if (f instanceof ContextFragment.ComputedFragment cff) {
                            return cff.computedFiles().renderNowOr(Set.of()).stream();
                        } else {
                            return f.files().stream();
                        }
                    })
                    .map(ProjectFile::toString)
                    .distinct()
                    .count();
            return totalFiles > 0 ? "Summaries (" + totalFiles + ")" : "Summaries";
        }

        private String buildAggregateSummaryTooltip() {
            var allFiles = summaryFragments.stream()
                    .flatMap(f -> {
                        if (f instanceof ContextFragment.ComputedFragment cff) {
                            return cff.computedFiles().renderNowOr(Set.of()).stream();
                        } else {
                            return f.files().stream();
                        }
                    })
                    .map(ProjectFile::toString)
                    .distinct()
                    .sorted()
                    .toList();

            StringBuilder body = new StringBuilder();

            int totalLoc = 0;
            int totalTokens = 0;
            try {
                for (var summary : summaryFragments) {
                    String text;
                    if (summary instanceof ContextFragment.ComputedFragment cf) {
                        text = cf.computedText().renderNowOr("");
                    } else {
                        text = summary.text();
                    }
                    totalLoc += text.split("\\r?\\n", -1).length;
                    totalTokens += Messages.getApproximateTokens(text);
                }
                body.append("<div>")
                        .append(formatCount(totalLoc))
                        .append(" LOC \u2022 ~")
                        .append(formatCount(totalTokens))
                        .append(" tokens</div><br/>");
            } catch (Exception e) {
                logger.error(e);
            }

            body.append("<div><b>Summaries</b></div>");
            body.append("<hr style='border:0;border-top:1px solid #ccc;margin:4px 0 6px 0;'/>");

            if (allFiles.isEmpty()) {
                body.append("Multiple summaries");
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

            for (var fragment : summaryFragments) {
                String labelText = buildIndividualDropLabel(fragment);
                JMenuItem item = new JMenuItem(labelText);
                item.addActionListener(e -> dropSingleFragment(fragment));
                menu.add(item);
            }

            try {
                chrome.themeManager.registerPopupMenu(menu);
            } catch (Exception ex) {
                logger.debug("Failed to register synthetic chip popup menu with theme manager", ex);
            }
            return menu;
        }

        private String buildIndividualDropLabel(ContextFragment fragment) {
            var files = (fragment instanceof ContextFragment.ComputedFragment cff
                            ? cff.computedFiles().renderNowOr(Set.of())
                            : fragment.files())
                    .stream()
                            .map(pf -> {
                                String path = pf.toString();
                                int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                                return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                            })
                            .toList();

            if (files.isEmpty()) {
                return "Drop: no files";
            }

            StringBuilder label = new StringBuilder("Drop: ");
            int charCount = 0;
            int filesAdded = 0;

            for (String file : files) {
                if (filesAdded > 0) {
                    if (charCount + 2 + file.length() > 20) {
                        label.append("...");
                        break;
                    }
                    label.append(", ");
                    charCount += 2;
                }

                if (charCount + file.length() > 20) {
                    int remaining = 20 - charCount;
                    if (remaining > 3) {
                        label.append(file, 0, remaining - 3).append("...");
                    } else {
                        label.append("...");
                    }
                    break;
                }

                label.append(file);
                charCount += file.length();
                filesAdded++;
            }

            if (filesAdded < files.size() && !label.toString().endsWith("...")) {
                label.append("...");
            }

            return label.toString();
        }

        private void previewSyntheticChip() {
            int totalFiles = (int) summaryFragments.stream()
                    .flatMap(f -> {
                        if (f instanceof ContextFragment.ComputedFragment cff) {
                            return cff.computedFiles().renderNowOr(Set.of()).stream();
                        } else {
                            return f.files().stream();
                        }
                    })
                    .map(ProjectFile::toString)
                    .distinct()
                    .count();
            String title = totalFiles > 0 ? "Summaries (" + totalFiles + ")" : "Summaries";

            StringBuilder combinedText = new StringBuilder();
            for (var summary : summaryFragments) {
                String txt = (summary instanceof ContextFragment.ComputedFragment cf)
                        ? cf.computedText().renderNowOr("")
                        : summary.text();
                combinedText.append(txt).append("\n\n");
            }

            var previewPanel = new PreviewTextPanel(
                    chrome.getContextManager(),
                    null,
                    combinedText.toString(),
                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                    chrome.getTheme(),
                    null);
            chrome.showPreviewFrame(chrome.getContextManager(), title, previewPanel);
        }

        private void executeSyntheticChipDrop() {
            contextManager.submitContextTask(() -> {
                try {
                    contextManager.dropWithHistorySemantics(summaryFragments);
                } catch (Exception ex) {
                    logger.error("Failed to drop summary fragments", ex);
                }
            });
        }
    }
}
