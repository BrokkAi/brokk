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
                  - name: "vec"
                    path: "std::vec"
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
        assertNull(println.path());

        MacroPolicy.MacroMatch vec = policy.macros().get(1);
        assertEquals("vec", vec.name());
        assertEquals("std::vec", vec.path());
        assertEquals(MacroPolicy.MacroStrategy.AI_EXPAND, vec.strategy());
        assertNotNull(vec.options());
        assertEquals(500, vec.options().get("max_tokens"));
    }
}
