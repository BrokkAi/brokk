package io.github.jbellis.brokk.gui;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.dialogs.PreviewTextPanel;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * Displays current workspace items as "chips" with a close button to remove them from the workspace. Listens to context
 * changes and updates itself accordingly.
 */
public class WorkspaceItemsChipPanel extends JPanel implements ThemeAware, Scrollable {

    private final Chrome chrome;
    private final ContextManager contextManager;
    private @Nullable Consumer<ContextFragment> onRemoveFragment;

    // Logger for defensive debug logging in catch blocks (avoid empty catches)
    private static final Logger logger = LogManager.getLogger(WorkspaceItemsChipPanel.class);

    // Cross-hover state: chip lookup by fragment id and external hover callback
    private final Map<String, RoundedChipPanel> chipById = new ConcurrentHashMap<>();
    private @Nullable BiConsumer<ContextFragment, Boolean> onHover;
    private Set<ContextFragment> hoveredFragments = Set.of();

    public WorkspaceItemsChipPanel(Chrome chrome) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        setOpaque(false);
        this.chrome = chrome;
        this.contextManager = chrome.getContextManager();

        // Add right-click listener for blank space
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleBlankSpaceRightClick(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleBlankSpaceRightClick(e);
                }
            }
        });
    }

    private void handleBlankSpaceRightClick(MouseEvent e) {
        // Check if click is on blank space (not within any chip component)
        Component clickTarget = getComponentAt(e.getPoint());
        if (clickTarget != null && clickTarget != WorkspaceItemsChipPanel.this) {
            // Click is within a chip component, ignore
            return;
        }

        // Use NoSelection scenario to get standard blank-space actions
        var scenario = new WorkspacePanel.NoSelection();
        var actions = scenario.getActions(chrome.getContextPanel());

        // Show popup menu using PopupBuilder
        WorkspacePanel.PopupBuilder.create(chrome).add(actions).show(this, e.getX(), e.getY());
    }

    /**
     * Programmatically set the fragments to display as chips. Safe to call from any thread; updates are marshaled to
     * the EDT.
     */
    public void setFragments(List<ContextFragment> fragments) {
        SwingUtilities.invokeLater(() -> updateChips(fragments));
    }

    /**
     * Sets a listener invoked when a chip's remove button is clicked. If not set, the panel will default to removing
     * from the ContextManager.
     */
    public void setOnRemoveFragment(Consumer<ContextFragment> listener) {
        this.onRemoveFragment = listener;
    }

    /**
     * Set a callback invoked when the mouse enters/leaves a chip for a fragment.
     * Pass null to clear.
     */
    public void setOnHover(@Nullable BiConsumer<ContextFragment, Boolean> listener) {
        this.onHover = listener;
    }

    public void applyGlobalStyling(Set<ContextFragment> targets) {
        this.hoveredFragments = targets;
        for (var component : getComponents()) {
            if (component instanceof JComponent jc) {
                jc.repaint();
            }
        }
    }

    // Overload to support existing callers that pass a Collection
    public void applyGlobalStyling(Collection<ContextFragment> targets) {
        applyGlobalStyling(Set.copyOf(targets));
    }

    /**
     * Highlight or clear highlight for a collection of fragments' chips.
     * Safe to call from any thread; will marshal to the EDT.
     */
    public void highlightFragments(Collection<ContextFragment> fragments, boolean highlight) {
        if (highlight) {
            applyGlobalStyling(Set.copyOf(fragments));
        } else {
            applyGlobalStyling(Set.of());
        }
    }

    private void updateChips(List<ContextFragment> fragments) {
        removeAll();
        chipById.clear();

        // Partition into summaries vs others
        var summaries =
                fragments.stream().filter(f -> classify(f) == ChipKind.SUMMARY).toList();
        var others =
                fragments.stream().filter(f -> classify(f) != ChipKind.SUMMARY).toList();

        // Add individual chips for non-summaries
        for (var fragment : others) {
            add(createChip(fragment));
        }

        // Add synthetic summary chip if we have summaries
        if (!summaries.isEmpty()) {
            add(createSyntheticSummaryChip(summaries));
        }

        // Re-layout this panel
        revalidate();
        repaint();

        // Also nudge ancestors so containers like BoxLayout recompute heights
        Container p = getParent();
        while (p != null) {
            if (p instanceof JComponent jc) {
                jc.revalidate();
                jc.repaint();
            }
            p = p.getParent();
        }
    }

    private enum ChipKind {
        EDIT,
        SUMMARY,
        HISTORY,
        OTHER
    }

    // Rounded chip container that paints a rounded background and border
    private static final class RoundedChipPanel extends JPanel {
        private Color borderColor = Color.GRAY;
        private int arc = 12;

        RoundedChipPanel() {
            setOpaque(false);
        }

        void setBorderColor(Color c) {
            this.borderColor = c;
            repaint();
        }

        @Override
        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                if (getParent() instanceof WorkspaceItemsChipPanel parentPanel) {
                    Object obj = getClientProperty("brokk.fragments");
                    if (obj instanceof Set<?> myFragments && !myFragments.isEmpty()) {
                        boolean hasHover = !parentPanel.hoveredFragments.isEmpty();
                        boolean isHovered =
                                hasHover && !Collections.disjoint(myFragments, parentPanel.hoveredFragments);
                        boolean isDimmed = hasHover && !isHovered;
                        if (isDimmed) {
                            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                        }
                    }
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
    }

    private ChipKind classify(ContextFragment fragment) {
        // EDIT: fragments that are in the editable file stream of the currently selected context
        if (fragment.getType().isEditable()) {
            return ChipKind.EDIT;
        }
        // SUMMARY: fragments produced by summarize action are Skeletons
        if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
            return ChipKind.SUMMARY;
        }
        // HISTORY: fragments from the history stream
        if (fragment.getType() == ContextFragment.FragmentType.HISTORY) {
            return ChipKind.HISTORY;
        }
        // OTHER: everything else
        return ChipKind.OTHER;
    }

    // Scrollable support and width-tracking preferred size for proper wrapping inside JScrollPane
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        int rowH = Math.max(24, getFontMetrics(getFont()).getHeight() + 8);
        return Math.max(12, rowH / 2);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        int rowH = Math.max(24, getFontMetrics(getFont()).getHeight() + 8);
        return Math.max(rowH, visibleRect.height - rowH);
    }

    @Override
    public Dimension getPreferredSize() {
        // Track viewport width to compute wrapped height
        int width;
        var parent = getParent();
        if (parent instanceof JViewport vp) {
            width = vp.getWidth();
        } else {
            width = getWidth();
        }

        if (width <= 0) {
            return super.getPreferredSize();
        }

        Insets in = getInsets();
        int contentWidth = width - (in == null ? 0 : in.left + in.right);
        if (contentWidth <= 0) {
            return super.getPreferredSize();
        }

        int hgap = 6;
        int vgap = 4;
        if (getLayout() instanceof FlowLayout fl) {
            hgap = fl.getHgap();
            vgap = fl.getVgap();
        }

        int lineWidth = 0;
        int rows = 1;
        for (var comp : getComponents()) {
            if (!comp.isVisible()) continue;
            int w = comp.getPreferredSize().width;
            int next = (lineWidth == 0 ? w : lineWidth + hgap + w);
            if (next <= contentWidth) {
                lineWidth = next;
            } else {
                rows++;
                lineWidth = w;
            }
        }

        int rowH = Math.max(24, getFontMetrics(getFont()).getHeight() + 8);
        int height = (rows * rowH) + (rows > 1 ? (rows - 1) * vgap : 0);
        return new Dimension(width, Math.max(height, rowH));
    }

    // Tooltip helpers

    private static String wrapTooltipHtml(String innerHtml, int maxWidthPx) {
        // Use Swing's HTML renderer with a width style to enable wrapping.
        // The BasicHTML engine respects width on body in practice.
        return "<html><body style='width: " + maxWidthPx + "px'>" + innerHtml + "</body></html>";
    }

    private static String buildSummaryLabel(ContextFragment fragment) {
        int n = (int)
                fragment.files().stream().map(ProjectFile::toString).distinct().count();
        return "Summary" + (n > 0 ? " (" + n + ")" : "");
    }

    private static String formatCount(int count) {
        if (count < 1000) {
            return String.format("%,d", count);
        }
        return String.format("%.1fk", count / 1000.0);
    }

    /**
     * Builds an HTML snippet showing approximate size metrics (LOC and tokens) for the fragment. Returns an empty
     * string if metrics are not applicable (e.g., non-text/image fragments).
     */
    private static String buildMetricsHtml(ContextFragment fragment) {
        try {
            // Only compute for text-like fragments; non-text (e.g., images) do not have meaningful text metrics
            if (fragment.isText() || fragment.getType().isOutput()) {
                String text = fragment.text();
                int loc = text.split("\\r?\\n", -1).length;
                int tokens = Messages.getApproximateTokens(text);
                return String.format("<div>%s LOC \u2022 ~%s tokens</div><br/>", formatCount(loc), formatCount(tokens));
            }
        } catch (Exception ignored) {
            // Best effort; if anything goes wrong, just return no metrics
        }
        return "";
    }

    private static String buildAggregateSummaryTooltip(List<ContextFragment> summaries) {
        var allFiles = summaries.stream()
                .flatMap(f -> f.files().stream())
                .map(ProjectFile::toString)
                .distinct()
                .sorted()
                .toList();

        StringBuilder body = new StringBuilder();

        // Aggregate metrics (LOC + tokens) across all summaries
        int totalLoc = 0;
        int totalTokens = 0;
        try {
            for (var summary : summaries) {
                String text = summary.text();
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

        // Header and divider
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

    private static String buildSummaryTooltip(ContextFragment fragment) {
        var files = fragment.files().stream()
                .map(ProjectFile::toString)
                .distinct()
                .sorted()
                .toList();

        StringBuilder body = new StringBuilder();

        // Prepend metrics (LOC + tokens) if available
        String metrics = buildMetricsHtml(fragment);
        if (!metrics.isEmpty()) {
            body.append(metrics);
        }

        // Header and divider
        body.append("<div><b>Summaries</b></div>");
        body.append("<hr style='border:0;border-top:1px solid #ccc;margin:4px 0 6px 0;'/>");

        if (files.isEmpty()) {
            // Fallback: if no files are available, show any description as a last resort
            String d = fragment.description();
            body.append(StringEscapeUtils.escapeHtml4(d));
        } else {
            body.append("<ul style='margin:0;padding-left:16px'>");
            for (var f : files) {
                body.append("<li>").append(StringEscapeUtils.escapeHtml4(f)).append("</li>");
            }
            body.append("</ul>");
        }

        body.append("<br/><i>Click to preview contents</i>");
        return wrapTooltipHtml(body.toString(), 420);
    }

    private static String buildDefaultTooltip(ContextFragment fragment) {
        String d = fragment.description();

        // Preserve existing newlines as line breaks for readability
        String descriptionHtml = StringEscapeUtils.escapeHtml4(d)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "<br/>");

        StringBuilder body = new StringBuilder();

        // Prepend metrics (LOC + tokens) if available
        String metrics = buildMetricsHtml(fragment);
        if (!metrics.isEmpty()) {
            body.append(metrics);
        }

        body.append(descriptionHtml);
        body.append("<br/><br/><i>Click to preview contents</i>");

        return wrapTooltipHtml(body.toString(), 420);
    }

    // Capitalize only the first character of the given string; leaves the rest unchanged.
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

    private void styleChip(JPanel chip, JLabel label, @Nullable ContextFragment fragment) {
        ChipKind kind = fragment == null ? ChipKind.OTHER : classify(fragment);

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

        chip.setBackground(bg);
        label.setForeground(fg);

        // Rounded look: padding as inner border, rounded border painted by RoundedChipPanel
        var inner = new EmptyBorder(2, 8, 2, 6);
        if (chip instanceof RoundedChipPanel rc) {
            rc.setBorderColor(border);
            chip.setBorder(inner);
        } else {
            // Fallback for non-rounded panel (shouldn't happen with current creation)
            var outer = new MatteBorder(1, 1, 1, 1, border);
            chip.setBorder(new CompoundBorder(outer, inner));
        }

        // Style the divider (vertical line) between label and close button
        for (var child : chip.getComponents()) {
            if (child instanceof JPanel p && Boolean.TRUE.equals(p.getClientProperty("brokk.chip.separator"))) {
                p.setBackground(border);
                var pref = p.getPreferredSize();
                int h = Math.max(label.getPreferredSize().height - 6, 10);
                p.setPreferredSize(new Dimension(pref.width, h));
                p.revalidate();
                p.repaint();
            }
        }

        // Update close button icon with the chip's background color for proper contrast in high-contrast mode
        var closeObj = chip.getClientProperty("brokk.chip.closeButton");
        if (closeObj instanceof MaterialButton closeButton) {
            closeButton.setIcon(buildCloseIcon(bg));
        }
    }

    private Icon buildCloseIcon(Color chipBackground) {
        int targetW = 10;
        int targetH = 10;

        // In high-contrast mode, draw a theme-aware × with proper contrast against the chip background
        boolean isHighContrast = GuiTheme.THEME_HIGH_CONTRAST.equalsIgnoreCase(MainProject.getTheme());
        if (isHighContrast) {
            BufferedImage icon = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = icon.createGraphics();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Use contrasting color based on the chip's background
                Color iconColor = ColorUtil.contrastingText(chipBackground);
                g2.setColor(iconColor);
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawLine(2, 2, targetW - 3, targetH - 3);
                g2.drawLine(2, targetH - 3, targetW - 3, 2);
            } finally {
                g2.dispose();
            }
            return new ImageIcon(icon);
        }

        // For non-high-contrast themes, use the standard icon approach
        var uiIcon = UIManager.getIcon("Brokk.close");
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
            // Robust fallback: draw a simple X
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

    private JPopupMenu buildChipContextMenu(ContextFragment fragment) {
        JPopupMenu menu = new JPopupMenu();
        var scenario = new WorkspacePanel.SingleFragment(fragment);
        var actions = scenario.getActions(chrome.getContextPanel());
        for (var action : actions) {
            menu.add(action);
        }
        try {
            chrome.themeManager.registerPopupMenu(menu);
        } catch (Exception ex) {
            logger.debug("Failed to register chip popup menu with theme manager", ex);
        }
        return menu;
    }

    private String buildIndividualDropLabel(ContextFragment fragment) {
        var files = fragment.files().stream()
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
                // Truncate this filename
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

        // Add ellipsis if there are more files we didn't include
        if (filesAdded < files.size() && !label.toString().endsWith("...")) {
            label.append("...");
        }

        return label.toString();
    }

    private JPopupMenu buildSyntheticChipContextMenu(List<ContextFragment> fragments) {
        JPopupMenu menu = new JPopupMenu();
        var scenario = new WorkspacePanel.MultiFragment(fragments);
        var actions = scenario.getActions(chrome.getContextPanel());
        for (var action : actions) {
            menu.add(action);
        }

        // Add separator
        menu.addSeparator();

        // Add individual drop actions for each fragment
        for (var fragment : fragments) {
            String label = buildIndividualDropLabel(fragment);
            JMenuItem item = new JMenuItem(label);
            item.addActionListener(e -> executeCloseChip(fragment));
            menu.add(item);
        }

        try {
            chrome.themeManager.registerPopupMenu(menu);
        } catch (Exception ex) {
            logger.debug("Failed to register chip popup menu with theme manager", ex);
        }
        return menu;
    }

    private void executeCloseChip(ContextFragment fragment) {
        // Enforce latest-context gating (read-only when viewing historical context)
        boolean onLatest = Objects.equals(contextManager.selectedContext(), contextManager.topContext());
        if (!onLatest) {
            chrome.systemNotify("Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Perform the removal via the ContextManager task queue to avoid
        // listener reentrancy and ensure proper processing of the drop.
        chrome.getContextManager().submitContextTask(() -> {
            if (fragment.getType() == ContextFragment.FragmentType.HISTORY || onRemoveFragment == null) {
                // Centralized HISTORY-aware semantics
                contextManager.dropWithHistorySemantics(List.of(fragment));
            } else {
                // Allow custom removal logic for non-history when provided
                onRemoveFragment.accept(fragment);
            }
        });
    }

    private Component createChip(ContextFragment fragment) {
        var chip = new RoundedChipPanel();
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(false);

        // Use a compact label for SUMMARY chips; otherwise use the fragment's shortDescription
        ChipKind kindForLabel = classify(fragment);
        String labelText;
        if (kindForLabel == ChipKind.SUMMARY) {
            labelText = buildSummaryLabel(fragment);
        } else if (kindForLabel == ChipKind.OTHER) {
            labelText = capitalizeFirst(fragment.shortDescription());
        } else {
            labelText = fragment.shortDescription();
        }
        var label = new JLabel(labelText);

        // Improve discoverability and accessibility with wrapped HTML tooltips
        try {
            if (kindForLabel == ChipKind.SUMMARY) {
                label.setToolTipText(buildSummaryTooltip(fragment));
                // Accessible description: use the full (non-HTML) description
                label.getAccessibleContext().setAccessibleDescription(fragment.description());
            } else {
                label.setToolTipText(buildDefaultTooltip(fragment));
                label.getAccessibleContext().setAccessibleDescription(fragment.description());
            }
        } catch (Exception ex) {
            // Defensive logging instead of ignoring to satisfy static analysis rules.
            logger.debug("Failed to set chip tooltip for fragment {}", fragment, ex);
        }

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(label, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(label, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    chrome.openFragmentPreview(fragment);
                }
            }
        });

        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // MaterialButton does not provide a constructor that accepts an Icon on this classpath.
        // Construct with an empty label. Icon will be set by styleChip() after background color is determined.
        var close = new MaterialButton("");
        close.setFocusable(false);
        // keep the icon-only styling but keep hit area reasonable
        close.setOpaque(false);
        close.setContentAreaFilled(false);
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setMargin(new Insets(0, 0, 0, 0));
        close.setPreferredSize(new Dimension(14, 14));
        close.setToolTipText("Remove from Workspace");
        try {
            close.getAccessibleContext().setAccessibleName("Remove " + fragment.shortDescription());
        } catch (Exception ignored) {
            // best-effort accessibility improvements
        }
        close.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(close, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(close, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                executeCloseChip(fragment);
            }
        });

        chip.add(label);

        // Add a slim vertical divider between label and close button
        var sep = new JPanel();
        sep.putClientProperty("brokk.chip.separator", true);
        sep.setOpaque(true);
        sep.setPreferredSize(new Dimension(1, Math.max(label.getPreferredSize().height - 6, 10)));
        sep.setMinimumSize(new Dimension(1, 10));
        sep.setMaximumSize(new Dimension(1, Integer.MAX_VALUE));
        chip.add(sep);

        chip.add(close);

        // Keep a handle to the fragments and close button so theme changes can restyle accurately
        chip.putClientProperty("brokk.fragments", Set.of(fragment));
        chip.putClientProperty("brokk.chip.closeButton", close);
        chip.putClientProperty("brokk.chip.label", label);
        // Track by id for grouped-segment multi-highlight
        try {
            chipById.put(fragment.id(), chip);
        } catch (Exception ignored) {
            // best-effort; id() should be stable, but guard against any exceptions
        }
        styleChip(chip, label, fragment);

        // Hover handlers: simple glow on enter; restore styling on exit; forward to external listener
        final int[] hoverCounter = {0};
        MouseAdapter hoverAdapter = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (hoverCounter[0]++ == 0) {
                    if (onHover != null) {
                        try {
                            onHover.accept(fragment, true);
                        } catch (Exception ex) {
                            logger.trace("onHover callback threw", ex);
                        }
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverCounter[0] > 0 && --hoverCounter[0] == 0) {
                    if (onHover != null) {
                        try {
                            onHover.accept(fragment, false);
                        } catch (Exception ex) {
                            logger.trace("onHover callback threw", ex);
                        }
                    }
                }
            }
        };
        chip.addMouseListener(hoverAdapter);
        label.addMouseListener(hoverAdapter);
        close.addMouseListener(hoverAdapter);
        sep.addMouseListener(hoverAdapter);

        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(chip, e.getX(), e.getY());
                    e.consume();
                    return;
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(chip, e.getX(), e.getY());
                    e.consume();
                    return;
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.isConsumed()) return; // popup already handled
                int clickX = e.getX();
                int separatorEndX = sep.getX() + sep.getWidth();
                if (clickX > separatorEndX) {
                    executeCloseChip(fragment);
                } else {
                    // Open preview on left-click anywhere on the chip (excluding close button which handles its own
                    // events)
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                        chrome.openFragmentPreview(fragment);
                    }
                }
            }
        });

        return chip;
    }

    private Component createSyntheticSummaryChip(List<ContextFragment> summaries) {
        var chip = new RoundedChipPanel();
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(false);

        // Label: aggregate count
        int totalFiles = (int) summaries.stream()
                .flatMap(f -> f.files().stream())
                .map(ProjectFile::toString)
                .distinct()
                .count();
        String labelText = totalFiles > 0 ? "Summaries (" + totalFiles + ")" : "Summaries";
        var label = new JLabel(labelText);

        // Aggregated tooltip
        try {
            label.setToolTipText(buildAggregateSummaryTooltip(summaries));
            label.getAccessibleContext().setAccessibleDescription("All summaries combined");
        } catch (Exception ex) {
            logger.debug("Failed to set synthetic chip tooltip", ex);
        }

        // Click to preview all summaries
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildSyntheticChipContextMenu(summaries);
                    menu.show(label, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildSyntheticChipContextMenu(summaries);
                    menu.show(label, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    int totalFiles = (int) summaries.stream()
                            .flatMap(f -> f.files().stream())
                            .map(ProjectFile::toString)
                            .distinct()
                            .count();
                    String title = totalFiles > 0 ? "Summaries (" + totalFiles + ")" : "Summaries";

                    // Concatenate all summary text like the copy operation does
                    StringBuilder combinedText = new StringBuilder();
                    for (var summary : summaries) {
                        combinedText.append(summary.text()).append("\n\n");
                    }

                    // Display in a regular PreviewTextPanel like other text content
                    var previewPanel = new PreviewTextPanel(
                            chrome.getContextManager(),
                            null,
                            combinedText.toString(),
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                            chrome.getTheme(),
                            null);
                    chrome.showPreviewFrame(chrome.getContextManager(), title, previewPanel);
                }
            }
        });

        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        var close = new MaterialButton("");
        close.setFocusable(false);
        close.setOpaque(false);
        close.setContentAreaFilled(false);
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setMargin(new Insets(0, 0, 0, 0));
        close.setPreferredSize(new Dimension(14, 14));
        close.setToolTipText("Remove all summaries from Workspace");
        try {
            close.getAccessibleContext().setAccessibleName("Remove all summaries");
        } catch (Exception e) {
            logger.error(e);
        }
        close.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildSyntheticChipContextMenu(summaries);
                    menu.show(close, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildSyntheticChipContextMenu(summaries);
                    menu.show(close, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                boolean onLatest = Objects.equals(contextManager.selectedContext(), contextManager.topContext());
                if (!onLatest) {
                    chrome.systemNotify(
                            "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }

                contextManager.submitContextTask(() -> {
                    contextManager.dropWithHistorySemantics(summaries);
                });
            }
        });

        chip.add(label);

        // Add a slim vertical divider between label and close button
        var sep = new JPanel();
        sep.putClientProperty("brokk.chip.separator", true);
        sep.setOpaque(true);
        sep.setPreferredSize(new Dimension(1, Math.max(label.getPreferredSize().height - 6, 10)));
        sep.setMinimumSize(new Dimension(1, 10));
        sep.setMaximumSize(new Dimension(1, Integer.MAX_VALUE));
        chip.add(sep);

        chip.add(close);

        chip.putClientProperty("brokk.fragments", Set.copyOf(summaries));
        chip.putClientProperty("brokk.chip.closeButton", close);
        chip.putClientProperty("brokk.chip.label", label);
        chip.putClientProperty("brokk.chip.kind", ChipKind.SUMMARY);
        styleChip(chip, label, null);

        // Hover handlers for the synthetic chip
        final int[] hoverCounter = {0};
        MouseAdapter hoverAdapter = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (hoverCounter[0]++ == 0 && onHover != null) {
                    for (var summary : summaries) {
                        try {
                            onHover.accept(summary, true);
                        } catch (Exception ex) {
                            logger.trace("onHover callback threw", ex);
                        }
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverCounter[0] > 0 && --hoverCounter[0] == 0 && onHover != null) {
                    for (var summary : summaries) {
                        try {
                            onHover.accept(summary, false);
                        } catch (Exception ex) {
                            logger.trace("onHover callback threw", ex);
                        }
                    }
                }
            }
        };
        chip.addMouseListener(hoverAdapter);
        label.addMouseListener(hoverAdapter);
        close.addMouseListener(hoverAdapter);
        sep.addMouseListener(hoverAdapter);

        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildSyntheticChipContextMenu(summaries);
                    menu.show(chip, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = buildSyntheticChipContextMenu(summaries);
                    menu.show(chip, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.isConsumed()) return;
                int clickX = e.getX();
                int separatorEndX = sep.getX() + sep.getWidth();
                if (clickX > separatorEndX) {
                    boolean onLatest = Objects.equals(contextManager.selectedContext(), contextManager.topContext());
                    if (!onLatest) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }

                    contextManager.submitContextTask(() -> {
                        contextManager.dropWithHistorySemantics(summaries);
                    });
                } else {
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                        int totalFiles = (int) summaries.stream()
                                .flatMap(f -> f.files().stream())
                                .map(ProjectFile::toString)
                                .distinct()
                                .count();
                        String title = totalFiles > 0 ? "Summaries (" + totalFiles + ")" : "Summaries";

                        // Concatenate all summary text like the copy operation does
                        StringBuilder combinedText = new StringBuilder();
                        for (var summary : summaries) {
                            combinedText.append(summary.text()).append("\n\n");
                        }

                        // Display in a regular PreviewTextPanel like other text content
                        var previewPanel = new PreviewTextPanel(
                                chrome.getContextManager(),
                                null,
                                combinedText.toString(),
                                SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                                chrome.getTheme(),
                                null);
                        chrome.showPreviewFrame(chrome.getContextManager(), title, previewPanel);
                    }
                }
            }
        });

        return chip;
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        applyTheme(guiTheme, false);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        SwingUtilities.invokeLater(() -> {
            restyleAllChips();
            // Defer a second pass to catch any late UIManager icon changes after LAF/theme switch
            SwingUtilities.invokeLater(this::restyleAllChips);
        });
    }

    private void restyleAllChips() {
        for (var component : getComponents()) {
            if (component instanceof JPanel chip) {
                JLabel label = null;
                for (var child : chip.getComponents()) {
                    if (child instanceof JLabel jLabel) {
                        label = jLabel;
                        break;
                    }
                }
                if (label != null) {
                    var fragsObj = chip.getClientProperty("brokk.fragments");
                    ContextFragment fragment = null;
                    if (fragsObj instanceof Set<?> fragSet && !fragSet.isEmpty()) {
                        fragment = (ContextFragment) fragSet.iterator().next();
                    }
                    // styleChip() now updates the close button icon with proper background color
                    styleChip(chip, label, fragment);
                }
            }
        }
    }
}
