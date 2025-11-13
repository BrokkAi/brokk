package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.MainProject;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.PreviewTextPanel;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.util.Messages;
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

    private static final String READ_ONLY_TIP = "Select latest activity to enable";

    // Cross-hover state: chip lookup by fragment id and external hover callback
    private final Map<String, RoundedChipPanel> chipById = new ConcurrentHashMap<>();
    private @Nullable JComponent syntheticSummaryChip = null;
    private @Nullable BiConsumer<ContextFragment, Boolean> onHover;
    private Set<ContextFragment> hoveredFragments = Set.of();
    private boolean readOnly = false;

    // Recompute label/tooltip for a chip based on current computed values and restyle
    private void refreshChipLabelAndTooltip(JComponent chip, JLabel label, ContextFragment fragment) {
        var kind = classify(fragment);
        String newLabelText;
        if (kind == ChipKind.SUMMARY) {
            newLabelText = buildSummaryLabel(fragment);
        } else if (kind == ChipKind.OTHER) {
            // Guard shortDescription()
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
            if (kind == ChipKind.SUMMARY) {
                label.setToolTipText(buildSummaryTooltip(fragment));
                label.getAccessibleContext().setAccessibleDescription(fragment.description());
            } else {
                label.setToolTipText(buildDefaultTooltip(fragment));
                label.getAccessibleContext().setAccessibleDescription(fragment.description());
            }
        } catch (Exception ex) {
            logger.debug("Failed to refresh chip tooltip for fragment {}", fragment, ex);
        }

        // Restyle to account for label size/separator height
        styleChip((JPanel) chip, label, fragment);
        chip.revalidate();
        chip.repaint();
    }

    // Subscribe to computed values to update the chip asynchronously; also set initial placeholder
    private void subscribeToComputedUpdates(ContextFragment fragment, JComponent chip, JLabel label) {
        if (!(fragment instanceof ContextFragment.ComputedFragment cf)) {
            return;
        }
        logger.debug("subscribeToComputedUpdates: attaching listeners for fragment {}", fragment);

        // Initial placeholder for summaries if files are not yet known
        if (classify(fragment) == ChipKind.SUMMARY) {
            var filesOpt = cf.computedFiles().tryGet();
            if (filesOpt.isEmpty()) {
                label.setText("Summary (Loading...)");
            }
        }

        // Bind all relevant computed updates to a single UI refresh (runs on EDT and auto-disposes with the chip)
        cf.bind(chip, () -> refreshChipLabelAndTooltip(chip, label, fragment));
    }

    // Update synthetic summary chip label/tooltip from current computed values
    private void refreshSyntheticSummaryChip(JComponent chip, JLabel label, List<ContextFragment> summaries) {
        // Chip presence check
        boolean present = false;
        for (var c : getComponents()) {
            if (c == chip) {
                present = true;
                break;
            }
        }
        if (!present) return;

        int totalFiles = (int) summaries.stream()
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
        String text = totalFiles > 0 ? "Summaries (" + totalFiles + ")" : "Summaries";
        label.setText(text);

        try {
            label.setToolTipText(buildAggregateSummaryTooltip(summaries));
        } catch (Exception ex) {
            logger.warn("Failed to build aggregate summary tooltip", ex);
        }

        try {
            label.getAccessibleContext().setAccessibleDescription("All summaries combined");
        } catch (Exception ex) {
            logger.warn("Failed to set accessibility description for synthetic chip", ex);
        }

        styleChip((JPanel) chip, label, null);
        chip.revalidate();
        chip.repaint();
    }

    // Subscribe to each summary's computed values to keep the synthetic chip updated
    private void subscribeSummaryGroupUpdates(List<ContextFragment> summaries, JComponent chip, JLabel label) {
        for (var f : summaries) {
            if (f instanceof ContextFragment.ComputedFragment cf) {
                // Bind per-summary computed updates to refresh the aggregate synthetic chip
                cf.bind((JComponent) chip, () -> refreshSyntheticSummaryChip(chip, label, summaries));
            }
        }
    }

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

            @Override
            public void mouseExited(MouseEvent e) {
                // Clear hover state when mouse leaves the entire panel
                applyGlobalStyling(Set.of());
            }
        });
    }

    private void handleBlankSpaceRightClick(MouseEvent e) {
        if (readOnly) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, READ_ONLY_TIP);
            return;
        }
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
     * Sets the fragments from a Context (historical or live). Safe to call from any thread.
     */
    public void setFragmentsForContext(Context context) {
        List<ContextFragment> frags = context.getAllFragmentsInDisplayOrder();
        SwingUtilities.invokeLater(() -> updateChips(frags));
    }

    /**
     * Enable or disable interactive behavior and visuals for read-only mode. Runs on the EDT.
     */
    public void setReadOnly(boolean readOnly) {
        SwingUtilities.invokeLater(() -> {
            this.readOnly = readOnly;
            // Clear hover effects when switching to read-only
            if (readOnly) {
                this.hoveredFragments = Set.of();
            }
            // Disable close buttons on all chips (but keep labels clickable for preview)
            for (var component : getComponents()) {
                if (component instanceof JComponent chip) {
                    var closeObj = chip.getClientProperty("brokk.chip.closeButton");
                    if (closeObj instanceof AbstractButton btn) {
                        btn.setEnabled(!readOnly);
                    }
                }
            }
            revalidate();
            repaint();
        });
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
        // Suppress hover highlighting in read-only mode
        this.hoveredFragments = readOnly ? Set.of() : targets;
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
        if (readOnly) {
            applyGlobalStyling(Set.of());
            return;
        }
        if (highlight) {
            applyGlobalStyling(Set.copyOf(fragments));
        } else {
            applyGlobalStyling(Set.of());
        }
    }

    private void updateChips(List<ContextFragment> fragments) {
        logger.debug(
                "updateChips (incremental) called with {} fragments (forceToolEmulation={} readOnly={})",
                fragments.size(),
                MainProject.getForceToolEmulation(),
                readOnly);

        // Filter out visually-empty fragments unless dev-mode override is enabled.
        var visibleFragments = fragments.stream()
                .filter(f -> MainProject.getForceToolEmulation() || hasRenderableContent(f))
                .toList();

        // Partition into summaries vs others (using only the visible set)
        var summaries = visibleFragments.stream()
                .filter(f -> classify(f) == ChipKind.SUMMARY)
                .toList();
        var others = visibleFragments.stream()
                .filter(f -> classify(f) != ChipKind.SUMMARY)
                .toList();

        logger.debug(
                "updateChips: {} visible ({} summaries, {} others) out of {}",
                visibleFragments.size(),
                summaries.size(),
                others.size(),
                fragments.size());

        // Build a new map for others (non-summaries) by id, preserving order
        Map<String, ContextFragment> newOthersById = new LinkedHashMap<>();
        for (var f : others) {
            newOthersById.put(f.id(), f);
        }

        // 1) Remove chips that are no longer present
        var toRemove = chipById.keySet().stream()
                .filter(oldId -> !newOthersById.containsKey(oldId))
                .toList();
        for (var id : toRemove) {
            var chip = chipById.remove(id);
            if (chip != null) {
                remove(chip);
            }
        }

        // 2) Add chips that are new
        for (var entry : newOthersById.entrySet()) {
            var id = entry.getKey();
            var frag = entry.getValue();
            if (!chipById.containsKey(id)) {
                // Final guard before creating UI
                if (!MainProject.getForceToolEmulation() && !hasRenderableContent(frag)) {
                    logger.debug("Skipping creation for newly-added fragment filtered by renderability: {}", frag);
                    continue;
                }
                var comp = createChip(frag);
                if (comp != null) {
                    add(comp);
                    chipById.put(id, (RoundedChipPanel) comp);
                }
            }
        }

        // 3) Reorder chips to match 'others' order
        int z = 0;
        for (var f : others) {
            var chip = chipById.get(f.id());
            if (chip != null) {
                try {
                    setComponentZOrder(chip, z++);
                } catch (Exception ex) {
                    logger.debug("Failed to set component z-order for fragment: {}", f.id(), ex);
                }
            }
        }

        // 4) Synthetic "Summaries" chip incremental management
        boolean anyRenderableSummary =
                summaries.stream().anyMatch(f -> MainProject.getForceToolEmulation() || hasRenderableContent(f));

        if (!anyRenderableSummary) {
            // Remove synthetic chip if present
            if (syntheticSummaryChip != null) {
                remove(syntheticSummaryChip);
                syntheticSummaryChip = null;
            }
        } else {
            if (syntheticSummaryChip == null) {
                var synthetic = createSyntheticSummaryChip(summaries);
                if (synthetic != null) {
                    syntheticSummaryChip = (JComponent) synthetic;
                    add(syntheticSummaryChip);
                }
            } else {
                // Update label/tooltip
                var labelObj = syntheticSummaryChip.getClientProperty("brokk.chip.label");
                if (labelObj instanceof JLabel lbl) {
                    refreshSyntheticSummaryChip(syntheticSummaryChip, lbl, summaries);
                }

                // Subscribe to new summary ids if needed
                @SuppressWarnings("unchecked")
                Set<String> prevIds = (Set<String>) syntheticSummaryChip.getClientProperty("brokk.summary.ids");
                if (prevIds == null) prevIds = Set.of();
                Set<String> nowIds =
                        summaries.stream().map(ContextFragment::id).collect(java.util.stream.Collectors.toSet());

                // Subscribe for newly added summary ids
                for (var f : summaries) {
                    if (!prevIds.contains(f.id()) && f instanceof ContextFragment.ComputedFragment) {
                        var rawChipLabel = syntheticSummaryChip.getClientProperty("brokk.chip.label");
                        if (rawChipLabel instanceof JLabel chipLabel) {
                            subscribeSummaryGroupUpdates(List.of(f), syntheticSummaryChip, chipLabel);
                        } else {
                            logger.warn(
                                    "Expected JLabel for 'brokk.chip.label' but found: {}",
                                    rawChipLabel == null
                                            ? "null"
                                            : rawChipLabel.getClass().getName());
                        }
                    }
                }
                syntheticSummaryChip.putClientProperty("brokk.summary.ids", nowIds);
            }

            // Keep synthetic at end
            if (syntheticSummaryChip != null) {
                try {
                    setComponentZOrder(syntheticSummaryChip, getComponentCount() - 1);
                } catch (Exception ex) {
                    logger.debug("Failed to set component z-order for synthetic summary chip", ex);
                }
            }
        }

        // Re-layout this panel minimally
        revalidate();
        repaint();

        // Also nudge ancestors so containers like BoxLayout recompute heights (rarely needed but safe)
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
                float alpha = 1.0f;
                if (getParent() instanceof WorkspaceItemsChipPanel parentPanel) {
                    // Dim all chips in read-only mode
                    if (parentPanel.readOnly) {
                        alpha = Math.min(alpha, 0.6f);
                    }
                    Object obj = getClientProperty("brokk.fragments");
                    if (obj instanceof Set<?> myFragments && !myFragments.isEmpty()) {
                        boolean hasHover = !parentPanel.hoveredFragments.isEmpty();
                        boolean isHovered =
                                hasHover && !Collections.disjoint(myFragments, parentPanel.hoveredFragments);
                        boolean isDimmed = hasHover && !isHovered;
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

    /**
     * Conservative predicate deciding whether a fragment has visible/renderable content.
     * <p>
     * Rules:
     * - Immediately return true for ComputedFragment or any dynamic fragment (isDynamic()).
     * - Always keep output fragments (history / outputs).
     * - For static non-computed text fragments: require non-blank text.
     * - For static non-text fragments: require at least an image, at least one file, or a non-empty description.
     * <p>
     * Any exception during evaluation causes the method to return true (fail-safe: show the fragment).
     */
    private boolean hasRenderableContent(ContextFragment f) {
        try {
            // Immediately render any computed or dynamic fragment to avoid hiding chips while content is computing.
            if (f instanceof ContextFragment.ComputedFragment) {
                return true;
            }

            // Always keep output fragments visible
            if (f.getType().isOutput()) {
                return true;
            }

            // Static, non-computed fragments: check current content
            if (f.isText()) {
                String txt = f.text();
                return !txt.trim().isEmpty();
            } else {
                boolean hasImage = f instanceof ContextFragment.ImageFragment;
                Set<ProjectFile> files = f.files();
                String desc = f.description();
                return hasImage || !files.isEmpty() || !desc.trim().isEmpty();
            }
        } catch (Exception ex) {
            // Be conservative: if we cannot decide, render the fragment to avoid hiding useful info.
            logger.debug("hasRenderableContent threw for fragment {}", f, ex);
            return true;
        }
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
        Set<ProjectFile> files;
        if (fragment instanceof ContextFragment.ComputedFragment cff) {
            files = cff.computedFiles().renderNowOr(Set.of());
        } else {
            files = fragment.files();
        }
        int n = (int) files.stream().map(ProjectFile::toString).distinct().count();
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

    private static String buildAggregateSummaryTooltip(List<ContextFragment> summaries) {
        var allFiles = summaries.stream()
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

        // Aggregate metrics (LOC + tokens) across all summaries
        int totalLoc = 0;
        int totalTokens = 0;
        try {
            for (var summary : summaries) {
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
        var files = ((fragment instanceof ContextFragment.ComputedFragment cff)
                        ? cff.computedFiles().renderNowOr(Set.of())
                        : fragment.files())
                .stream().map(ProjectFile::toString).distinct().sorted().toList();

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
            String d;
            if (fragment instanceof ContextFragment.ComputedFragment cf) {
                d = cf.computedDescription().renderNowOr("");
            } else {
                d = fragment.description();
            }
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
        String d;
        if (fragment instanceof ContextFragment.ComputedFragment cf) {
            d = cf.computedDescription().renderNowOr("");
        } else {
            d = fragment.description();
        }

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

    // Helper to identify a single-item "Drop" action/menu item
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

    private JPopupMenu buildChipContextMenu(ContextFragment fragment) {
        JPopupMenu menu = new JPopupMenu();
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

        // Add "Drop Others" action: remove all workspace fragments except this one,
        // preserving HISTORY fragments (task history).
        try {
            JMenuItem dropOther = new JMenuItem("Drop Others");
            try {
                dropOther.getAccessibleContext().setAccessibleName("Drop Others");
            } catch (Exception ex) {
                logger.trace("Failed to set accessible name for 'Drop Others' menu item", ex);
            }

            // Determine enabled state at menu construction time
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
                // Fail-safe: enable the action if we couldn't compute
                dropOther.setEnabled(true);
            }

            dropOther.addActionListener(e -> {
                if (readOnly) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, READ_ONLY_TIP);
                    return;
                }
                boolean onLatest = Objects.equals(contextManager.selectedContext(), contextManager.liveContext());
                if (!onLatest) {
                    chrome.systemNotify(
                            "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
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

            // Separate from scenario actions to emphasize the destructive multi-drop
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

    private String buildIndividualDropLabel(ContextFragment fragment) {
        var files = ((fragment instanceof ContextFragment.ComputedFragment cff)
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

        // Add separator only if there were scenario actions added
        if (addedAnyAction) {
            menu.addSeparator();
        }

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
        if (readOnly) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, READ_ONLY_TIP);
            return;
        }
        // Enforce latest-context gating (read-only when viewing historical context)
        boolean onLatest = Objects.equals(contextManager.selectedContext(), contextManager.liveContext());
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

    private @Nullable Component createChip(ContextFragment fragment) {
        // Defensive pre-check: guard against visually-empty fragments.
        if (!MainProject.getForceToolEmulation() && !hasRenderableContent(fragment)) {
            logger.debug("Skipping creation of chip for fragment (no renderable content): {}", fragment);
            return null;
        }

        // Safely read commonly-used fragment properties with fallbacks.
        String safeShortDescription;
        try {
            safeShortDescription = fragment.shortDescription();
            if (safeShortDescription == null || safeShortDescription.isBlank()) {
                safeShortDescription = "(no description)";
            }
        } catch (Exception e) {
            logger.debug("shortDescription() threw for fragment {}", fragment, e);
            safeShortDescription = "(no description)";
        }

        String safeDescription;
        try {
            safeDescription = fragment.description();
            if (safeDescription == null) safeDescription = "";
        } catch (Exception e) {
            logger.debug("description() threw for fragment {}", fragment, e);
            safeDescription = "";
        }

        // Last-line safety: re-check that the fragment still has renderable content before building UI.
        if (!MainProject.getForceToolEmulation() && !hasRenderableContent(fragment)) {
            logger.debug("Avoiding chip creation after re-check for fragment {}", fragment);
            return null;
        }

        var chip = new RoundedChipPanel();
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(false);

        // Use a default "Loading..." label for any ComputedFragment; otherwise derive from fragment kind
        ChipKind kindForLabel = classify(fragment);
        String labelText;
        if (fragment instanceof ContextFragment.ComputedFragment) {
            labelText = "Loading...";
        } else if (kindForLabel == ChipKind.SUMMARY) {
            labelText = buildSummaryLabel(fragment);
        } else if (kindForLabel == ChipKind.OTHER) {
            labelText = capitalizeFirst(safeShortDescription);
        } else {
            labelText = safeShortDescription;
        }
        var label = new JLabel(labelText);

        // Improve discoverability and accessibility with wrapped HTML tooltips
        try {
            if (kindForLabel == ChipKind.SUMMARY) {
                label.setToolTipText(buildSummaryTooltip(fragment));
                // Accessible description: use the full (non-HTML) description
                label.getAccessibleContext().setAccessibleDescription(safeDescription);
            } else {
                label.setToolTipText(buildDefaultTooltip(fragment));
                label.getAccessibleContext().setAccessibleDescription(safeDescription);
            }
        } catch (Exception ex) {
            // Defensive logging instead of ignoring to satisfy static analysis rules.
            logger.debug("Failed to set chip tooltip for fragment {}", fragment, ex);
        }

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(label, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
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

        // Subscribe to computed updates to refresh label/tooltip asynchronously (non-blocking)
        subscribeToComputedUpdates(fragment, chip, label);

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
            close.getAccessibleContext().setAccessibleName("Remove " + safeShortDescription);
        } catch (Exception ex) {
            logger.trace("Failed to set accessibility name for close button", ex);
        }
        close.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(close, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(close, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (readOnly) {
                    chrome.systemNotify(
                            "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
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
        } catch (Exception ex) {
            logger.error("Failed to index chip by fragment id for {}", fragment, ex);
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
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
                    JPopupMenu menu = buildChipContextMenu(fragment);
                    menu.show(chip, e.getX(), e.getY());
                    e.consume();
                    return;
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
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
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
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

    private @Nullable Component createSyntheticSummaryChip(List<ContextFragment> summaries) {
        if (summaries.isEmpty()) return null;

        var renderableSummaries = summaries.stream()
                .filter(f -> MainProject.getForceToolEmulation() || hasRenderableContent(f))
                .toList();

        if (renderableSummaries.isEmpty()) {
            logger.debug("No renderable summaries for synthetic chip; skipping creation.");
            return null;
        }

        var chip = new RoundedChipPanel();
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(false);

        var label = new JLabel();
        refreshSyntheticSummaryChip(chip, label, renderableSummaries);

        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        subscribeSummaryGroupUpdates(renderableSummaries, chip, label);

        var close = createStandardCloseButton(() -> executeSyntheticChipDrop(renderableSummaries));
        var sep = createStandardDivider(label);

        chip.add(label);
        chip.add(sep);
        chip.add(close);

        chip.putClientProperty("brokk.fragments", Set.copyOf(renderableSummaries));
        chip.putClientProperty("brokk.chip.closeButton", close);
        chip.putClientProperty("brokk.chip.label", label);
        chip.putClientProperty("brokk.chip.kind", ChipKind.SUMMARY);
        styleChip(chip, label, null);

        attachSyntheticChipMouseListeners(chip, label, sep, renderableSummaries);

        return chip;
    }

    private MaterialButton createStandardCloseButton(Runnable onClickAction) {
        var close = new MaterialButton("");
        close.setFocusable(false);
        close.setOpaque(false);
        close.setContentAreaFilled(false);
        close.setBorderPainted(false);
        close.setFocusPainted(false);
        close.setMargin(new Insets(0, 0, 0, 0));
        close.setPreferredSize(new Dimension(14, 14));
        close.setToolTipText("Remove all summaries from Workspace");
        close.getAccessibleContext().setAccessibleName("Remove all summaries");

        close.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleContextMenuTrigger(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    handleContextMenuTrigger(e);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (readOnly) {
                    chrome.systemNotify(
                            "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                if (!Objects.equals(contextManager.selectedContext(), contextManager.liveContext())) {
                    chrome.systemNotify(
                            "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                onClickAction.run();
            }
        });

        return close;
    }

    private JPanel createStandardDivider(JLabel label) {
        var sep = new JPanel();
        sep.putClientProperty("brokk.chip.separator", true);
        sep.setOpaque(true);
        sep.setPreferredSize(new Dimension(1, Math.max(label.getPreferredSize().height - 6, 10)));
        sep.setMinimumSize(new Dimension(1, 10));
        sep.setMaximumSize(new Dimension(1, Integer.MAX_VALUE));
        return sep;
    }

    private void handleContextMenuTrigger(MouseEvent e) {
        if (readOnly) {
            chrome.systemNotify("Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
            e.consume();
        }
    }

    private void attachSyntheticChipMouseListeners(
            JPanel chip, JLabel label, JPanel sep, List<ContextFragment> renderableSummaries) {
        final int[] hoverCounter = {0};
        MouseAdapter hoverAdapter = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (hoverCounter[0]++ == 0 && onHover != null) {
                    for (var summary : renderableSummaries) {
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
                    for (var summary : renderableSummaries) {
                        try {
                            onHover.accept(summary, false);
                        } catch (Exception ex) {
                            logger.trace("onHover callback threw", ex);
                        }
                    }
                }
            }
        };

        // Attach hover listeners to all components
        chip.addMouseListener(hoverAdapter);
        label.addMouseListener(hoverAdapter);
        var close = (MaterialButton) chip.getClientProperty("brokk.chip.closeButton");
        if (close != null) {
            close.addMouseListener(hoverAdapter);
        }
        sep.addMouseListener(hoverAdapter);

        // Label: click to preview or right-click for context menu
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
                    JPopupMenu menu = buildSyntheticChipContextMenu(renderableSummaries);
                    menu.show(label, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
                    JPopupMenu menu = buildSyntheticChipContextMenu(renderableSummaries);
                    menu.show(label, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    previewSyntheticChip(renderableSummaries);
                }
            }
        });

        // Chip background: right-click for context menu, left-click on label area for preview
        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
                    JPopupMenu menu = buildSyntheticChipContextMenu(renderableSummaries);
                    menu.show(chip, e.getX(), e.getY());
                    e.consume();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        e.consume();
                        return;
                    }
                    JPopupMenu menu = buildSyntheticChipContextMenu(renderableSummaries);
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
                    // Click on close button area
                    if (readOnly) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    if (!Objects.equals(contextManager.selectedContext(), contextManager.liveContext())) {
                        chrome.systemNotify(
                                "Select latest activity to enable", "Workspace", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    executeSyntheticChipDrop(renderableSummaries);
                } else {
                    // Click on label area
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                        previewSyntheticChip(renderableSummaries);
                    }
                }
            }
        });
    }

    private void previewSyntheticChip(List<ContextFragment> renderableSummaries) {
        int totalFiles = (int) renderableSummaries.stream()
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
        for (var summary : renderableSummaries) {
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

    private void executeSyntheticChipDrop(List<ContextFragment> renderableSummaries) {
        contextManager.submitContextTask(() -> {
            contextManager.dropWithHistorySemantics(renderableSummaries);
        });
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
