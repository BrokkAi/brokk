package ai.brokk.gui.dependencies;

import ai.brokk.IConsoleIO;
import ai.brokk.IProject;
import ai.brokk.ContextManager;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.WorkspacePanel;
import java.awt.Component;
import javax.swing.JFrame;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight host abstraction used by DependenciesPanel to avoid depending on the full Chrome surface
 * in tests. The DefaultDependenciesHost wraps a real Chrome instance for production usage.
 */
public interface DependenciesHost {
    IProject getProject();
    ContextManager getContextManager();
    JFrame getFrame();
    int showConfirmDialog(
            @Nullable Component parent, String message, String title, int optionType, int messageType);
    void showNotification(IConsoleIO.NotificationRole role, String message);
    void toolError(String msg, String title);
    WorkspacePanel getContextPanel();

    /**
     * Production implementation backed by a Chrome instance.
     */
    class DefaultDependenciesHost implements DependenciesHost {
        private final Chrome chrome;

        public DefaultDependenciesHost(Chrome chrome) {
            this.chrome = chrome;
        }

        @Override
        public IProject getProject() {
            return chrome.getProject();
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
        public int showConfirmDialog(
                @Nullable Component parent, String message, String title, int optionType, int messageType) {
            return chrome.showConfirmDialog(parent, message, title, optionType, messageType);
        }

        @Override
        public void showNotification(IConsoleIO.NotificationRole role, String message) {
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
         * Expose the wrapped Chrome for callers that need the concrete (e.g. launching legacy dialogs).
         * Tests should prefer using the DependenciesHost API methods instead of calling this.
         */
        public Chrome getChrome() {
            return chrome;
        }
    }
}
