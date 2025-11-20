package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.context.Context;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests verifying SearchAgent exposes and references createOrReplaceTaskList
 * and appendTaskList tools correctly.
 * 
 * Note: These tests require a full ContextManager with project infrastructure and LLM access.
 * They are marked @Disabled pending proper test infrastructure setup.
 */
@Disabled("Requires full ContextManager, project infrastructure, and LLM service access")
public class SearchAgentTaskListIntegrationTest {

    @Test
    void searchAgent_exposes_createOrReplaceTaskList_inAllowedTools() {
        // Requires full SearchAgent instantiation with ContextManager
        // See SearchAgent.calculateAllowedToolNames() which includes both tools
    }

    @Test
    void searchAgent_exposes_appendTaskList_inAllowedTools() {
        // Requires full SearchAgent instantiation with ContextManager
        // See SearchAgent.calculateAllowedToolNames() which includes both tools
    }

    @Test
    void searchAgent_categorizesTool_bothTaskListToolsAsTerminal() {
        // Requires full SearchAgent instantiation with ContextManager
        // Both createOrReplaceTaskList and appendTaskList are categorized as TERMINAL
    }

    @Test
    void searchAgent_assignsPriority_bothTaskListToolsAtSamePriority() {
        // Requires full SearchAgent instantiation with ContextManager
        // Both tools have priority 100 (see SearchAgent.priority() method)
    }

    @Test
    void searchAgent_lutzObjective_allowsTaskListTerminals() {
        // Requires full SearchAgent instantiation with ContextManager
        // LUTZ objective exposes both createOrReplaceTaskList and appendTaskList
    }

    @Test
    void searchAgent_tasksOnlyObjective_allowsTaskListTerminals() {
        // Requires full SearchAgent instantiation with ContextManager
        // TASKS_ONLY objective exposes both createOrReplaceTaskList and appendTaskList
    }
}
