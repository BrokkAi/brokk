package ai.brokk.analyzer.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MacroTemplateExpanderTest {

    @Test
    void testExpandSimpleVariables() {
        String template = "Hello {{name}}! Welcome to {{place}}.";
        Map<String, Object> context = Map.of(
                "name", "Brokk",
                "place", "the Forge");

        String result = MacroTemplateExpander.expand(template, context);

        assertEquals("Hello Brokk! Welcome to the Forge.", result);
    }

    @Test
    void testExpandWithMissingVariables() {
        String template = "Keep it {{secret}}.";
        Map<String, Object> context = Map.of();

        String result = MacroTemplateExpander.expand(template, context);

        assertEquals("Keep it .", result);
    }

    @Test
    void testExpandWithSections() {
        String template = "List: {{#items}}{{.}} {{/items}}";
        Map<String, Object> context = Map.of("items", java.util.List.of("a", "b", "c"));

        String result = MacroTemplateExpander.expand(template, context);

        assertEquals("List: a b c ", result);
    }
}
