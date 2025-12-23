package ai.brokk.agents;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests verifying SearchAgent exposes and references createOrReplaceTaskList
 * tool correctly.
 *
 * Note: These tests require a full ContextManager with project infrastructure and LLM access.
 * They are marked @Disabled pending proper test infrastructure setup.
 */
@Disabled("Requires full ContextManager, project infrastructure, and LLM service access")
public class SearchAgentTaskListIntegrationTest {

    @Test
    void searchAgent_exposes_createOrReplaceTaskList_inAllowedTools() {
        // Requires full SearchAgent instantiation with ContextManager
        // See SearchAgent.calculateAllowedToolNames() which includes the tool
    }

    @Test
    void searchAgent_categorizesTool_bothTaskListToolsAsTerminal() {
        // Requires full SearchAgent instantiation with ContextManager
        // createOrReplaceTaskList is categorized as TERMINAL
    }

    @Test
    void searchAgent_assignsPriority_bothTaskListToolsAtSamePriority() {
        // Requires full SearchAgent instantiation with ContextManager
        // The tool has priority 100 (see SearchAgent.priority() method)
    }

    @Test
    void searchAgent_lutzObjective_allowsTaskListTerminals() {
        // Requires full SearchAgent instantiation with ContextManager
        // LUTZ objective exposes createOrReplaceTaskList
    }

    @Test
    void searchAgent_tasksOnlyObjective_allowsTaskListTerminals() {
        // Requires full SearchAgent instantiation with ContextManager
        // TASKS_ONLY objective exposes createOrReplaceTaskList
    }
}
