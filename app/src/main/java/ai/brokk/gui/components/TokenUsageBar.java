package ai.brokk.gui.components;

import ai.brokk.Service;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.FragmentColorUtils;
import ai.brokk.gui.dialogs.PreviewTextPanel;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.Messages;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.swing.*;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.text.WordUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

public class TokenUsageBar extends JComponent implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(TokenUsageBar.class);

    public enum WarningLevel {
        NONE,
        YELLOW,
        RED
    }

    private WarningLevel warningLevel = WarningLevel.NONE;

    @Nullable
    private Service.ModelConfig modelConfig = null;

    // Fallback counters if fragments aren't provided
    private int fallbackCurrentTokens = 0;

    private int maxTokens = 1; // Avoid division by zero

    // Hovered segment state and callbacks
    @Nullable
    private volatile Segment hoveredSegment = null;

    @Nullable
    private volatile BiConsumer<Collection<ContextFragment>, Boolean> onHoverFragments = null;

    @Nullable
    private volatile Runnable onClick = null;

    // Rounded rectangle arc (diameter for corner rounding). Radius is arc/2.
    private static final int ARC = 8;
    private static final int MIN_SEGMENT_PX = 8; // 2x radius
    private static final int SEGMENT_GAP = 2;

    // Fragments and computed segments
    private volatile List<ContextFragment> fragments = List.of();
    private volatile List<Segment> segments = List.of();
    private final ConcurrentHashMap<String, Integer> tokenCache = new ConcurrentHashMap<>();
    private volatile Set<ContextFragment> hoveredFragments = Set.of();
    private volatile boolean readOnly = false;

    // Tooltip for unfilled part (model/max/cost)
    @Nullable
    private volatile String unfilledTooltipHtml = null;

    public TokenUsageBar(Chrome chrome) {
        setOpaque(false);
        setMinimumSize(new Dimension(50, 24));
        setPreferredSize(new Dimension(75, 24));
        setMaximumSize(new Dimension(125, 24));
        // Seed to enable tooltips; actual content comes from getToolTipText(MouseEvent)
        setToolTipText("Shows Workspace token usage.");

        // Track hover per segment and support left-click to trigger action if provided
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!isEnabled() || readOnly) {
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger()) {
                    Segment seg = findSegmentAt(e.getX());
                    if (seg != null && !seg.fragments.isEmpty()) {
                        if (seg.isSummaryGroup) {
                            // Open combined preview for all summaries (mirrors synthetic chip behavior)
                            int totalFiles = (int) seg.fragments.stream()
                                    .flatMap(f -> f.files().stream())
                                    .map(ProjectFile::toString)
                                    .distinct()
                                    .count();
                            String title = totalFiles > 0 ? "Summaries (" + totalFiles + ")" : "Summaries";

                            StringBuilder combinedText = new StringBuilder();
                            for (var f : seg.fragments) {
                                try {
                                    combinedText.append(f.text()).append("\n\n");
                                } catch (Exception ex) {
                                    logger.debug("Failed reading summary text for preview", ex);
                                }
                            }
                            var previewPanel = new PreviewTextPanel(
                                    chrome.getContextManager(),
                                    null,
                                    combinedText.toString(),
                                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                                    chrome.getTheme(),
                                    null);
                            chrome.showPreviewFrame(chrome.getContextManager(), title, previewPanel);
                        } else if (seg.fragments.size() == 1) {
                            // Single fragment: open its preview
                            chrome.openFragmentPreview(seg.fragments.iterator().next());
                        } else {
                            // Grouped segment (e.g., "Other"): no direct preview action
                            Runnable r = onClick;
                            if (r != null) {
                                try {
                                    r.run();
                                } catch (Exception ex) {
                                    logger.debug("TokenUsageBar onClick handler threw", ex);
                                }
                            }
                        }
                    } else {
                        Runnable r = onClick;
                        if (r != null) {
                            try {
                                r.run();
                            } catch (Exception ex) {
                                logger.debug("TokenUsageBar onClick handler threw", ex);
                            }
                        }
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                Segment prev = hoveredSegment;
                hoveredSegment = null;
                if (prev != null && onHoverFragments != null && isEnabled() && !readOnly) {
                    try {
                        onHoverFragments.accept(prev.getFragments(), false);
                    } catch (Exception ex) {
                        logger.trace("onHoverFragments exit callback threw", ex);
                    }
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (!isEnabled() || readOnly) {
                    return;
                }
                Segment seg = findSegmentAt(e.getX());
                if (!Objects.equals(seg, hoveredSegment)) {
                    Segment prev = hoveredSegment;
                    hoveredSegment = seg;
                    if (prev != null && onHoverFragments != null) {
                        try {
                            onHoverFragments.accept(prev.getFragments(), false);
                        } catch (Exception ex) {
                            logger.trace("onHoverFragments exit callback threw", ex);
                        }
                    }
                    if (seg != null && onHoverFragments != null) {
                        try {
                            onHoverFragments.accept(seg.getFragments(), true);
                        } catch (Exception ex) {
                            logger.trace("onHoverFragments enter callback threw", ex);
                        }
                    }
                }
            }
        };
        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);
    }

    /**
     * Fallback setter used when fragment breakdown isn't available.
     */
    public void setTokens(int current, int max) {
        this.fallbackCurrentTokens = Math.max(0, current);
        this.maxTokens = Math.max(1, max); // Ensure max is at least 1
        repaint();
    }

    /**
     * Deprecated in favor of setUnfilledTooltip; kept for compatibility with prior callers.
     */
    public void setTooltip(String text) {
        setUnfilledTooltip(text);
    }

    /**
     * Explicitly set the tooltip used for the unfilled portion of the bar (model/max/cost).
     */
    public void setUnfilledTooltip(@Nullable String text) {
        this.unfilledTooltipHtml = text;
        repaint();
    }

    public void setOnClick(@Nullable Runnable onClick) {
        this.onClick = onClick;
        setCursor(onClick != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
        repaint();
    }

    public void setOnHoverFragments(@Nullable BiConsumer<Collection<ContextFragment>, Boolean> cb) {
        this.onHoverFragments = cb;
    }

    public void applyGlobalStyling(Set<ContextFragment> targets) {
        this.hoveredFragments = targets;
        repaint();
    }

    // Overload to support existing callers that pass a Collection
    public void applyGlobalStyling(Collection<ContextFragment> targets) {
        applyGlobalStyling(Set.copyOf(targets));
    }

    /**
     * Highlight the segment corresponding to the given fragment.
     * If the fragment belongs to a grouped segment, that segment is highlighted.
     */
    public void highlightForFragment(ContextFragment fragment, boolean entered) {
        if (entered) {
            // Find the segment containing this fragment
            for (var seg : segments) {
                if (seg.fragments.contains(fragment)) {
                    applyGlobalStyling(seg.fragments);
                    return;
                }
            }
        } else {
            applyGlobalStyling(Set.of());
        }
    }

    public void setMaxTokens(int max) {
        this.maxTokens = Math.max(1, max);
        repaint();
    }

    /**
     * Provide the current fragments so the bar can paint per-fragment segments and compute tooltips.
     */
    public void setFragments(List<ContextFragment> fragments) {
        this.fragments = List.copyOf(fragments);
        // Invalidate token cache entries for removed ids to keep memory bounded
        var validIds = this.fragments.stream().map(ContextFragment::id).collect(Collectors.toSet());
        tokenCache.keySet().retainAll(validIds);
        repaint();
    }

    /**
     * Update the bar with fragments from the given Context.
     * - Schedules UI update on the EDT.
     * - Pre-warms token counts off-EDT to avoid doing heavy work during paint.
     * - Repaints on completion so segment widths reflect computed tokens.
     */
    public void setFragmentsForContext(Context context) {
        List<ContextFragment> frags = context.getAllFragmentsInDisplayOrder();

        // Update UI on EDT
        SwingUtilities.invokeLater(() -> setFragments(frags));

        // Precompute token counts off-EDT to avoid jank during paint and tooltips
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                for (var f : frags) {
                    try {
                        if (f.isText() || f.getType().isOutput()) {
                            // This will compute and cache the token count for the fragment
                            tokensForFragment(f);
                        }
                    } catch (Exception ignore) {
                        // Best-effort pre-warm; failures are non-fatal and will be handled lazily
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                SwingUtilities.invokeLater(TokenUsageBar.this::repaint);
            }
        }.execute();
    }

    /**
     * Enable or disable interactive behavior and visuals for read-only mode. Runs on the EDT.
     */
    public void setReadOnly(boolean readOnly) {
        SwingUtilities.invokeLater(() -> {
            this.readOnly = readOnly;
            setEnabled(!readOnly);
            repaint();
        });
    }

    public void setWarningLevel(WarningLevel level, @Nullable Service.ModelConfig config) {
        this.warningLevel = level;
        this.modelConfig = config;
        repaint();
    }

    @Nullable
    public Service.ModelConfig getModelConfig() {
        return modelConfig;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (warningLevel != WarningLevel.NONE && modelConfig != null) {
            String modelName = modelConfig.name();
            if (modelName.contains("-nothink")) {
                modelName = modelName.replace("-nothink", "");
            }
            modelName = StringEscapeUtils.escapeHtml4(modelName);

            int usedTokens = computeUsedTokens();
            int successRate = ModelBenchmarkData.getSuccessRate(modelConfig, usedTokens);

            String reason = warningLevel == WarningLevel.YELLOW
                    ? "a lower success rate (&lt;50%)"
                    : "a very low success rate (&lt;30%)";

            // Indicate if we're extrapolating beyond tested ranges
            String extrapolationNote = "";
            if (usedTokens > 131071) {
                extrapolationNote =
                        "<br/><br/><i>Note: This success rate is extrapolated beyond the maximum tested range (131K tokens).</i>";
            } else if (usedTokens < 4096) {
                extrapolationNote =
                        "<br/><br/><i>Note: This success rate is extrapolated from the smallest tested range (&lt;4K tokens).</i>";
            }

            return String.format(
                    "<html><body style='width: 300px'>"
                            + "<b>Warning: Potential performance issue</b><br/><br/>"
                            + "The model <b>%s</b> might perform poorly at this token count due to %s.<br/><br/>"
                            + "Observed success rate from benchmarks: <b>%d%%</b>"
                            + "%s"
                            + "</body></html>",
                    modelName, reason, successRate, extrapolationNote);
        }

        try {
            int width = getWidth();
            // Ensure segments correspond to current size
            var segs = computeSegments(width);
            int x = Math.max(0, Math.min(event.getX(), width));
            for (var s : segs) {
                if (x >= s.startX && x < s.startX + s.widthPx) {
                    return s.tooltipHtml;
                }
            }
            // Any position not on a segment (including gaps and trailing unfilled) uses the unfilled tooltip
            if (unfilledTooltipHtml != null) {
                return unfilledTooltipHtml;
            }
        } catch (Exception e) {
            logger.trace("Failed to compute tooltip for token bar position", e);
        }
        return super.getToolTipText(event);
    }

    @Override
    public Point getToolTipLocation(MouseEvent event) {
        try {
            String text = getToolTipText(event);
            if (text == null || text.isEmpty()) {
                return null; // default behavior if no tooltip
            }

            JToolTip tip = createToolTip();
            tip.setTipText(text);
            Dimension sz = tip.getPreferredSize();

            int compW = getWidth();
            int compH = getHeight();

            // Prefer centering over the hovered segment; otherwise around the mouse x
            int anchorX;
            Segment seg = findSegmentAt(event.getX());
            if (seg != null) {
                anchorX = seg.startX + Math.max(0, seg.widthPx) / 2;
            } else {
                anchorX = Math.max(0, Math.min(event.getX(), compW));
            }

            int x = anchorX - sz.width / 2;
            x = Math.max(0, Math.min(x, Math.max(0, compW - sz.width)));

            // Place above by default with a small margin; fallback below if not enough room
            int yAbove = -sz.height - 6;

            Point aboveScreen = new Point(x, yAbove);
            SwingUtilities.convertPointToScreen(aboveScreen, this);

            Rectangle screenBounds;
            GraphicsConfiguration gc = getGraphicsConfiguration();
            if (gc != null) {
                screenBounds = gc.getBounds();
            } else {
                Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
                screenBounds = new Rectangle(0, 0, scr.width, scr.height);
            }

            boolean fitsAbove = aboveScreen.y >= screenBounds.y;

            int yBelow = compH + 6;
            Point belowScreen = new Point(x, yBelow);
            SwingUtilities.convertPointToScreen(belowScreen, this);
            boolean fitsBelow = (belowScreen.y + sz.height) <= (screenBounds.y + screenBounds.height);

            int y = (fitsAbove || !fitsBelow) ? yAbove : yBelow;

            // Clamp horizontally to on-screen bounds as well
            Point finalScreen = new Point(x, y);
            SwingUtilities.convertPointToScreen(finalScreen, this);
            int minX = screenBounds.x;
            int maxX = screenBounds.x + screenBounds.width - sz.width;
            if (finalScreen.x < minX) {
                x += (minX - finalScreen.x);
            } else if (finalScreen.x > maxX) {
                x -= (finalScreen.x - maxX);
            }

            return new Point(x, y);
        } catch (Exception ex) {
            logger.trace("Failed to compute tooltip location for TokenUsageBar", ex);
            return null; // default placement
        }
    }

    @Nullable
    private Segment findSegmentAt(int x) {
        int width = getWidth();
        var segs = computeSegments(width);
        int clamped = Math.max(0, Math.min(x, width));
        for (var s : segs) {
            if (clamped >= s.startX && clamped < s.startX + s.widthPx) {
                return s;
            }
        }
        return null;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int width = getWidth();
            int height = getHeight();

            // Draw background track
            g2d.setColor(getTrackColor());
            g2d.fillRoundRect(0, 0, width, height, ARC, ARC);

            // Draw per-fragment segments with rounded borders similar to chips
            var segs = computeSegments(width);
            for (var s : segs) {
                if (s.widthPx <= 0) continue;

                boolean isHovered = !hoveredFragments.isEmpty() && !Collections.disjoint(s.fragments, hoveredFragments);
                boolean isDimmed = !hoveredFragments.isEmpty() && !isHovered;
                Composite originalComposite = g2d.getComposite();
                if (isDimmed) {
                    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                }

                try {
                    // Fill
                    g2d.setColor(s.bg);
                    g2d.fillRoundRect(s.startX, 0, s.widthPx, height, ARC, ARC);

                    // Border: summary group uses summary border; else derive from fragment kind
                    Color borderColor;
                    if (s.isSummaryGroup) {
                        borderColor = ThemeColors.getColor(isDarkTheme(), ThemeColors.CHIP_SUMMARY_BORDER);
                    } else {
                        ContextFragment fragForBorder =
                                s.fragments.size() == 1 ? s.fragments.iterator().next() : null;
                        borderColor = getSegmentBorderColor(fragForBorder);
                    }
                    g2d.setColor(borderColor);
                    int bw = Math.max(1, s.widthPx - 1);
                    int bh = Math.max(1, height - 1);
                    g2d.drawRoundRect(s.startX, 0, bw, bh, ARC, ARC);
                } finally {
                    if (isDimmed) {
                        g2d.setComposite(originalComposite);
                    }
                }
            }

            // Draw text on top: current tokens in white, aligned to the fill's east edge
            g2d.setFont(getFont().deriveFont(Font.BOLD, 11f));

            int fillPixelEnd =
                    segs.isEmpty() ? 0 : segs.get(segs.size() - 1).startX + segs.get(segs.size() - 1).widthPx;
            int usedTokens = computeUsedTokens();
            drawText(g2d, width, height, fillPixelEnd, usedTokens);

            // Dim overlay when disabled/read-only to provide a visual cue
            if (!isEnabled() || readOnly) {
                Composite orig = g2d.getComposite();
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
                g2d.setColor(new Color(0, 0, 0, 120));
                g2d.fillRoundRect(0, 0, width, height, ARC, ARC);
                g2d.setComposite(orig);
            }
        } finally {
            g2d.dispose();
        }
    }

    private void drawText(Graphics2D g2d, int width, int height, int fillWidth, int usedTokens) {
        boolean hasContext = usedTokens > 0 && maxTokens > 0;

        g2d.setFont(getFont().deriveFont(Font.BOLD, 11f));
        FontMetrics fm = g2d.getFontMetrics();
        int textHeight = fm.getAscent();
        int textY = (height - textHeight) / 2 + fm.getAscent();
        int padding = 6;

        if (!hasContext) {
            String msg =
                    "No context yet - use Lutz Mode to discover and add relevant files automatically, or click the paperclip icon or drag-and-drop files from the Project Files panel.";
            int maxTextWidth = Math.max(0, width - 2 * padding);
            String shown = elide(msg, fm, maxTextWidth);

            Color track = getTrackColor();
            Color textColor = readableTextForBackground(track);

            g2d.setColor(textColor);
            int textWidth = fm.stringWidth(shown);
            int x = (width - textWidth) / 2;
            x = Math.max(0, x);
            g2d.drawString(shown, x, textY);

            var ac = getAccessibleContext();
            if (ac != null) {
                ac.setAccessibleDescription(msg);
            }
            return;
        }

        String currentText = formatTokens(usedTokens);
        int textWidth = fm.stringWidth(currentText);

        int x;
        // Prefer placing text after the fill, but constrain to bar bounds
        int preferredX = fillWidth + padding;
        if (preferredX + textWidth <= width) {
            // Fits comfortably after the fill
            x = preferredX;
        } else if (preferredX + textWidth - padding <= width) {
            // Fits if we reduce padding on the right
            x = preferredX;
        } else {
            // Shift left to keep within bounds
            x = Math.max(0, width - textWidth - padding);
        }

        g2d.setColor(Color.WHITE);
        g2d.drawString(currentText, x, textY);

        var ac = getAccessibleContext();
        if (ac != null) {
            ac.setAccessibleDescription(String.format("Tokens: %s of %d", currentText, maxTokens));
        }
    }

    /**
     * Elide a string with "..." using Apache Commons Text WordUtils.abbreviate, sized to fit within maxWidth pixels.
     */
    private static String elide(String text, FontMetrics fm, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (text.isEmpty()) return "";
        if (fm.stringWidth(text) <= maxWidth) return text;

        final String ellipsis = "...";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        int available = maxWidth - ellipsisWidth;
        if (available <= 0) return ellipsis;

        final String sample = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        int avgCharWidth = Math.max(1, fm.stringWidth(sample) / sample.length());
        int maxChars = Math.max(1, available / avgCharWidth);
        return WordUtils.abbreviate(text, maxChars, maxChars, ellipsis);
    }

    /** Pick a readable text color (white or dark) against the given background color. */
    private static Color readableTextForBackground(Color background) {
        double r = background.getRed() / 255.0;
        double g = background.getGreen() / 255.0;
        double b = background.getBlue() / 255.0;
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return lum < 0.5 ? Color.WHITE : new Color(0x1E1E1E);
    }

    private String formatTokens(int tokens) {
        if (tokens < 1000) {
            return String.valueOf(tokens);
        }
        if (tokens < 1_000_000) {
            return String.format(Locale.US, "%.1fK", tokens / 1000.0);
        }
        return String.format(Locale.US, "%.1fM", tokens / 1_000_000.0);
    }

    private List<Segment> computeSegments(int width) {
        // If no fragments are provided, fall back to single fill behavior using fallbackCurrentTokens
        if (fragments.isEmpty()) {
            int fillWidth =
                    (int) Math.floor(width * Math.min(1.0, (double) fallbackCurrentTokens / Math.max(1, maxTokens)));
            boolean dark = isDarkTheme();
            Color fillColor = getOkColor(dark);
            var s = new Segment(0, Math.max(0, fillWidth), fillColor, "", Set.of(), false);
            this.segments = List.of(s);
            return this.segments;
        }

        // Compute token totals only for text-like and output fragments
        var usable = fragments.stream()
                .filter(f -> f.isText() || f.getType().isOutput())
                .toList();

        if (usable.isEmpty()) {
            this.segments = List.of();
            return this.segments;
        }

        // Separate summaries (SKELETON) to form a single "Summaries" segment
        var summaries = usable.stream()
                .filter(f -> f.getType() == ContextFragment.FragmentType.SKELETON)
                .toList();
        var nonSummaries = usable.stream()
                .filter(f -> f.getType() != ContextFragment.FragmentType.SKELETON)
                .toList();

        int tokensSummaries =
                summaries.stream().mapToInt(this::tokensForFragment).sum();
        int tokensNonSummaries =
                nonSummaries.stream().mapToInt(this::tokensForFragment).sum();
        int totalTokens = tokensSummaries + tokensNonSummaries;

        if (totalTokens <= 0) {
            this.segments = List.of();
            return this.segments;
        }

        // Compute filled width based on total tokens vs maxTokens
        int fillWidth = (int) Math.floor(width * Math.min(1.0, (double) totalTokens / Math.max(1, maxTokens)));
        if (fillWidth <= 0) {
            this.segments = List.of();
            return this.segments;
        }

        // Mark small fragments among non-summaries (but never group HISTORY fragments)
        double[] rawWidths = new double[nonSummaries.size()];
        List<ContextFragment> small = new ArrayList<>();
        for (int i = 0; i < nonSummaries.size(); i++) {
            int t = tokensForFragment(nonSummaries.get(i));
            rawWidths[i] = (t * 1.0 / totalTokens) * fillWidth;
            if (rawWidths[i] < MIN_SEGMENT_PX
                    && nonSummaries.get(i).getType() != ContextFragment.FragmentType.HISTORY) {
                small.add(nonSummaries.get(i));
            }
        }
        int smallCount = small.size();
        int otherTokens = small.stream().mapToInt(this::tokensForFragment).sum();

        // Build allocation items: non-small fragments + optional "Other" + optional "Summaries" group
        record AllocItem(
                @Nullable ContextFragment frag,
                boolean isOther,
                boolean isSummaryGroup,
                int tokens,
                double rawWidth,
                int minWidth) {}

        int nBaseItems = (smallCount > 1) ? (nonSummaries.size() - smallCount + 1) : nonSummaries.size();
        int nItems = nBaseItems + (summaries.isEmpty() ? 0 : 1);
        int totalGaps = Math.max(0, nItems - 1);
        int effectiveFill = Math.max(0, fillWidth - totalGaps * SEGMENT_GAP);

        List<AllocItem> items = new ArrayList<>();
        if (smallCount == 1) {
            // Exactly one "small" fragment: place it normally with a min width and normal label
            ContextFragment onlySmall = small.get(0);
            for (var f : nonSummaries) {
                int t = tokensForFragment(f);
                double rw = (t * 1.0 / totalTokens) * effectiveFill;
                int min = (f == onlySmall) ? Math.min(MIN_SEGMENT_PX, effectiveFill) : 0;
                items.add(new AllocItem(f, false, false, t, rw, min));
            }
        } else {
            // Add all non-small individually
            for (var f : nonSummaries) {
                if (small.contains(f)) continue;
                int t = tokensForFragment(f);
                double rw = (t * 1.0 / totalTokens) * effectiveFill;
                items.add(new AllocItem(f, false, false, t, rw, 0));
            }
            // Group remaining as "Other" if there are multiple small ones
            if (smallCount > 1) {
                double rw = (otherTokens * 1.0 / totalTokens) * effectiveFill;
                int min = Math.min(MIN_SEGMENT_PX, effectiveFill);
                items.add(new AllocItem(null, true, false, otherTokens, rw, min));
            }
        }

        // Add a single "Summaries" item if present
        if (!summaries.isEmpty()) {
            double rw = (tokensSummaries * 1.0 / totalTokens) * effectiveFill;
            int min = Math.min(MIN_SEGMENT_PX, effectiveFill);
            items.add(new AllocItem(null, false, true, tokensSummaries, rw, min));
        }

        if (items.isEmpty()) {
            this.segments = List.of();
            return this.segments;
        }

        // Largest-remainder with min width clamping
        class Working {
            final AllocItem item;
            int width;
            final double remainder;

            Working(AllocItem item, int width, double remainder) {
                this.item = item;
                this.width = width;
                this.remainder = remainder;
            }
        }
        List<Working> work = new ArrayList<>(items.size());
        int sum = 0;
        for (var it : items) {
            int floor = (int) Math.floor(it.rawWidth);
            int widthAlloc = Math.max(floor, it.minWidth);
            Working w = new Working(it, widthAlloc, it.rawWidth - floor);
            work.add(w);
            sum += widthAlloc;
        }

        int deficit = effectiveFill - sum;
        if (deficit > 0) {
            work.stream()
                    .sorted(Comparator.comparingDouble((Working w) -> w.remainder)
                            .reversed())
                    .limit(deficit)
                    .forEach(w -> w.width++);
        } else if (deficit < 0) {
            int need = -deficit;
            Predicate<Working> canShrink = w -> w.width > w.item.minWidth;
            while (need > 0) {
                boolean removed = false;
                for (var w : work.stream()
                        .sorted(Comparator.comparingDouble((Working x) -> x.remainder))
                        .toList()) {
                    if (need == 0) break;
                    if (canShrink.test(w)) {
                        w.width--;
                        need--;
                        removed = true;
                    }
                }
                if (!removed) break;
            }
            if (need > 0) {
                for (var w : work.stream()
                        .sorted(Comparator.comparingInt((Working x) -> x.width).reversed())
                        .toList()) {
                    if (need == 0) break;
                    if (w.width > 0) {
                        int d = Math.min(need, w.width);
                        w.width -= d;
                        need -= d;
                    }
                }
            }
        }

        // Convert to segments with tooltips and colors, inserting 1px gaps
        boolean isDark = isDarkTheme();
        List<Segment> out = new ArrayList<>();
        int x = 0;
        for (var w : work) {
            if (w.width <= 0) continue;

            if (w.item.isSummaryGroup) {
                Color bg = FragmentColorUtils.getBackgroundColor(FragmentColorUtils.FragmentKind.SUMMARY, isDark);
                String tip = buildAggregateSummaryTooltip(summaries);
                Set<ContextFragment> segmentFrags = Set.copyOf(summaries);
                out.add(new Segment(x, w.width, bg, tip, segmentFrags, true));
            } else if (w.item.isOther) {
                Color bg = FragmentColorUtils.getBackgroundColor(FragmentColorUtils.FragmentKind.OTHER, isDark);
                String tip = buildOtherTooltip(small);
                Set<ContextFragment> segmentFrags = Set.copyOf(small);
                out.add(new Segment(x, w.width, bg, tip, segmentFrags, false));
            } else {
                ContextFragment frag = Objects.requireNonNull(w.item.frag);
                FragmentColorUtils.FragmentKind kind = FragmentColorUtils.classify(frag);
                Color bg = FragmentColorUtils.getBackgroundColor(kind, isDark);
                String tip = buildFragmentTooltip(frag);
                Set<ContextFragment> segmentFrags = Set.of(frag);
                out.add(new Segment(x, w.width, bg, tip, segmentFrags, false));
            }

            x += w.width + SEGMENT_GAP;
        }

        this.segments = List.copyOf(out);
        return this.segments;
    }

    private int computeUsedTokens() {
        if (fragments.isEmpty()) {
            return fallbackCurrentTokens;
        }
        return fragments.stream()
                .filter(f -> f.isText() || f.getType().isOutput())
                .mapToInt(this::tokensForFragment)
                .sum();
    }

    private Color getSegmentBorderColor(@Nullable ContextFragment frag) {
        boolean isDark = isDarkTheme();
        if (frag == null) {
            // Grouped "Other" bucket
            return ThemeColors.getColor(isDark, ThemeColors.CHIP_OTHER_BORDER);
        }
        try {
            if (frag.getType().isEditable()) {
                return ThemeColors.getColor(isDark, ThemeColors.CHIP_EDIT_BORDER);
            }
            if (frag.getType() == ContextFragment.FragmentType.SKELETON) {
                return ThemeColors.getColor(isDark, ThemeColors.CHIP_SUMMARY_BORDER);
            }
            if (frag.getType() == ContextFragment.FragmentType.HISTORY) {
                return ThemeColors.getColor(isDark, ThemeColors.CHIP_HISTORY_BORDER);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return ThemeColors.getColor(isDark, ThemeColors.CHIP_OTHER_BORDER);
    }

    private int tokensForFragment(ContextFragment f) {
        try {
            return tokenCache.computeIfAbsent(f.id(), id -> {
                if (f.isText() || f.getType().isOutput()) {
                    try {
                        return Messages.getApproximateTokens(f.text());
                    } catch (Exception e) {
                        logger.trace("Failed to compute token count for fragment", e);
                        return 0;
                    }
                }
                return 0;
            });
        } catch (Exception e) {
            logger.trace("Failed to cache token count for fragment", e);
            return 0;
        }
    }

    private static class Segment {
        final int startX;
        final int widthPx;
        final Color bg;
        final String tooltipHtml;
        final Set<ContextFragment> fragments;
        final boolean isSummaryGroup;

        Segment(
                int startX,
                int widthPx,
                Color bg,
                String tooltipHtml,
                Set<ContextFragment> fragments,
                boolean isSummaryGroup) {
            this.startX = startX;
            this.widthPx = widthPx;
            this.bg = bg;
            this.tooltipHtml = tooltipHtml;
            this.fragments = fragments;
            this.isSummaryGroup = isSummaryGroup;
        }

        Set<ContextFragment> getFragments() {
            return fragments;
        }
    }

    // Tooltip helpers (mirroring WorkspaceItemsChipPanel behavior)

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
                String text = fragment.text();
                int loc = text.split("\\r?\\n", -1).length;
                int tokens = Messages.getApproximateTokens(text);
                return String.format("<div>%s LOC \u2022 ~%s tokens</div><br/>", formatCount(loc), formatCount(tokens));
            }
        } catch (Exception e) {
            logger.trace("Failed to compute metrics for tooltip", e);
        }
        return "";
    }

    private static String buildSummaryTooltip(ContextFragment fragment) {
        var files = fragment.files().stream()
                .map(ProjectFile::toString)
                .distinct()
                .sorted()
                .toList();

        StringBuilder body = new StringBuilder();

        String metrics = buildMetricsHtml(fragment);
        if (!metrics.isEmpty()) {
            body.append(metrics);
        }

        body.append("<div><b>Summaries</b></div>");
        body.append("<hr style='border:0;border-top:1px solid #ccc;margin:4px 0 6px 0;'/>");

        if (files.isEmpty()) {
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

    private static String buildFragmentTooltip(ContextFragment fragment) {
        if (fragment.getType() == ContextFragment.FragmentType.SKELETON) {
            return buildSummaryTooltip(fragment);
        }
        return buildDefaultTooltip(fragment);
    }

    private String buildOtherTooltip(List<ContextFragment> smallFragments) {
        int totalTokens = 0;
        int totalLoc = 0;
        for (var f : smallFragments) {
            if (f.isText() || f.getType().isOutput()) {
                try {
                    String text = f.text();
                    totalLoc += text.split("\\r?\\n", -1).length;
                    totalTokens += Messages.getApproximateTokens(text);
                } catch (Exception e) {
                    logger.trace("Failed to compute metrics for tooltip", e);
                }
            }
        }
        StringBuilder body = new StringBuilder();
        body.append(String.format(
                "<div>%s LOC \u2022 ~%s tokens</div><br/>", formatCount(totalLoc), formatCount(totalTokens)));
        body.append("<div><b>Other</b></div>");
        body.append("<hr style='border:0;border-top:1px solid #ccc;margin:4px 0 6px 0;'/>");

        if (!smallFragments.isEmpty()) {
            body.append("<ul style='margin:0;padding-left:16px'>");
            // List up to 12 items by shortDescription
            int listed = 0;
            for (var f : smallFragments) {
                if (listed >= 12) break;
                String name = f.shortDescription();
                body.append("<li>").append(StringEscapeUtils.escapeHtml4(name)).append("</li>");
                listed++;
            }
            if (smallFragments.size() > listed) {
                body.append("<li>... and ")
                        .append(smallFragments.size() - listed)
                        .append(" more</li>");
            }
            body.append("</ul>");
        }

        return wrapTooltipHtml(body.toString(), 420);
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
            logger.trace("Failed to compute aggregate summary metrics", e);
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

    private Color getTrackColor() {
        Color panel = UIManager.getColor("Panel.background");
        boolean dark = isDarkTheme();
        if (panel != null) {
            return dark ? lighten(panel, 0.08f) : darken(panel, 0.18f);
        }
        Color pb = UIManager.getColor("ProgressBar.background");
        if (pb != null) return pb;
        return dark ? new Color(0x2B2B2B) : new Color(0xC0C4C8);
    }

    private boolean isDarkTheme() {
        return UIManager.getBoolean("laf.dark");
    }

    private static Color lighten(Color base, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.min(255, Math.round(base.getRed() + (255 - base.getRed()) * amount));
        int g = Math.min(255, Math.round(base.getGreen() + (255 - base.getGreen()) * amount));
        int b = Math.min(255, Math.round(base.getBlue() + (255 - base.getBlue()) * amount));
        return new Color(r, g, b);
    }

    private static Color darken(Color base, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.max(0, Math.round(base.getRed() * (1f - amount)));
        int g = Math.max(0, Math.round(base.getGreen() * (1f - amount)));
        int b = Math.max(0, Math.round(base.getBlue() * (1f - amount)));
        return new Color(r, g, b);
    }

    private Color getOkColor(boolean dark) {
        return dark ? new Color(0x2EA043) : new Color(0x1F883D);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        repaint();
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        applyTheme(guiTheme, false);
    }
}
