package io.github.jbellis.brokk.gui.components;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.FragmentColorUtils;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.swing.*;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.text.WordUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class TokenUsageBar extends JComponent implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(TokenUsageBar.class);

    // Fallback counters if fragments aren't provided
    private int fallbackCurrentTokens = 0;

    private int maxTokens = 1; // Avoid division by zero

    // Hover state for highlight
    private boolean hovered = false;

    // Rounded rectangle arc (diameter for corner rounding). Radius is arc/2.
    private static final int ARC = 8;
    private static final int MIN_SEGMENT_PX = 8; // 2x radius
    private static final int SEGMENT_GAP = 2;

    // Fragments and computed segments
    private volatile List<ContextFragment> fragments = List.of();
    private volatile List<Segment> segments = List.of();
    private final ConcurrentHashMap<String, Integer> tokenCache = new ConcurrentHashMap<>();

    // Tooltip for unfilled part (model/max/cost)
    @Nullable
    private volatile String unfilledTooltipHtml = null;

    public TokenUsageBar() {
        setOpaque(false);
        setMinimumSize(new Dimension(50, 24));
        setPreferredSize(new Dimension(75, 24));
        setMaximumSize(new Dimension(125, 24));
        // Seed to enable tooltips; actual content comes from getToolTipText(MouseEvent)
        setToolTipText("Shows Workspace token usage.");

        // Only track hover; no click behavior per requirements
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                repaint();
            }
        });
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

    /**
     * Disable click behavior entirely; keep API but ignore the runnable to respect new UX.
     */
    public void setOnClick(@Nullable Runnable onClick) {
        setCursor(Cursor.getDefaultCursor());
        // intentionally ignored (no click behavior in the bar)
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
        var validIds = this.fragments.stream().map(ContextFragment::id).collect(java.util.stream.Collectors.toSet());
        tokenCache.keySet().retainAll(validIds);
        repaint();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
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

            // Draw per-fragment segments
            var segs = computeSegments(width);
            for (var s : segs) {
                if (s.widthPx <= 0) continue;
                g2d.setColor(s.bg);
                g2d.fillRoundRect(s.startX, 0, s.widthPx, height, ARC, ARC);
                // Optional border (only outer border looks good; inner borders between segments can look jagged)
                // We skip borders to keep it clean.
            }

            // Hover affordance (subtle overlay + outline) regardless of clickability
            if (hovered && isEnabled()) {
                g2d.setComposite(AlphaComposite.SrcOver.derive(0.08f));
                g2d.setColor(getAccentColor());
                g2d.fillRoundRect(0, 0, width, height, ARC, ARC);

                g2d.setComposite(AlphaComposite.SrcOver);
                g2d.setColor(getAccentColor());
                g2d.setStroke(new BasicStroke(1.0f));
                g2d.drawRoundRect(0, 0, width - 1, height - 1, ARC, ARC);
            }

            // Draw text on top: current tokens in white, aligned to the fill's east edge
            g2d.setFont(getFont().deriveFont(Font.BOLD, 11f));

            int fillPixelEnd =
                    segs.isEmpty() ? 0 : segs.get(segs.size() - 1).startX + segs.get(segs.size() - 1).widthPx;
            int usedTokens = computeUsedTokens();
            drawText(g2d, width, height, fillPixelEnd, usedTokens);
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
                    "No context yet - add some by clicking the paperclip icon or drag-and-drop files from the Project Files panel.";
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
            var s = new Segment(0, Math.max(0, fillWidth), fillColor, "");
            this.segments = List.of(s);
            return this.segments;
        }

        // Compute token totals only for text-like and output fragments
        var usable = fragments.stream()
                .filter(f -> f.isText() || f.getType().isOutput())
                .toList();

        int totalTokens = usable.stream().mapToInt(this::tokensForFragment).sum();
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

        // Pass 1: mark small fragments
        double[] rawWidths = new double[usable.size()];
        List<ContextFragment> small = new ArrayList<>();
        for (int i = 0; i < usable.size(); i++) {
            int t = tokensForFragment(usable.get(i));
            rawWidths[i] = (t * 1.0 / totalTokens) * fillWidth;
            if (rawWidths[i] < MIN_SEGMENT_PX) {
                small.add(usable.get(i));
            }
        }
        int smallCount = small.size();
        int otherTokens = small.stream().mapToInt(this::tokensForFragment).sum();

        // Build allocation items: large fragments + optional "Other"
        record AllocItem(@Nullable ContextFragment frag, boolean isOther, int tokens, double rawWidth, int minWidth) {}

        int nItems = (smallCount > 1) ? (usable.size() - smallCount + 1) : usable.size();
        int totalGaps = Math.max(0, nItems - 1);
        int effectiveFill = Math.max(0, fillWidth - totalGaps * SEGMENT_GAP);

        List<AllocItem> items = new ArrayList<>();
        if (smallCount == 1) {
            // Exactly one "small" fragment: place it normally with a min width and normal label
            ContextFragment onlySmall = small.get(0);
            for (var f : usable) {
                int t = tokensForFragment(f);
                double rw = (t * 1.0 / totalTokens) * effectiveFill;
                int min = (f == onlySmall) ? Math.min(MIN_SEGMENT_PX, effectiveFill) : 0;
                items.add(new AllocItem(f, false, t, rw, min));
            }
        } else {
            // Add all non-small individually
            for (int i = 0; i < usable.size(); i++) {
                var f = usable.get(i);
                if (small.contains(f)) continue;
                int t = tokensForFragment(f);
                double rw = (t * 1.0 / totalTokens) * effectiveFill;
                items.add(new AllocItem(f, false, t, rw, 0));
            }
            // Group remaining as "Other" if there are multiple small ones
            if (smallCount > 1) {
                double rw = (otherTokens * 1.0 / totalTokens) * effectiveFill;
                int min = Math.min(MIN_SEGMENT_PX, effectiveFill);
                items.add(new AllocItem(null, true, otherTokens, rw, min));
            }
        }

        // If nothing left after grouping (edge-case), bail
        if (items.isEmpty()) {
            this.segments = List.of();
            return this.segments;
        }

        // Pass 2: largest-remainder with min width clamping
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
            // Distribute extra pixels by largest remainder
            work.stream()
                    .sorted(Comparator.comparingDouble((Working w) -> w.remainder)
                            .reversed())
                    .limit(deficit)
                    .forEach(w -> w.width++);
        } else if (deficit < 0) {
            // Reclaim pixels from items above their minWidth, preferring smallest remainder
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
            ContextFragment frag = w.item.frag;
            boolean isOther = w.item.isOther;
            FragmentColorUtils.FragmentKind kind = isOther
                    ? FragmentColorUtils.FragmentKind.OTHER
                    : FragmentColorUtils.classify(Objects.requireNonNull(frag));
            Color bg = FragmentColorUtils.getBackgroundColor(kind, isDark);
            String tip = isOther ? buildOtherTooltip(small) : buildFragmentTooltip(Objects.requireNonNull(frag));
            out.add(new Segment(x, w.width, bg, tip));
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

        Segment(int startX, int widthPx, Color bg, String tooltipHtml) {
            this.startX = startX;
            this.widthPx = widthPx;
            this.bg = bg;
            this.tooltipHtml = tooltipHtml;
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

    private Color getAccentColor() {
        Color c = UIManager.getColor("Component.focusColor");
        if (c == null) c = UIManager.getColor("Focus.color");
        if (c == null) c = UIManager.getColor("List.selectionBackground");
        if (c == null) c = ThemeColors.getColor(isDarkTheme(), "mode_answer_accent");
        if (c == null) c = new Color(0x1F6FEB);
        return c;
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
