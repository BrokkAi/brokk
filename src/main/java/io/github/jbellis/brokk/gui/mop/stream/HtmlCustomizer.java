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
    HtmlCustomizer DEFAULT = new HtmlCustomizer() {
        @Override
        public void customize(Element root) {
            // No-op
        }

        @Override
        public int getCustomizerId() {
            return 0; // Reserved ID for default/no-op customizer
        }
    };

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

    /**
     * Returns a unique identifier for this customizer type.
     * Used by CompositeHtmlCustomizer to prevent duplicate customizers.
     * Implementations should return a consistent ID for the same customizer type.
     *
     * @return a unique integer identifier for this customizer type
     */
    default int getCustomizerId() {
        return this.getClass().hashCode();
    }

    /**
     * Called when streaming is complete and the customizer can perform
     * expensive operations like symbol analysis and badge generation.
     * Default implementation does nothing.
     */
    default void markStreamingComplete() {
        // No-op by default
    }
}
