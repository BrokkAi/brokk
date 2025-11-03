package ai.brokk.gui.dependencies;

import ai.brokk.MainProject;
import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.gui.WorkspacePanel;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Minimal test-only DependenciesHost implementation used by UI tests.
 *
 * This class purposefully keeps the workspace panel and frame very small and lightweight.
 * It intentionally returns null for ContextManager because the particular UI tests that use
 * this host exercise only the dependency-loading UI path (which relies on getProject()
 * and getContextPanel()).
 *
 * NOTE: To avoid constructing a WorkspacePanel with a null Chrome (which triggers
 * WorkspacePanel internal initialization that expects a non-null Chrome), this test
 * host intentionally defers creating any WorkspacePanel instance. getContextPanel()
 * returns null which the DependenciesPanel is now resilient to.
 */
public final class TestDependenciesHost implements DependenciesHost {

    private final MainProject project;
    private final JFrame frame;
    // Intentionally null to avoid WorkspacePanel constructor plumbing in tests
    private final WorkspacePanel contextPanel;

    public TestDependenciesHost(MainProject project) {
        this.project = project;
        this.frame = new JFrame("Test Frame");
        this.frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.contextPanel = null; // do not instantiate WorkspacePanel here
    }

    @Override
    public IProject getProject() {
        return project;
    }

    /**
     * Not required for the loading-only tests that use this host.
     * Returning null is acceptable for these tests; if future tests need background
     * task capabilities, replace with a lightweight ContextManager stub.
     */
    @Override
    public ai.brokk.ContextManager getContextManager() {
        return null;
    }

    @Override
    public JFrame getFrame() {
        return frame;
    }

    @Override
    public int showConfirmDialog(@Nullable Component parent, String message, String title, int optionType, int messageType) {
        // Deterministic: always say YES in tests that call confirm dialogs.
        return JOptionPane.YES_OPTION;
    }

    @Override
    public void showNotification(IConsoleIO.NotificationRole role, String message) {
        // no-op for tests
    }

    @Override
    public void toolError(String msg, String title) {
        // no-op for tests
    }

    @Override
    public WorkspacePanel getContextPanel() {
        // Intentionally returns null for this lightweight test host.
        return contextPanel;
    }

    /**
     * Retain a DummyWorkspacePanel class for future tests, but we do NOT instantiate it
     * by default in order to avoid WorkspacePanel constructor side-effects in this test suite.
     */
    private static final class DummyWorkspacePanel extends WorkspacePanel {
        // Use the (Chrome, ContextManager) constructor with nulls; this keeps the stub minimal.
        public DummyWorkspacePanel() {
            super(null, null);
        }

        @Override
        public int getBottomControlsPreferredHeight() {
            return 50;
        }

        @Override
        public void addBottomControlsListener(BottomControlsListener l) {
            // no-op for tests
        }

        @Override
        public void removeBottomControlsListener(BottomControlsListener l) {
            // no-op for tests
        }
    }
}
