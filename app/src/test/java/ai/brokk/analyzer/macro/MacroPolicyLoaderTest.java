package ai.brokk.analyzer.macro;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MacroPolicyLoaderTest {

    @Test
    void testParseValidYaml() throws IOException {
        String yaml =
                """
                version: "1.0"
                language: "rust"
                macros:
                  - name: "println"
                    strategy: "BYPASS"
                    options: {}
                  - name: "vec"
                    scope: "LIBRARY"
                    strategy: "AI_EXPAND"
                    options:
                      max_tokens: 500
                """;

        MacroPolicy policy = MacroPolicyLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

        assertEquals("1.0", policy.version());
        assertEquals("rust", policy.language());
        assertEquals(2, policy.macros().size());

        MacroPolicy.MacroMatch println = policy.macros().get(0);
        assertEquals("println", println.name());
        assertEquals(MacroPolicy.MacroStrategy.BYPASS, println.strategy());
        assertEquals(MacroPolicy.MacroScope.LIBRARY, println.scope());
        assertInstanceOf(MacroPolicy.BypassConfig.class, println.options());

        MacroPolicy.MacroMatch vec = policy.macros().get(1);
        assertEquals("vec", vec.name());
        assertEquals(MacroPolicy.MacroStrategy.AI_EXPAND, vec.strategy());
        assertEquals(MacroPolicy.MacroScope.LIBRARY, vec.scope());
        assertNotNull(vec.options());
        assertInstanceOf(MacroPolicy.AIExpandConfig.class, vec.options());
        assertEquals(500, ((MacroPolicy.AIExpandConfig) vec.options()).max_tokens());
    }
}
