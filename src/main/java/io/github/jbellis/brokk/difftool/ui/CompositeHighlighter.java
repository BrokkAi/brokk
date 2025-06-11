package io.github.jbellis.brokk.difftool.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaHighlighter;

import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.Arrays;
import java.util.stream.Stream;

import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.ThemeAware;

/**
 * Extends {@link RSyntaxTextAreaHighlighter} to provide base syntax highlighting and delegates
 * to a secondary {@link Highlighter} (typically {@link JMHighlighter}) for additional decorations like diff/search.
 * Mutating operations are forwarded to the secondary highlighter.
 */
public class CompositeHighlighter extends RSyntaxTextAreaHighlighter implements ThemeAware
{
    private final Highlighter secondary;

    public CompositeHighlighter(Highlighter secondaryHighlighter)
    {
        super();
        if (secondaryHighlighter == null) {
            throw new IllegalArgumentException("Secondary highlighter cannot be null");
        }
        this.secondary = secondaryHighlighter;
    }

    @Override
    public void install(JTextComponent c)
    {
        super.install(c); // RSyntaxTextAreaHighlighter's install
        // The 'secondary' highlighter should also be associated with the component
        // if it tracks the component state independently (like JMHighlighter does).
        secondary.install(c);
    }

    @Override
    public void deinstall(JTextComponent c)
    {
        super.deinstall(c);
        secondary.deinstall(c);
    }

    @Override
    public void paint(Graphics g)
    {
        super.paint(g);     // RSyntaxTextAreaHighlighter paints syntax highlights
        secondary.paint(g); // Secondary paints diff/search highlights on top
    }

    @Override
    public Object addHighlight(int p0, int p1, HighlightPainter painter) throws BadLocationException
    {
        // forward to JMHighlighter (secondary)
        return secondary.addHighlight(p0, p1, painter);
    }

    @Override
    public void removeHighlight(Object tag)
    {
        secondary.removeHighlight(tag);
    }

    @Override
    public void removeAllHighlights()
    {
        secondary.removeAllHighlights();
    }

    @Override
    public void changeHighlight(Object tag, int p0, int p1) throws BadLocationException
    {
        secondary.changeHighlight(tag, p0, p1);
    }

    @Override
    public Highlight[] getHighlights()
    {
        // Highlights from RSyntaxTextAreaHighlighter (e.g., syntax, mark occurrences)
        Highlight[] a = super.getHighlights();
        // Highlights from JMHighlighter (diffs, search results)
        Highlight[] b = secondary.getHighlights();

        return Stream.concat(Arrays.stream(a), Arrays.stream(b))
                     .toArray(Highlight[]::new);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme)
    {
        // Forward theme changes to the secondary highlighter if it supports themes
        if (secondary instanceof ThemeAware themeAware) {
            themeAware.applyTheme(guiTheme);
        }
    }
}
