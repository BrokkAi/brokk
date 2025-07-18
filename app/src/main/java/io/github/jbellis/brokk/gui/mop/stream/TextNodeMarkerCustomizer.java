package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HtmlCustomizer that wraps every appearance of a given term occurring in normal
 * text nodes with configurable start/end wrappers.  Works at the DOM level,
 * leaving existing markup untouched.
 */
public final class TextNodeMarkerCustomizer implements HtmlCustomizer {

    private final Pattern pattern;
    private final String wrapperStart;
    private final String wrapperEnd;

    /**
     * Tags inside which we deliberately skip highlighting.
     */
    private static final Set<String> SKIP_TAGS =
            Set.of("script", "style", "img");

    /**
     * Attribute used to mark wrapper elements created by this customizer.
     * Any text inside an element bearing this attribute will be ignored on
     * subsequent traversals, preventing repeated wrapping.
     */
    private static final String BROKK_MARKER_ATTR = "data-brokk-marker";

    /**
     * Attribute that carries a stable numeric id for later component lookup.
     */
    private static final String BROKK_ID_ATTR = "data-brokk-id";

    /**
     * Global id generator for {@link #BROKK_ID_ATTR}. Thread-safe, simple monotonic counter.
     * We deliberately do not reset between customizer instances because we only
     * need *uniqueness* inside a single JVM session.
     */
    private static final AtomicInteger ID_GEN =
            new AtomicInteger(1);

    /**
     * @param term          the term to highlight (must not be empty)
     * @param caseSensitive true if the match should be case-sensitive
     * @param wholeWord     true to require word boundaries around the term
     * @param wrapperStart  snippet inserted before the match (may contain HTML)
     * @param wrapperEnd    snippet inserted after  the match (may contain HTML)
     */
    public TextNodeMarkerCustomizer(String term,
                                    boolean caseSensitive,
                                    boolean wholeWord,
                                    String wrapperStart,
                                    String wrapperEnd) {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(wrapperStart, "wrapperStart");
        Objects.requireNonNull(wrapperEnd, "wrapperEnd");
        if (term.isEmpty()) {
            throw new IllegalArgumentException("term must not be empty");
        }
        this.wrapperStart = wrapperStart;
        this.wrapperEnd = wrapperEnd;

        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        var regex = Pattern.quote(term);
        if (wholeWord) {
            regex = "\\b" + regex + "\\b";
        }
        this.pattern = Pattern.compile(regex, flags);
    }

    /**
     * Fast check used by renderers to see if this customizer could possibly
     * influence the supplied text.  Returns {@code true} if the term occurs
     * at least once according to the current configuration.
     *
     * @param text input to test (may be {@code null})
     */
    public boolean mightMatch(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return pattern.matcher(text).find();
    }

    @Override
    public void customize(Element root) {
        // ------------------------------------------------------------------
        // 1.  Remove any highlight wrappers left from a previous search.
        //     We unwrap each element that bears BROKK_MARKER_ATTR by hoisting
        //     its children into the same position and then deleting the wrapper
        //     itself.  After this step the DOM contains *no* search markup.
        // ------------------------------------------------------------------
        for (var el : root.select("[" + BROKK_MARKER_ATTR + "]")) {
            // Copy to avoid ConcurrentModificationException while rewriting
            var children = new ArrayList<Node>(el.childNodes());
            for (Node child : children) {
                el.before(child);
            }
            el.remove();
        }

        // ------------------------------------------------------------------
        // 2.  Add fresh highlights in a single traversal with proper ordering
        // ------------------------------------------------------------------
        NodeTraversor.traverse(new Visitor(), root);
    }

    
    private class Visitor implements NodeVisitor {
        @Override
        public void head(Node node, int depth) {
            if (node instanceof TextNode textNode) {
                process(textNode);
            }
        }
        @Override public void tail(Node node, int depth) { /* no-op */ }

        private void process(TextNode tn) {
            if (tn.isBlank()) return;
            if (hasAncestorMarker(tn)) return;
            if (tn.parent() instanceof Element el &&
                    SKIP_TAGS.contains(el.tagName().toLowerCase(Locale.ROOT))) {
                return; // skip inside forbidden tags
            }

            String text = tn.getWholeText();
            // Quick check if there's anything to highlight
            if (!pattern.matcher(text).find()) return; // nothing to highlight
            
            // Find all matches in this text node
            Matcher m = pattern.matcher(text);
            List<MatchRange> ranges = new ArrayList<>();
            while (m.find()) {
                ranges.add(new MatchRange(m.start(), m.end()));
            }
            
            // Apply all highlights to this text node at once
            applyHighlightsToTextNode(tn, text, ranges);
        }

        /**
         * Returns true if the node has an ancestor element that already
         * carries the custom wrapper attribute, meaning it has been processed.
         */
        private boolean hasAncestorMarker(Node node) {
            for (Node p = node.parent(); p != null; p = p.parent()) {
                if (p instanceof Element e && e.hasAttr(BROKK_MARKER_ATTR)) {
                    return true;
                }
            }
            return false;
        }
    }
    
    private static class MatchRange {
        final int start;
        final int end;
        
        MatchRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
    
    /**
     * Apply multiple highlights to a single text node.
     * Ranges are already sorted by start position from the matcher.
     */
    private void applyHighlightsToTextNode(TextNode textNode, String text, List<MatchRange> ranges) {
        List<Node> pieces = new ArrayList<>();
        int lastEnd = 0;
        
        for (MatchRange range : ranges) {
            // Add text before this match
            if (range.start > lastEnd) {
                pieces.add(new TextNode(text.substring(lastEnd, range.start)));
            }
            
            // Add the highlighted match
            String matchText = text.substring(range.start, range.end);
            int markerId = ID_GEN.getAndIncrement();
            
            var snippetHtml = wrapperStart + matchText + wrapperEnd;
            var fragment = Jsoup.parseBodyFragment(snippetHtml).body().childNodes();
            for (Node fragNode : fragment) {
                if (fragNode instanceof Element fragEl) {
                    fragEl.attr(BROKK_MARKER_ATTR, "1");
                    fragEl.attr(BROKK_ID_ATTR, Integer.toString(markerId));
                }
            }
            pieces.addAll(fragment);
            
            lastEnd = range.end;
        }
        
        // Add remaining text after the last match
        if (lastEnd < text.length()) {
            pieces.add(new TextNode(text.substring(lastEnd)));
        }
        
        // Replace the original text node with all the pieces
        Node ref = textNode;
        for (Node n : pieces) {
            ref.after(n);
            ref = n;
        }
        textNode.remove();
    }

}
