package io.github.jbellis.brokk.difftool.ui;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;

public class JMHighlighter implements Highlighter, ThemeAware {

    // Define highlight layers with increasing priority
    public static final int LAYER0 = 1;
    public static final int LAYER1 = 2;
    public static final int LAYER2 = 3;
    public static final int LAYER3 = 4;
    public static final int UPPER_LAYER = LAYER3;

    // Stores highlights mapped by their layers
    private final Map<Integer, List<Highlight>> highlights = new HashMap<>();
    private JTextComponent component; // The associated text component

    /**
     * Installs the highlighter into a JTextComponent.
     */
    public void install(JTextComponent c) {
        component = c;
        removeAllHighlights();
    }

    /**
     * Uninstalls the highlighter from the JTextComponent.
     */
    public void deinstall(JTextComponent c) {
        component = null; // Unbind from the text component
    }

    /**
     * Paints the highlights on the associated text component.
     */
    public void paint(Graphics g) {
        if (component == null) return;

        Rectangle clip = g.getClipBounds();
        int lineHeight = component.getFontMetrics(component.getFont()).getHeight();
        int startOffset = component.viewToModel2D(new Point(clip.x - lineHeight, clip.y));
        int endOffset = component.viewToModel2D(new Point(clip.x, clip.y + clip.height + lineHeight));

        LineNumberBorder lineNumberBorder = (component.getBorder() instanceof LineNumberBorder)
                ? (LineNumberBorder) component.getBorder()
                : null;

        if (lineNumberBorder != null) {
            lineNumberBorder.paintBefore(g);
        }

        Rectangle textBounds = component.getBounds();
        Insets insets = component.getInsets();
        textBounds.x = insets.left;
        textBounds.y = insets.top;
        textBounds.width -= insets.left + insets.right;
        textBounds.height -= insets.top + insets.bottom;

        // Paint highlights in each layer
        for (int layer : Arrays.asList(LAYER0, LAYER1, LAYER2, LAYER3)) {
            List<Highlight> list = highlights.get(layer);
            if (list == null || list.isEmpty()) continue;

            for (Highlight hli : list) {
                if (hli.getStartOffset() > endOffset || hli.getEndOffset() < startOffset) continue;
                hli.getPainter().paint(g, hli.getStartOffset(), hli.getEndOffset(), textBounds, component);
            }
        }

        if (lineNumberBorder != null) {
            lineNumberBorder.paintAfter(g, startOffset, endOffset);
        }
    }

    /**
     * Adds a highlight to the highest priority layer.
     */
    public Object addHighlight(int p0, int p1, HighlightPainter painter) throws BadLocationException {
        return addHighlight(UPPER_LAYER, p0, p1, painter);
    }

    /**
     * Adds a highlight to a specific layer.
     */
    public Object addHighlight(int layer, int p0, int p1, HighlightPainter painter) throws BadLocationException {
        if (component == null) {
            throw new IllegalStateException("Highlighter not installed on any component");
        }
        Document doc = component.getDocument();
        if (doc == null) {
            throw new IllegalStateException("No document available");
        }
        HighlightInfo hli = new HighlightInfo(doc.createPosition(p0), doc.createPosition(p1), painter);

        getLayer(layer).add(hli);
        repaint();
        return hli;
    }

    /**
     * Removes a highlight from the highest priority layer.
     */
    public void removeHighlight(Object highlight) {
        removeHighlight(UPPER_LAYER, highlight);
    }

    /**
     * Removes a highlight from a specific layer.
     */
    public void removeHighlight(int layer, Object highlight) {
        getLayer(layer).remove(highlight);
        repaint();
    }

    /**
     * Clears all highlights in a specific layer.
     */
    public void removeHighlights(int layer) {
        getLayer(layer).clear();
        repaint();
    }

    /**
     * Removes all highlights from all layers.
     */
    public void removeAllHighlights() {
        highlights.clear();
        repaint();
    }

    /**
     * Updates the position of an existing highlight.
     */
    public void changeHighlight(Object highlight, int p0, int p1) throws BadLocationException {
        if (!(highlight instanceof HighlightInfo)) return;

        HighlightInfo hli = (HighlightInfo) highlight;
        Document doc = component.getDocument();
        hli.p0 = doc.createPosition(p0);
        hli.p1 = doc.createPosition(p1);
        repaint();
    }

    /**
     * Retrieves all active highlights.
     */
    public Highlight[] getHighlights() {
        return highlights.values().stream()
                .flatMap(List::stream)
                .toArray(Highlight[]::new);
    }

    /**
     * Ensures a highlight layer exists and retrieves it.
     */
    private List<Highlight> getLayer(int layer) {
        return highlights.computeIfAbsent(layer, k -> new ArrayList<>());
    }

    /**
     * Triggers a repaint of the text component.
     */
    public void repaint() {
        if (component != null) {
            component.repaint();
        }
    }
    
    /**
     * ThemeAware callback – simply repaint the host component so that any
     * highlight painters created with theme-specific colours can refresh.
     */
    @Override
    public void applyTheme(GuiTheme guiTheme)
    {
        repaint();
    }
    
    /**
     * Represents a highlight within the text component.
     */
    static class HighlightInfo implements Highlight {
        private Position p0;
        private Position p1;
        private final HighlightPainter painter;

        public HighlightInfo(Position p0, Position p1, HighlightPainter painter) {
            this.p0 = p0;
            this.p1 = p1;
            this.painter = painter;
        }

        public int getStartOffset() {
            return p0.getOffset();
        }

        public int getEndOffset() {
            return p1.getOffset();
        }

        public HighlightPainter getPainter() {
            return painter;
        }
    }
}
