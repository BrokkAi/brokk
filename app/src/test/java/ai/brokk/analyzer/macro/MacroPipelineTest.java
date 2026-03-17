package ai.brokk.analyzer.macro;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class MacroPipelineTest {

    @Test
    void testLoadStdV1Policy() throws IOException {
        MacroPolicy policy = MacroPolicyLoader.loadFromResource("/macros/rust/std-v1.yml");
        assertNotNull(policy);
        assertEquals("rust", policy.language());

        boolean foundMyMacro = policy.macros().stream()
                .anyMatch(m -> "my_macro".equals(m.name()) && m.strategy() == MacroPolicy.MacroStrategy.TEMPLATE);
        assertTrue(foundMyMacro, "Should find 'my_macro' with TEMPLATE strategy");
    }

    @Test
    void testLoadIsMacroPolicyAndExpand() throws IOException {
        MacroPolicy policy = MacroPolicyLoader.loadFromResource("/macros/rust/is_macro.yml");
        assertNotNull(policy);
        assertEquals("rust", policy.language());

        MacroPolicy.MacroMatch isMacro = policy.macros().stream()
                .filter(m -> "Is".equals(m.name()))
                .findFirst()
                .orElseThrow();

        assertEquals(MacroPolicy.MacroStrategy.TEMPLATE, isMacro.strategy());
        assertInstanceOf(MacroPolicy.TemplateConfig.class, isMacro.options());

        String template = ((MacroPolicy.TemplateConfig) isMacro.options()).template();
        Map<String, Object> context = Map.of("variant_name", "Running");
        String expanded = MacroTemplateExpander.expand(template, context);

        assertEquals("pub fn is_Running(&self) -> bool { matches!(self, Self::Running { .. }) }", expanded);
    }

    @Test
    void testTemplateExpansion() throws IOException {
        MacroPolicy policy = MacroPolicyLoader.loadFromResource("/macros/rust/std-v1.yml");
        MacroPolicy.MacroMatch myMacro = policy.macros().stream()
                .filter(m -> "my_macro".equals(m.name()))
                .findFirst()
                .orElseThrow();

        assertInstanceOf(MacroPolicy.TemplateConfig.class, myMacro.options());
        String template = ((MacroPolicy.TemplateConfig) myMacro.options()).template();

        Map<String, Object> context = Map.of(
                "name",
                "User",
                "fields",
                List.of(Map.of("name", "id", "type", "u32"), Map.of("name", "username", "type", "String")));

        String expanded = MacroTemplateExpander.expand(template, context);

        // Normalize whitespace for comparison
        String normalized = expanded.replaceAll("\\s+", " ").trim();
        assertEquals("pub struct User { id: u32, username: String, }", normalized);
    }
}
