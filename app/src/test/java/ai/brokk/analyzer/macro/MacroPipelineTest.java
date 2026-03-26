package ai.brokk.analyzer.macro;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
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

        boolean foundVec = policy.macros().stream()
                .anyMatch(m -> "vec".equals(m.name()) && m.strategy() == MacroPolicy.MacroStrategy.BYPASS);
        assertTrue(foundVec, "Should find 'vec' with BYPASS strategy");
    }

    @Test
    void testLoadIsMacroPolicyAndExpand() throws IOException {
        // The Is macro in is_macro.yml has parent: "is_macro"
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

        // Create a parent CodeUnit (Enum) with child variants.
        // We use null for ProjectFile as it's not needed for template expansion.
        CodeUnit parent = CodeUnit.cls(null, "state", "Status");

        // The Is macro has parent: "is_macro"

        CodeUnit variant1 = CodeUnit.field(null, "state", "Status.Running");
        CodeUnit variant2 = CodeUnit.field(null, "state", "Status.Stopped");

        Map<String, Object> context = Map.of("code_unit", parent, "children", List.of(variant1, variant2));

        String expanded = MacroTemplateExpander.expand(template, context);

        assertTrue(expanded.contains("pub fn is_Running(&self) -> bool { matches!(self, Self::Running { .. }) }"));
        assertTrue(expanded.contains("pub fn is_Stopped(&self) -> bool { matches!(self, Self::Stopped { .. }) }"));
    }
}
