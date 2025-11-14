package ai.brokk.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class TuiControllerRoutingTest {

    private static final class FakeTuiView implements TuiView {
        boolean chipsToggled;
        boolean tasksToggled;
        boolean shutdownCalled;
        Boolean lastTaskInProgress;

        @Override
        public void toggleChipPanel() {
            chipsToggled = true;
        }

        @Override
        public void toggleTaskList() {
            tasksToggled = true;
        }

        @Override
        public void setTaskInProgress(boolean progress) {
            lastTaskInProgress = progress;
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }
    }

    @Test
    void routesSlashCommands() {
        var input = new ByteArrayInputStream("/chips\n/tasks\n/quit\n".getBytes());
        var outBytes = new ByteArrayOutputStream();
        var out = new PrintStream(outBytes);
        var fakeView = new FakeTuiView();

        var controller = new TuiController(fakeView, new InputStreamReader(input), out);
        controller.run();

        var output = outBytes.toString();
        assertTrue(output.contains("Brokk TUI - interactive mode"), "welcome banner");
        assertTrue(output.contains("[TUI] Chip panel toggled."), "chips toggle message");
        assertTrue(output.contains("[TUI] Task list toggled."), "tasks toggle message");
        assertTrue(output.contains("[TUI] Exiting..."), "exit message");
        assertTrue(fakeView.chipsToggled, "chips toggled recorded");
        assertTrue(fakeView.tasksToggled, "tasks toggled recorded");
        assertTrue(fakeView.shutdownCalled, "shutdown called");
    }
}
