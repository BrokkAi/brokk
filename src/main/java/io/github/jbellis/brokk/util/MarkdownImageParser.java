package io.github.jbellis.brokk.util;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarkdownImageParser {
    private static final Pattern IMAGE_MARKDOWN_PATTERN = Pattern.compile("!\\[(?:[^\\]]*)\\]\\(([^\\)]+)\\)");
    private static final Pattern HTML_IMG_TAG_PATTERN = Pattern.compile("<img\\s+[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?/?>", Pattern.CASE_INSENSITIVE);

    private MarkdownImageParser() {
        // Prevent instantiation
    }

    /**
     * Extracts image URLs from a given text content that might contain Markdown image syntax or HTML img tags.
     *
     * @param textContent The text content to parse.
     * @return A set of unique image URLs found in the content. Returns an empty set if the content is null or blank.
     */
    public static @NotNull Set<String> extractImageUrls(@NotNull String textContent) {
        if (textContent.isBlank()) {
            return Set.of();
        }

        Set<String> uniqueImageUrls = new LinkedHashSet<>();

        // Find and add URLs from Markdown image links
        Matcher markdownMatcher = IMAGE_MARKDOWN_PATTERN.matcher(textContent);
        while (markdownMatcher.find()) {
            uniqueImageUrls.add(markdownMatcher.group(1));
        }

        // Find and add URLs from HTML <img> tags
        Matcher htmlMatcher = HTML_IMG_TAG_PATTERN.matcher(textContent);
        while (htmlMatcher.find()) {
            uniqueImageUrls.add(htmlMatcher.group(1));
        }
        return uniqueImageUrls;
    }
}
