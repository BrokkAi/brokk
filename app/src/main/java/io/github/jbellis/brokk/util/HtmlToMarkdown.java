package io.github.jbellis.brokk.util;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.html2md.converter.HtmlNodeRendererHandler;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

public class HtmlToMarkdown {

    /**
     * Converts the given string to Markdown if it looks like HTML.
     * Otherwise returns it unchanged.
     */
    public static String maybeConvertToMarkdown(String maybeHtml) {
        if (!looksLikeHtml(maybeHtml)) {
            return maybeHtml;
        }

        // 1) Extract and clean just the <body> content with Jsoup:
        Document doc = Jsoup.parse(maybeHtml);
        doc.select("script, style, form, iframe").remove(); // optional removals
        Element body = doc.body();
        String bodyHtml = body.html();

        // 2) Configure Flexmark options and enable the custom extension:
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Collections.singletonList(IgnoreLinksAndImagesExtension.create()));

        // 3) Convert body HTML to Markdown:
        return FlexmarkHtmlConverter.builder(options).build().convert(bodyHtml);
    }

    // Very simple check: if it has <html> or <body> or typical markup tags, assume HTML
    private static boolean looksLikeHtml(String input) {
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("<html") || lower.contains("<body")
                || lower.contains("<div") || lower.contains("<p")
                || lower.contains("<!doctype");
    }

    /**
     * A custom Flexmark extension that ignores actual hyperlinks (keeps their text) and images.
     */
    static class IgnoreLinksAndImagesExtension implements FlexmarkHtmlConverter.HtmlConverterExtension {
        public static IgnoreLinksAndImagesExtension create() {
            return new IgnoreLinksAndImagesExtension();
        }

        @Override
        public void rendererOptions(MutableDataHolder options) {
            // no-op
        }

        @Override
        public void extend(FlexmarkHtmlConverter.Builder builder) {
            builder.htmlNodeRendererFactory(dataHolder -> () -> new HashSet<>(Arrays.asList(
                    // Ignore <a> tag; output only its text
                    new HtmlNodeRendererHandler<>("a", Element.class, (node, context, out) -> {
                        context.renderChildren(node, true, null);
                    }),
                    // Skip <img> entirely
                    new HtmlNodeRendererHandler<>("img", Element.class, (node, context, out) -> {
                        // no output
                    })
            )));
        }
    }
}
