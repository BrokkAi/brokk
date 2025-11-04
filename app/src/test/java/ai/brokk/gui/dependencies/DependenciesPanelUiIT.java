package ai.brokk.gui.dependencies;

import ai.brokk.MainProject;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.swing.*;
import org.assertj.core.api.Assertions;
import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.Robot;
import org.assertj.swing.edt.FailOnThreadViolationRepaintManager;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.fixture.JTableFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * UI integration test for DependenciesPanel.
 *
 * Verifies that an existing on-disk dependency (pre-created under .brokk/dependencies)
 * is detected and appears in the dependencies table.
 *
 * The test uses an explicit polling await loop to wait for the Swing workers to populate
 * the table model. To make component lookup robust we scan the component hierarchy
 * for the JTable named 'dependenciesTable' (or any JTable if the name is not set).
 */
public class DependenciesPanelUiIT {
    static {
        // Ensure AssertJ catches EDT violations early
        FailOnThreadViolationRepaintManager.install();
    }

    private Robot robot;
    private FrameFixture window;
    private JFrame frame;

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
    }

    @Test
    void loadsExistingDependencyRow() throws Exception {
        // Create a temporary project root and pre-create the dependency folder
        Path root = Files.createTempDirectory("deps-ui-it-");
        Files.createDirectories(root.resolve(".brokk").resolve("dependencies").resolve("existing-comp"));

        // Instantiate MainProject for this root
        MainProject project = new MainProject(root);

        // Create robot BEFORE creating any AWT windows so they are attached to the robot-managed AWT hierarchy
        robot = BasicRobot.robotWithNewAwtHierarchy();

        // Build UI on the EDT (after robot creation so the frame is part of the robot's hierarchy)
        frame = GuiActionRunner.execute(() -> {
            var host = new TestDependenciesHost(project);
            var panel = new DependenciesPanel(host);
            var f = new JFrame("DependenciesPanel IT");
            f.setName("depsTestFrame");
            f.getContentPane().add(panel);
            f.pack();
            f.setLocationRelativeTo(null);
            return f;
        });

        // Create fixture and show the frame under the robot-managed hierarchy
        window = new FrameFixture(robot, frame);
        window.show();

        // Nudge the UI to settle
        GuiActionRunner.execute(() -> {
            frame.validate();
            frame.repaint();
            return null;
        });
        robot.waitForIdle();
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
        }

        // Find table by scanning component hierarchy (prefer name 'dependenciesTable')
        javax.swing.JTable rawTable = GuiActionRunner.execute(() -> findDependenciesTable(frame.getContentPane()));

        // If not found yet, poll for up to ~10s, revalidating/repainting in between
        long compDeadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (rawTable == null && System.currentTimeMillis() < compDeadline) {
            robot.waitForIdle();
            GuiActionRunner.execute(() -> {
                frame.validate();
                frame.repaint();
                return null;
            });
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            rawTable = GuiActionRunner.execute(() -> findDependenciesTable(frame.getContentPane()));
        }

        Assertions.assertThat(rawTable)
                .as("dependencies table should exist in component hierarchy")
                .isNotNull();

        JTableFixture table = new JTableFixture(robot, rawTable);

        // Explicit await/poll loop waiting for the table to contain the expected dependency.
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        boolean found = false;
        while (System.currentTimeMillis() < deadline) {
            robot.waitForIdle();
            try {
                int rowCount = table.target().getRowCount();
                if (rowCount > 0) {
                    for (int i = 0; i < rowCount; i++) {
                        Object val = table.target().getValueAt(i, 1); // Name column (model index 1)
                        if (val != null && "existing-comp".equals(val.toString())) {
                            found = true;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            if (found) break;
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }

        Assertions.assertThat(found)
                .as("table should contain existing-comp row")
                .isTrue();
    }

    // Recursively collect all components under the given container
    private static java.util.List<java.awt.Component> allComponents(java.awt.Container c) {
        java.util.List<java.awt.Component> result = new java.util.ArrayList<>();
        if (c == null) return result;
        for (java.awt.Component comp : c.getComponents()) {
            result.add(comp);
            if (comp instanceof java.awt.Container cont) {
                result.addAll(allComponents(cont));
            }
        }
        return result;
    }

    // Find the JTable named 'dependenciesTable' if present, otherwise return the first JTable found.
    private static javax.swing.JTable findDependenciesTable(java.awt.Container root) {
        java.util.List<java.awt.Component> comps = allComponents(root);
        javax.swing.JTable firstTable = null;
        for (java.awt.Component comp : comps) {
            if (comp instanceof javax.swing.JTable t) {
                if ("dependenciesTable".equals(t.getName())) {
                    return t;
                }
                if (firstTable == null) firstTable = t;
            }
        }
        return firstTable;
    }
}
