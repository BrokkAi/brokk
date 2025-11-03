package ai.brokk.gui.dependencies;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.WorkspacePanel;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Lightweight host abstraction that exposes the handful of operations DependenciesPanel needs from Chrome.
 *
 * This allows tests to provide a small test-specific implementation (see test-only
 * {@code TestDependenciesHost}) while production code uses {@link DefaultDependenciesHost} which
 * delegates to {@link Chrome}.
 */
public interface DependenciesHost {
    IProject getProject();

    ContextManager getContextManager();

    JFrame getFrame();

    int showConfirmDialog(@Nullable Component parent, String message, String title, int optionType, int messageType);

    void showNotification(IConsoleIO.NotificationRole role, String message);

    void toolError(String msg, String title);

    WorkspacePanel getContextPanel();

    /**
     * Production adapter that delegates to Chrome.
     */
    final class DefaultDependenciesHost implements DependenciesHost {
        private final Chrome chrome;

        public DefaultDependenciesHost(Chrome chrome) {
            this.chrome = chrome;
        }

        @Override
        public IProject getProject() {
            // Prefer context manager's project for stability
            return chrome.getContextManager().getProject();
        }

        @Override
        public ContextManager getContextManager() {
            return chrome.getContextManager();
        }

        @Override
        public JFrame getFrame() {
            return chrome.getFrame();
        }

        @Override
        public int showConfirmDialog(@Nullable Component parent, String message, String title, int optionType, int messageType) {
            // Delegate to standard JOptionPane using Chrome's frame as owner when parent is null
            Component owner = parent != null ? parent : chrome.getFrame();
            return JOptionPane.showConfirmDialog(owner, message, title, optionType, messageType);
        }

        @Override
        public void showNotification(IConsoleIO.NotificationRole role, String message) {
            // Chrome implements IConsoleIO; delegate
            chrome.showNotification(role, message);
        }

        @Override
        public void toolError(String msg, String title) {
            chrome.toolError(msg, title);
        }

        @Override
        public WorkspacePanel getContextPanel() {
            return chrome.getContextPanel();
        }

        /**
         * Expose the underlying Chrome instance for code that requires direct access (production compatibility).
         * Tests should prefer providing their own DependenciesHost implementation instead of relying on this.
         */
        public Chrome getChrome() {
            return chrome;
        }
    }
}
