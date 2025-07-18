package io.github.jbellis.brokk.gui;

import com.vladsch.flexmark.ext.emoji.EmojiExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Handles rendering of GitHub Flavored Markdown (GFM) to HTML.
 * This class encapsulates the flexmark-java library setup for parsing and rendering
 * with common GFM extensions like tables, strikethrough, and emoji.
 * The parser and renderer instances are created once and reused for efficiency.
 */
public class GfmRenderer {
    private final Parser flexmarkParser;
    private final HtmlRenderer flexmarkRenderer;

    /**
     * Constructs a GfmRenderer, initializing the flexmark parser and renderer
     * with GFM-specific extensions (Tables, Strikethrough, Emoji).
     */
    public GfmRenderer() {
        List<Extension> extensions = List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                EmojiExtension.create()
        );
        MutableDataSet options = new MutableDataSet()
                .set(TablesExtension.MIN_SEPARATOR_DASHES, 1);

        this.flexmarkParser = Parser.builder(options)
                .extensions(extensions)
                .build();
        this.flexmarkRenderer = HtmlRenderer.builder(options)
                .extensions(extensions)
                .build();
    }

    /**
     * Renders the given Markdown text to HTML.
     * If the input markdownText is null, it will be treated as an empty string.
     *
     * @param markdownText The Markdown text to render.
     * @return The rendered HTML string.
     */
    public String render(@Nullable String markdownText) {
        Node document = this.flexmarkParser.parse(markdownText == null ? "" : markdownText);
        return this.flexmarkRenderer.render(document);
    }
}
