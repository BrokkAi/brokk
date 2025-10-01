package io.github.jbellis.brokk.gui.terminal;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * Lightweight wrapped text painter used in the renderer (no JTextArea).
 * - Measures wrapped content height for a given available width.
 * - Applies vertical centering by honoring a top padding paint offset.
 * - Keeps rendering fast and stable; avoids layout churn and preserves wrap behavior
 *   consistent with the inline editor (which uses JTextArea only while editing).
 */
public class WrappedTextView extends JComponent {
    private String text = "";
    private int availableWidth = 0;
    private int contentHeight = 0;
    private boolean strikeThrough = false;
    private boolean expanded = false;
    private int maxVisibleLines = 2;
    private int topPadding = 0;

    public void setText(String text) {
        this.text = text;
        // Re-measure on text change
        measure();
        repaint();
    }

    public void setAvailableWidth(int w) {
        if (w < 1) w = 1;
        if (this.availableWidth != w) {
            this.availableWidth = w;
            measure();
            revalidate();
            repaint();
        }
    }

    public void setStrikeThrough(boolean on) {
        if (this.strikeThrough != on) {
            this.strikeThrough = on;
            repaint();
        }
    }

    public void setExpanded(boolean expanded) {
        if (this.expanded != expanded) {
            this.expanded = expanded;
            measure();
            revalidate();
            repaint();
        }
    }

    public void setMaxVisibleLines(int lines) {
        int newVal = Math.max(1, lines);
        if (this.maxVisibleLines != newVal) {
            this.maxVisibleLines = newVal;
            measure();
            revalidate();
            repaint();
        }
    }

    public void setTopPadding(int padTop) {
        this.topPadding = Math.max(0, padTop);
        repaint();
    }

    public int getContentHeight() {
        return contentHeight;
    }

    @Override
    public void setFont(Font font) {
        super.setFont(font);
        measure();
        revalidate();
        repaint();
    }

    private void measure() {
        Font f = getFont();
        if (f == null) {
            contentHeight = 0;
            return;
        }
        FontMetrics fm = getFontMetrics(f);
        int lineHeight = fm.getHeight();
        if (availableWidth <= 0 || text.isEmpty()) {
            contentHeight = lineHeight;
            return;
        }

        var lines = wrapLines(text, fm, availableWidth);
        int visibleLines = expanded ? lines.size() : Math.min(lines.size(), maxVisibleLines);
        contentHeight = Math.max(lineHeight, visibleLines * lineHeight);
    }

    @Override
    public Dimension getPreferredSize() {
        // The parent panel dictates minHeight; this view reports its content height and width.
        return new Dimension(Math.max(1, availableWidth), Math.max(0, topPadding + contentHeight));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (text.isEmpty()) return;

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(getForeground());
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int lineHeight = fm.getHeight();
            int y = topPadding + fm.getAscent();

            var lines = wrapLines(text, fm, availableWidth);
            List<String> renderLines = lines;
            if (!expanded && lines.size() > maxVisibleLines) {
                var tmp = new ArrayList<String>(maxVisibleLines);
                int lastIdx = Math.max(0, maxVisibleLines - 1);
                for (int i = 0; i < lastIdx; i++) {
                    tmp.add(lines.get(i));
                }
                String last = lines.get(lastIdx);
                String lastTruncated = truncateWithEllipsis(last, " ...", fm, availableWidth);
                tmp.add(lastTruncated);
                renderLines = tmp;
            }

            for (var line : renderLines) {
                g2.drawString(line, 0, y);
                if (strikeThrough) {
                    int yStrike = y - Math.round(fm.getAscent() * 0.4f);
                    int w = fm.stringWidth(line);
                    g2.drawLine(0, yStrike, w, yStrike);
                }
                y += lineHeight;
            }
        } finally {
            g2.dispose();
        }
    }

    private List<String> wrapLines(String text, FontMetrics fm, int maxWidth) {
        // Handle explicit newlines as hard breaks first.
        var paragraphs = text.split("\\R", -1);
        var result = new ArrayList<String>(paragraphs.length);
        for (var para : paragraphs) {
            wrapParagraph(para, fm, maxWidth, result);
        }
        if (result.isEmpty()) {
            result.add("");
        }
        return result;
    }

    private void wrapParagraph(String para, FontMetrics fm, int maxWidth, List<String> out) {
        if (para.isEmpty()) {
            out.add("");
            return;
        }
        var breaker = BreakIterator.getLineInstance(java.util.Locale.ROOT);
        breaker.setText(para);
        int start = breaker.first();
        int end = breaker.next();
        StringBuilder current = new StringBuilder();
        while (end != BreakIterator.DONE) {
            String word = para.substring(start, end);
            String candidate = current + word;
            int w = fm.stringWidth(candidate);
            if (w <= maxWidth) {
                current.append(word);
                start = end;
                end = breaker.next();
            } else {
                if (current.length() == 0) {
                    // Single word longer than maxWidth: hard-break by characters
                    end = hardBreakByChars(para, start, fm, maxWidth, out);
                    start = end;
                    current.setLength(0);
                    end = breaker.following(start);
                    if (end == BreakIterator.DONE && start < para.length()) {
                        end = para.length();
                    }
                } else {
                    out.add(current.toString().stripTrailing());
                    current.setLength(0);
                    // keep evaluating the same word on next loop
                }
            }
        }
        if (start < para.length()) {
            current.append(para.substring(start));
        }
        if (current.length() > 0) {
            out.add(current.toString().stripTrailing());
        }
    }

    private int hardBreakByChars(String text, int start, FontMetrics fm, int maxWidth, List<String> out) {
        int i = start;
        StringBuilder sb = new StringBuilder();
        while (i < text.length()) {
            char ch = text.charAt(i);
            int w = fm.stringWidth(sb.toString() + ch);
            if (w > maxWidth) {
                if (sb.length() == 0) {
                    // Always place at least one char to make progress
                    sb.append(ch);
                    i++;
                }
                out.add(sb.toString());
                sb.setLength(0);
                // Continue building next line without incrementing i
            } else {
                sb.append(ch);
                i++;
            }
            // If next char would overflow and we already have content, flush
            if (i < text.length() && fm.stringWidth(sb.toString() + text.charAt(i)) > maxWidth && sb.length() > 0) {
                out.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return i;
    }

    private String truncateWithEllipsis(String line, String suffix, FontMetrics fm, int maxWidth) {
        String sfx = suffix.isEmpty() ? "..." : suffix;
        int sfxW = fm.stringWidth(sfx);
        if (sfxW > maxWidth) {
            return "";
        }
        int lineW = fm.stringWidth(line);
        if (lineW + sfxW <= maxWidth) {
            return line + sfx;
        }
        int low = 0;
        int high = line.length();
        int best = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int w = fm.stringWidth(line.substring(0, mid)) + sfxW;
            if (w <= maxWidth) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (best <= 0) {
            return sfx;
        }
        return line.substring(0, best) + sfx;
    }
}
