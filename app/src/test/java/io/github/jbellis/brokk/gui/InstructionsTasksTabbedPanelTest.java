package io.github.jbellis.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

@DisabledIf("java.awt.GraphicsEnvironment#isHeadless")
class InstructionsTasksTabbedPanelTest {

    @Test
    void actionButton_whenInstructionsTabActive_callsInstructionsPanelMethod() throws Exception {
        var wrapper = new Object() {
            boolean called = false;
        };
        
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var chrome = new TestChrome();
            var spyInstructionsPanel = new SpyInstructionsPanel(chrome);
            var tabbedPanel = new InstructionsTasksTabbedPanel(chrome, spyInstructionsPanel);
            
            tabbedPanel.selectInstructionsTab();
            tabbedPanel.onActionButtonPressed();
            
            wrapper.called = spyInstructionsPanel.onActionButtonPressedCalled;
        });
        
        assertTrue(wrapper.called, "InstructionsPanel.onActionButtonPressed should be called");
    }

    // Commented out until we can inject a spy for TaskListPanel
    // @Test
    // void actionButton_whenTasksTabActive_callsTaskListPanelMethod() {
    //     var testPanel = new TestInstructionsTasksTabbedPanel();
    //     
    //     testPanel.selectTasksTab();
    //     testPanel.onActionButtonPressed();
    //     
    //     assertFalse(testPanel.instructionsPanelCalled, "InstructionsPanel.onActionButtonPressed should not be called");
    //     assertTrue(testPanel.taskListPanelCalled, "TaskListPanel.runArchitectOnAll should be called");
    // }

    @Test
    void actionButton_defaultTab_callsInstructionsPanelMethod() throws Exception {
        var wrapper = new Object() {
            boolean called = false;
        };
        
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            var chrome = new TestChrome();
            var spyInstructionsPanel = new SpyInstructionsPanel(chrome);
            var tabbedPanel = new InstructionsTasksTabbedPanel(chrome, spyInstructionsPanel);
            
            tabbedPanel.onActionButtonPressed();
            
            wrapper.called = spyInstructionsPanel.onActionButtonPressedCalled;
        });
        
        assertTrue(wrapper.called, "InstructionsPanel.onActionButtonPressed should be called by default");
    }

    private static class SpyInstructionsPanel extends InstructionsPanel {
        boolean onActionButtonPressedCalled = false;

        SpyInstructionsPanel(Chrome chrome) {
            super(chrome, null);
        }

        @Override
        public void onActionButtonPressed() {
            onActionButtonPressedCalled = true;
        }
    }

    @SuppressWarnings("NullAway")
    private static class TestChrome extends Chrome {
        TestChrome() {
            super(null);
        }
    }
}
