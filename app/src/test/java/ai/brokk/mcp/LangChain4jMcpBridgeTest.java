package ai.brokk.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.mcpserver.LangChain4jMcpBridge;
import ai.brokk.tools.WorkspaceTools;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LangChain4jMcpBridgeTest {

    static class TestToolProvider {
        @Tool("A test tool")
        public String testMethod(
                @P("A string param") String stringParam,
                @P("An int param") int intParam,
                @P("A list param") List<String> listParam,
                @P("A boolean param") boolean boolParam) {
            return "ok";
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testToMcpSchemaConversion() {
        TestToolProvider provider = new TestToolProvider();
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(provider);
        assertEquals(1, specs.size());
        ToolSpecification spec = specs.get(0);

        assertNotNull(spec.parameters());
        McpSchema.JsonSchema mcpSchema = LangChain4jMcpBridge.toMcpSchema(spec.parameters());

        assertEquals("object", mcpSchema.type());
        Map<String, Object> props = mcpSchema.properties();
        assertNotNull(props);

        // Check string param
        Map<String, Object> sParam = (Map<String, Object>) props.get("stringParam");
        assertEquals("string", sParam.get("type"));
        assertEquals("A string param", sParam.get("description"));

        // Check int param
        Map<String, Object> iParam = (Map<String, Object>) props.get("intParam");
        assertEquals("integer", iParam.get("type"));
        assertEquals("An int param", iParam.get("description"));

        // Check boolean param
        Map<String, Object> bParam = (Map<String, Object>) props.get("boolParam");
        assertEquals("boolean", bParam.get("type"));
        assertEquals("A boolean param", bParam.get("description"));

        // Check list param
        Map<String, Object> lParam = (Map<String, Object>) props.get("listParam");
        assertEquals("array", lParam.get("type"));
        assertEquals("A list param", lParam.get("description"));
        Map<String, Object> items = (Map<String, Object>) lParam.get("items");
        assertEquals("string", items.get("type"));

        // Check required
        List<String> required = mcpSchema.required();
        assertTrue(required.contains("stringParam"));
        assertTrue(required.contains("intParam"));
        assertTrue(required.contains("listParam"));
        assertTrue(required.contains("boolParam"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFragmentRemovalDescriptionPropagation() {
        // WorkspaceTools.dropWorkspaceFragments uses List<FragmentRemoval>
        // FragmentRemoval has @D annotations on its fields.
        List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(WorkspaceTools.class);
        ToolSpecification dropSpec = specs.stream()
                .filter(s -> s.name().equals("dropWorkspaceFragments"))
                .findFirst()
                .orElseThrow();

        assertNotNull(dropSpec.parameters());
        McpSchema.JsonSchema mcpSchema = LangChain4jMcpBridge.toMcpSchema(dropSpec.parameters());

        Map<String, Object> props = mcpSchema.properties();
        Map<String, Object> fragmentsParam = (Map<String, Object>) props.get("fragments");
        assertNotNull(fragmentsParam);

        Map<String, Object> items = (Map<String, Object>) fragmentsParam.get("items");
        assertEquals("object", items.get("type"));

        Map<String, Object> itemProps = (Map<String, Object>) items.get("properties");

        // Verify @D content from FragmentRemoval record
        Map<String, Object> fragmentId = (Map<String, Object>) itemProps.get("fragmentId");
        assertEquals("The alphanumeric ID exactly as listed in <workspace_toc>", fragmentId.get("description"));

        Map<String, Object> keyFacts = (Map<String, Object>) itemProps.get("keyFacts");
        assertEquals(WorkspaceTools.KEY_FACTS_DESCRIPTION, keyFacts.get("description"));

        Map<String, Object> dropReason = (Map<String, Object>) itemProps.get("dropReason");
        assertEquals(WorkspaceTools.DROP_REASON_DESCRIPTION, dropReason.get("description"));
    }
}
