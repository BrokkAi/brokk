package io.github.jbellis.brokk.gui.mop;

import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentData;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Test utilities for markdown rendering tests.
 */
public final class TestUtil {
    /**
     * Parses markdown text into a list of ComponentData objects using reflection
     * to access the private buildComponentData method.
     *
     * @param md Markdown text to parse
     * @return List of ComponentData objects parsed from the markdown
     */
    @SuppressWarnings("unchecked")
    public static List<ComponentData> parseMarkdown(String md) {
        try {
            var renderer = new IncrementalBlockRenderer(false);

            var m = IncrementalBlockRenderer.class.getDeclaredMethod("createHtml", String.class);
            m.setAccessible(true);
            var html = (String) m.invoke(renderer, md);
            m = IncrementalBlockRenderer.class.getDeclaredMethod("buildComponentData", String.class);
            m.setAccessible(true);
            return (List<ComponentData>) m.invoke(renderer, html);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse markdown via reflection", e);
        }
    }

    /**
     * Convert HTML to a list of ComponentData objects using reflection.
     * Useful for testing the direct HTML parsing path.
     *
     * @param html HTML string to parse
     * @return List of ComponentData objects parsed from the HTML
     */
    @SuppressWarnings("unchecked")
    public static List<ComponentData> parseHtml(String html) {
        try {
            var renderer = new IncrementalBlockRenderer(false);
            var m = IncrementalBlockRenderer.class.getDeclaredMethod("buildComponentData", String.class);
            m.setAccessible(true);
            return (List<ComponentData>) m.invoke(renderer, html);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse HTML via reflection", e);
        }
    }
}
