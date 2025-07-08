package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.nodes.Element;

/**
 * Functional interface allowing callers to tweak the parsed HTML DOM
 * (already produced from Markdown) in&nbsp;place before Swing components
 * are built.  Implementations may freely mutate the supplied element tree
 * but MUST NOT replace the root element.
 */
@FunctionalInterface
public interface HtmlCustomizer {

    /**
     * No-op customizer that leaves the DOM unchanged.
     */
    HtmlCustomizer DEFAULT = root -> { };

    /**
     * Convenience accessor for the {@link #DEFAULT} instance.
     *
     * @return an identity/no-op HtmlCustomizer
     */
    static HtmlCustomizer noOp() {
        return DEFAULT;
    }

    /**
     * Mutate the supplied DOM tree in place.
     *
     * @param root the root element (typically {@code <body>})
     */
    void customize(Element root);
}
