package io.github.jbellis.brokk.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolRegistry registry;
    private TestTools tools;

    @BeforeEach
    void setup() {
        registry = new ToolRegistry(null);
        tools = new TestTools();
        registry.register(tools);
    }

    @Test
    void signatureUnits_ListOfStrings() throws Exception {
        Method m = TestTools.class.getDeclaredMethod("getClassSources", List.class, String.class);
        String json = jsonArgs(m, List.of("com.a.A", "com.b.B"), "why not");
        var req = ToolExecutionRequest.builder()
                .name("getClassSources")
                .arguments(json)
                .build();

        var units = registry.signatureUnits(tools, req);
        assertEquals(2, units.size());
        assertEquals("getClassSources", units.get(0).toolName());
        assertEquals(units.get(0).paramName(), units.get(1).paramName());
        assertEquals("classNames", units.get(0).paramName());
        assertEquals("com.a.A", units.get(0).item());
        assertEquals("com.b.B", units.get(1).item());
    }

    @Test
    void buildRequestFromUnits_PreservesScalars() throws Exception {
        Method m = TestTools.class.getDeclaredMethod("getClassSources", List.class, String.class);
        String json = jsonArgs(m, List.of("A", "B", "C"), "reason");
        var req = ToolExecutionRequest.builder()
                .name("getClassSources")
                .arguments(json)
                .build();

        var allUnits = registry.signatureUnits(tools, req);
        // choose a subset to simulate "new" items
        var subset = List.of(allUnits.get(1), allUnits.get(2));

        var rewritten = registry.buildRequestFromUnits(req, subset);
        assertEquals("getClassSources", rewritten.name());

        // verify classNames replaced, reasoning preserved
        var map = MAPPER.readValue(rewritten.arguments(), LinkedHashMap.class);
        assertEquals(List.of("B", "C"), map.get("classNames"));
        assertEquals("reason", map.get("reasoning"));
    }

    @Test
    void signatureUnits_ThrowsOnNonListTool() throws Exception {
        Method m = TestTools.class.getDeclaredMethod("getCallGraphTo", String.class, int.class);
        String json = jsonArgs(m, "com.a.A.m", 3);
        var req = ToolExecutionRequest.builder()
                .name("getCallGraphTo")
                .arguments(json)
                .build();

        assertThrows(IllegalArgumentException.class, () -> registry.signatureUnits(tools, req));
    }

    @Test
    void signatureUnits_ListOfIntegers() throws Exception {
        Method m = TestTools.class.getDeclaredMethod("listInts", List.class, String.class);
        String json = jsonArgs(m, List.of(1, 2, 3), "r");
        var req =
                ToolExecutionRequest.builder().name("listInts").arguments(json).build();

        var units = registry.signatureUnits(tools, req);
        assertEquals(3, units.size());
        assertEquals(1, units.get(0).item());
        assertEquals(2, units.get(1).item());
        assertEquals(3, units.get(2).item());
    }

    @Test
    void buildRequestFromUnits_ListOfIntegers() throws Exception {
        Method m = TestTools.class.getDeclaredMethod("listInts", List.class, String.class);
        String json = jsonArgs(m, List.of(10, 20), "why");
        var req =
                ToolExecutionRequest.builder().name("listInts").arguments(json).build();

        var units = registry.signatureUnits(tools, req);
        // reorder to ensure order follows provided units (caller-controlled)
        var subset = List.of(units.get(1), units.get(0));
        var rewritten = registry.buildRequestFromUnits(req, subset);

        var map = MAPPER.readValue(rewritten.arguments(), LinkedHashMap.class);
        assertEquals(List.of(20, 10), map.get("ids"));
        assertEquals("why", map.get("reason"));
    }

    @Test
    void signatureUnits_ThrowsOnMultiListTool() throws Exception {
        Method m = TestTools.class.getDeclaredMethod("multiList", List.class, List.class);
        String json = jsonArgs(m, List.of("a"), List.of("b"));
        var req =
                ToolExecutionRequest.builder().name("multiList").arguments(json).build();

        assertThrows(IllegalArgumentException.class, () -> registry.signatureUnits(tools, req));
    }

    @Test
    void validateTool_Succeeds_WithTypedListAndScalar() throws Exception {
        Method m = TestTools.class.getDeclaredMethod("getClassSources", List.class, String.class);
        String json = jsonArgs(m, List.of("com.a.A", "com.b.B"), "why not");
        var req = ToolExecutionRequest.builder()
                .name("getClassSources")
                .arguments(json)
                .build();

        var vi = registry.validateTool(tools, req);
        assertEquals("getClassSources", vi.method().getName());
        assertSame(tools, vi.instance());
        assertEquals(2, vi.parameters().size());

        Object p0 = vi.parameters().get(0);
        assertTrue(p0 instanceof List);
        assertEquals(List.of("com.a.A", "com.b.B"), p0);

        assertEquals("why not", vi.parameters().get(1));
    }

    @Test
    void validateTool_ThrowsOnMissingParameter() throws Exception {
        // Build args missing the second parameter ("reasoning")
        var map = new LinkedHashMap<String, Object>();
        map.put("classNames", List.of("com.a.A", "com.b.B"));
        var json = MAPPER.writeValueAsString(map);

        var req = ToolExecutionRequest.builder()
                .name("getClassSources")
                .arguments(json)
                .build();
        var ex = assertThrows(ToolRegistry.ToolValidationException.class, () -> registry.validateTool(tools, req));
        assertTrue(ex.getMessage().contains("Missing required parameter: 'reasoning'"));
    }

    @Test
    void validateTool_ThrowsOnToolNotFound() {
        var req = ToolExecutionRequest.builder()
                .name("noSuchTool")
                .arguments("{}")
                .build();
        var ex = assertThrows(ToolRegistry.ToolValidationException.class, () -> registry.validateTool(tools, req));
        assertTrue(ex.getMessage().contains("Tool not found"));
    }

    @Test
    void validateTool_ThrowsOnBlankToolName() {
        var req = ToolExecutionRequest.builder().name("").arguments("{}").build();
        var ex = assertThrows(ToolRegistry.ToolValidationException.class, () -> registry.validateTool(tools, req));
        assertTrue(ex.getMessage().contains("Tool name cannot be empty"));
    }

    @Test
    void validateTool_ThrowsOnListElementTypeError() throws Exception {
        // listInts expects List<Integer>; provide strings to trigger conversion/type error
        var bad = new LinkedHashMap<String, Object>();
        bad.put("ids", List.of("a", "b", "c"));
        bad.put("reason", "r");
        var json = MAPPER.writeValueAsString(bad);

        var req =
                ToolExecutionRequest.builder().name("listInts").arguments(json).build();
        assertThrows(ToolRegistry.ToolValidationException.class, () -> registry.validateTool(tools, req));
    }

    @Test
    void getExplanationForToolRequest_ValidTool() throws Exception {
        Method m = TestTools.class.getDeclaredMethod("getClassSources", List.class, String.class);
        String json = jsonArgs(m, List.of("com.a.A", "com.b.B"), "analyzing dependencies");
        var req = ToolExecutionRequest.builder()
                .name("getClassSources")
                .arguments(json)
                .build();

        String explanation = registry.getExplanationForToolRequest(tools, req);

        assertFalse(explanation.isBlank());
        assertTrue(explanation.contains("Fetching class source"));
        assertTrue(explanation.contains("com.a.A"));
        assertTrue(explanation.contains("com.b.B"));
        assertTrue(explanation.contains("analyzing dependencies"));
    }

    @Test
    void getExplanationForToolRequest_MissingParameter() throws Exception {
        // Build args missing the second parameter ("reasoning")
        var map = new LinkedHashMap<String, Object>();
        map.put("classNames", List.of("com.a.A"));
        var json = MAPPER.writeValueAsString(map);

        var req = ToolExecutionRequest.builder()
                .name("getClassSources")
                .arguments(json)
                .build();

        // Should NOT throw - validation errors return empty string (details logged only)
        String explanation = registry.getExplanationForToolRequest(tools, req);

        assertTrue(explanation.isBlank());
    }

    @Test
    void getExplanationForToolRequest_InvalidJson() {
        var req = ToolExecutionRequest.builder()
                .name("getClassSources")
                .arguments("not valid json at all")
                .build();

        // Should NOT throw - validation errors return empty string (details logged only)
        String explanation = registry.getExplanationForToolRequest(tools, req);

        assertTrue(explanation.isBlank());
    }

    @Test
    void getExplanationForToolRequest_ToolNotFound() {
        var req = ToolExecutionRequest.builder()
                .name("noSuchTool")
                .arguments("{}")
                .build();

        // Should NOT throw - validation errors return empty string (details logged only)
        String explanation = registry.getExplanationForToolRequest(tools, req);

        assertTrue(explanation.isBlank());
    }

    // Build a JSON args string using actual parameter names as seen by reflection,
    // to be robust regardless of -parameters compiler flag.
    private static String jsonArgs(Method m, Object... values) throws JsonProcessingException {
        Parameter[] ps = m.getParameters();
        assertEquals(ps.length, values.length, "value count mismatch");
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < ps.length; i++) {
            map.put(ps[i].getName(), values[i]);
        }
        return MAPPER.writeValueAsString(map);
    }

    // Local tool provider for testing
    static class TestTools {
        @Tool("Fetch class sources for testing")
        public String getClassSources(@P("classes") List<String> classNames, @P("reason") String reasoning) {
            return "ok";
        }

        @Tool("Non-list tool")
        public String getCallGraphTo(@P("method") String methodName, @P("depth") int depth) {
            return "ok";
        }

        @Tool("List of ints")
        public String listInts(@P("ids") List<Integer> ids, @P("reason") String reason) {
            return "ok";
        }

        @Tool("Two lists")
        public String multiList(@P("a") List<String> a, @P("b") List<String> b) {
            return "ok";
        }
    }
}
