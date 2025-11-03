package ai.brokk;

import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.edt.FailOnThreadViolationRepaintManager;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JCheckBox;
import javax.swing.JPanel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight AssertJ-Swing based tests that:
 * - Install FailOnThreadViolationRepaintManager before any Swing usage (static initializer).
 * - Use BasicRobot / FrameFixture to execute deterministic UI interactions.
 * - Create and modify UI on the EDT via GuiActionRunner.
 *
 * Note: the project's Gradle test task currently sets java.awt.headless=true. This test uses
 * AssertJ Swing's BasicRobot (which works in headless CI); if you prefer visible UI for local debugging,
 * set the test system property java.awt.headless to false before any AWT init (e.g. via a JVM arg).
 */
public class TextNodeMarkerCustomizerTest {
    static {
        // Install FailOnThreadViolationRepaintManager as early as possible to catch EDT violations.
        FailOnThreadViolationRepaintManager.install();
    }

    private Robot robot;
    private FrameFixture window;
    private JFrame frame;
    private JPanel panel;

    @AfterEach
    void tearDown() {
        if (window != null) {
            try {
                window.cleanUp();
            } catch (Exception ignored) {
            } finally {
                window = null;
            }
        }
        if (robot != null) {
            try {
                robot.cleanUp();
            } catch (Exception ignored) {
            } finally {
                robot = null;
            }
        }
        frame = null;
        panel = null;
    }

    /**
     * Simulates: existing dependency is checked, then a new dependency is added (should be checked by default).
     * Verifies existing checkbox state is unchanged and new dependency is selected.
     */
    @Test
    void addingNewDependencyWhileExistingChecked_doesNotChangeExistingAndNewIsCheckedByDefault() {
        runDependencyScenario(true);
    }

    /**
     * Simulates: existing dependency is unchecked, then a new dependency is added (should be checked by default).
     * Verifies existing checkbox state is unchanged and new dependency is selected.
     */
    @Test
    void addingNewDependencyWhileExistingUnchecked_doesNotChangeExistingAndNewIsCheckedByDefault() {
        runDependencyScenario(false);
    }

    // Helper that builds the UI on the EDT and runs the scenario deterministically with AssertJ Swing
    private void runDependencyScenario(boolean existingInitiallyChecked) {
        // Create robot with new AWT hierarchy (works in headless CI)
        robot = BasicRobot.robotWithNewAwtHierarchy();

        // Build and show frame on the EDT
        frame = GuiActionRunner.execute(() -> {
            var f = new JFrame("Dependencies Test Frame");
            panel = new JPanel();
            // existing dependency checkbox
            var existing = new JCheckBox("Existing Dependency", existingInitiallyChecked);
            existing.setName("existingDep");
            panel.add(existing);
            f.getContentPane().add(panel);
            f.pack();
            f.setLocationRelativeTo(null);
            return f;
        });

        // Wrap with a fixture for deterministic interaction (shows the frame)
        window = new FrameFixture(robot, frame);
        window.show(); // ensures visible in robot-managed context

        // Verify initial state of existing dependency
        if (existingInitiallyChecked) {
            window.checkBox("existingDep").requireSelected();
        } else {
            window.checkBox("existingDep").requireNotSelected();
        }

        // Simulate adding a new dependency on the EDT; new dependency should be checked by default
        GuiActionRunner.execute(() -> {
            var newDep = new JCheckBox("New Dependency", true);
            newDep.setName("newDep");
            panel.add(newDep);
            frame.pack();
            frame.revalidate();
            frame.repaint();
            return null;
        });

        // Wait for any idle events
        robot.waitForIdle();

        // Assert existing dependency state unchanged
        if (existingInitiallyChecked) {
            window.checkBox("existingDep").requireSelected();
        } else {
            window.checkBox("existingDep").requireNotSelected();
        }

        // Assert new dependency is checked by default
        window.checkBox("newDep").requireSelected();

        // Additionally assert programmatically as an extra verification
        boolean existingState = window.checkBox("existingDep").target().isSelected();
        boolean newState = window.checkBox("newDep").target().isSelected();
        assertThat(existingState).isEqualTo(existingInitiallyChecked);
        assertThat(newState).isTrue();
    }
}
