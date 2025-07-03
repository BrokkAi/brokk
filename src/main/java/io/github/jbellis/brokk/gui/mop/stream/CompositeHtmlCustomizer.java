package io.github.jbellis.brokk.gui.mop.stream;

import org.jsoup.nodes.Element;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// Applies multiple HTML customizers in sequence, deduplicating by ID
public class CompositeHtmlCustomizer implements HtmlCustomizer {
    private final List<HtmlCustomizer> customizers;
    private static final int CUSTOMIZER_ID = 1000;

    public CompositeHtmlCustomizer(HtmlCustomizer... customizers) {
        this.customizers = deduplicateCustomizers(Arrays.asList(customizers));
    }

    public CompositeHtmlCustomizer(List<HtmlCustomizer> customizers) {
        this.customizers = deduplicateCustomizers(customizers);
    }

    @Override
    public void customize(Element root) {
        for (var customizer : customizers) {
            customizer.customize(root);
        }
    }

    @Override
    public int getCustomizerId() {
        return CUSTOMIZER_ID;
    }

    @Override
    public void markStreamingComplete() {
        for (var customizer : customizers) {
            customizer.markStreamingComplete();
        }
    }

    // Removes duplicates by ID, preserving order
    private static List<HtmlCustomizer> deduplicateCustomizers(List<HtmlCustomizer> input) {
        if (input.isEmpty()) {
            return List.of();
        }

        Set<Integer> seenIds = new LinkedHashSet<>();
        return input.stream()
                .filter(Objects::nonNull)
                .filter(customizer -> seenIds.add(customizer.getCustomizerId()))
                .collect(Collectors.toList());
    }

}
