package ai.brokk.executor.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.TestProject;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentDefinitionTest {

    private static AgentDefinition validDef() {
        return new AgentDefinition(
                "security-auditor",
                "Reviews code for vulnerabilities",
                List.of("searchSymbols", "scanUsages"),
                15,
                "You are a security auditor.",
                "project");
    }

    @Test
    void validate_validDefinition_noErrors() {
        var errors = validDef().validate();
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }

    @Test
    void validate_blankName_returnsError() {
        var def = new AgentDefinition("", "desc", null, null, "prompt", "project");
        var errors = def.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("name is required")));
    }

    @Test
    void validate_invalidNamePattern_returnsError() {
        var def = new AgentDefinition("Bad-Name", "desc", null, null, "prompt", "project");
        var errors = def.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("name must match")));
    }

    @Test
    void validate_nameStartingWithDigit_returnsError() {
        var def = new AgentDefinition("1agent", "desc", null, null, "prompt", "project");
        var errors = def.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("name must match")));
    }

    @Test
    void validate_validNameWithHyphensAndDigits() {
        var def = new AgentDefinition("my-agent-2", "desc", null, null, "prompt", "project");
        assertTrue(def.validate().isEmpty());
    }

    @Test
    void validate_blankDescription_returnsError() {
        var def = new AgentDefinition("test", "", null, null, "prompt", "project");
        var errors = def.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("description is required")));
    }

    @Test
    void validate_blankSystemPrompt_returnsError() {
        var def = new AgentDefinition("test", "desc", null, null, "", "project");
        var errors = def.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("systemPrompt is required")));
    }

    @Test
    void validate_unknownTool_returnsError() {
        var def = new AgentDefinition("test", "desc", List.of("nonexistentTool"), null, "prompt", "project");
        var errors = def.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown tool: nonexistentTool")));
    }

    @Test
    void validate_codeQualityTools_secretAndTestSmellTools_areKnown() {
        var def = new AgentDefinition(
                "test",
                "desc",
                List.of("reportSecretLikeCode", "reportTestAssertionSmells"),
                null,
                "prompt",
                "project");
        assertTrue(def.validate().isEmpty(), "Expected no errors but got: " + def.validate());
        assertTrue(AgentDefinition.READ_ONLY_TOOL_NAMES.contains("reportSecretLikeCode"));
        assertTrue(AgentDefinition.READ_ONLY_TOOL_NAMES.contains("reportTestAssertionSmells"));
        assertTrue(AgentDefinition.PARALLEL_SAFE_SEARCH_TOOL_NAMES.contains("reportSecretLikeCode"));
        assertTrue(AgentDefinition.PARALLEL_SAFE_SEARCH_TOOL_NAMES.contains("reportTestAssertionSmells"));
    }

    @Test
    void validate_sourceInspectionTools_areReadOnlyAndParallelSafe() {
        var sourceInspectionTools = List.of(
                "getFileContents",
                "listFiles",
                "getSummaries",
                "getClassSkeletons",
                "getClassSources",
                "getMethodSources");
        for (var tool : sourceInspectionTools) {
            assertTrue(AgentDefinition.READ_ONLY_TOOL_NAMES.contains(tool), tool);
            assertTrue(AgentDefinition.PARALLEL_SAFE_SEARCH_TOOL_NAMES.contains(tool), tool);
        }
    }

    @Test
    void isReadOnly_sourceInspectionAndCodeQualityTools_returnsTrue(@TempDir Path root) {
        var def = new AgentDefinition(
                "code-quality-complexity",
                "Reviews code quality",
                List.of(
                        "getFileContents",
                        "computeCyclomaticComplexity",
                        "computeCognitiveComplexity",
                        "reportLongMethodAndGodObjectSmells",
                        "reportExceptionHandlingSmells",
                        "reportStructuralCloneSmells",
                        "reportSecretLikeCode",
                        "reportTestAssertionSmells"),
                null,
                "You are a code quality reviewer.",
                "project");

        assertTrue(def.validate().isEmpty(), "Expected no errors but got: " + def.validate());
        assertTrue(def.isReadOnly(new TestProject(root, Languages.JAVA)));
    }

    @Test
    void validate_negativeMaxTurns_returnsError() {
        var def = new AgentDefinition("test", "desc", null, -1, "prompt", "project");
        var errors = def.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("maxTurns must be positive")));
    }

    @Test
    void validate_zeroMaxTurns_returnsError() {
        var def = new AgentDefinition("test", "desc", null, 0, "prompt", "project");
        var errors = def.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("maxTurns must be positive")));
    }

    @Test
    void effectiveMaxTurns_usesDefault_whenNull() {
        var def = new AgentDefinition("test", "desc", null, null, "prompt", "project");
        assertEquals(AgentDefinition.DEFAULT_MAX_TURNS, def.effectiveMaxTurns());
    }

    @Test
    void effectiveMaxTurns_usesProvided_whenSet() {
        var def = new AgentDefinition("test", "desc", null, 5, "prompt", "project");
        assertEquals(5, def.effectiveMaxTurns());
    }

    @Test
    void validate_multipleErrors_returnsAll() {
        var def = new AgentDefinition("", "", List.of("badTool"), -1, "", "project");
        var errors = def.validate();
        assertTrue(errors.size() >= 4, "Expected at least 4 errors but got " + errors.size() + ": " + errors);
    }
}
