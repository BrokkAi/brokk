package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests verifying SearchAgent exposes and references createOrReplaceTaskList
 * tool correctly, and tests for the lazy-prescan feature (PR #2203).
 *
 * Some tests require a full ContextManager with project infrastructure and LLM access.
 * These are marked @Disabled pending proper test infrastructure setup.
 */
public class SearchAgentTaskListIntegrationTest {

    @Test
    void scanConfig_factoryMethods_produceCorrectConfigurations() {
        // Test defaults()
        var defaults = SearchAgent.ScanConfig.defaults();
        assertTrue(defaults.autoScan(), "defaults() should have autoScan=true");
        assertNull(defaults.scanModel(), "defaults() should have scanModel=null");
        assertTrue(defaults.appendToScope(), "defaults() should have appendToScope=true");

        // Test disabled()
        var disabled = SearchAgent.ScanConfig.disabled();
        assertFalse(disabled.autoScan(), "disabled() should have autoScan=false");
        assertNull(disabled.scanModel(), "disabled() should have scanModel=null");
        assertTrue(disabled.appendToScope(), "disabled() should have appendToScope=true");

        // Test noAppend()
        var noAppend = SearchAgent.ScanConfig.noAppend();
        assertTrue(noAppend.autoScan(), "noAppend() should have autoScan=true");
        assertNull(noAppend.scanModel(), "noAppend() should have scanModel=null");
        assertFalse(noAppend.appendToScope(), "noAppend() should have appendToScope=false");
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_exposes_createOrReplaceTaskList_inAllowedTools() {
        // Requires full SearchAgent instantiation with ContextManager
        // See SearchAgent.calculateAllowedToolNames() which includes the tool
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_categorizesTool_bothTaskListToolsAsTerminal() {
        // Requires full SearchAgent instantiation with ContextManager
        // createOrReplaceTaskList is categorized as TERMINAL
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_assignsPriority_bothTaskListToolsAtSamePriority() {
        // Requires full SearchAgent instantiation with ContextManager
        // The tool has priority 100 (see SearchAgent.priority() method)
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_lutzObjective_allowsTaskListTerminals() {
        // Requires full SearchAgent instantiation with ContextManager
        // LUTZ objective exposes createOrReplaceTaskList
    }

    @Test
    @Disabled("Requires full SearchAgent instantiation with ContextManager")
    void searchAgent_tasksOnlyObjective_allowsTaskListTerminals() {
        // Requires full SearchAgent instantiation with ContextManager
        // TASKS_ONLY objective exposes createOrReplaceTaskList
    }
}
