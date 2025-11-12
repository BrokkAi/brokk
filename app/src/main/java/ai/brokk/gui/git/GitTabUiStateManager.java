package ai.brokk.gui.git;

import java.awt.Component;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.HttpException;

/**
 * Encapsulates common UI state management patterns for Git-related tabs (Issues and Pull Requests).
 * Provides centralized methods for displaying errors, managing control state, and toggling cell renderers.
 */
public final class GitTabUiStateManager {

    /**
     * Displays an error message in the table and disables UI controls.
     * Clears the table, adds a single error row, and disables reload controls via the provided callback.
     *
     * @param tableModel The DefaultTableModel backing the table
     * @param errorRow The row data to add (array of column values)
     * @param disableControlsCallback Callback to disable reload controls and clear details (called on EDT)
     */
    public static void showError(DefaultTableModel tableModel, Object[] errorRow, Runnable disableControlsCallback) {
        assert SwingUtilities.isEventDispatchThread();

        tableModel.setRowCount(0);
        tableModel.addRow(errorRow);
        disableControlsCallback.run();
    }

    /**
     * Enables or disables a set of UI components.
     * Must be called on the EDT.
     *
     * @param enabled Whether to enable or disable the controls
     * @param controls The components to enable/disable (null elements are skipped)
     */
    public static void setReloadControlsEnabled(boolean enabled, @Nullable Component... controls) {
        assert SwingUtilities.isEventDispatchThread();

        for (Component control : controls) {
            if (control != null) {
                control.setEnabled(enabled);
            }
        }
    }

    /**
     * Toggles between rich (multi-line) and simple (single-line) cell renderers for a specific table column.
     * Must be called on the EDT.
     *
     * @param table The JTable to update
     * @param columnIndex The column index to update
     * @param richRenderer The renderer to use for normal rows (rich/multi-line)
     * @param simpleRenderer The renderer to use for simple rows (single-line, e.g., error/empty states)
     * @param useRichRenderer true to use the rich renderer, false to use the simple renderer
     */
    public static void setTitleRenderer(
            JTable table,
            int columnIndex,
            TableCellRenderer richRenderer,
            TableCellRenderer simpleRenderer,
            boolean useRichRenderer) {
        assert SwingUtilities.isEventDispatchThread();

        TableCellRenderer renderer = useRichRenderer ? richRenderer : simpleRenderer;
        table.getColumnModel().getColumn(columnIndex).setCellRenderer(renderer);
    }

    /**
     * Sets the error state for a Git tab table, managing row height, renderer, error display, and control state.
     * Must be called on the EDT.
     *
     * @param table The JTable to update
     * @param tableModel The DefaultTableModel backing the table
     * @param titleColumnIndex The index of the title column to update renderer for
     * @param richRenderer The renderer to use for normal rows (rich/multi-line)
     * @param defaultRenderer The renderer to use for error rows (single-line)
     * @param isError true to set error state, false to set normal state
     * @param errorMessage The error message to display (used only if isError is true)
     * @param errorRowData The row data to add when showing error (used only if isError is true)
     * @param onErrorCallback Callback to execute when entering error state (used only if isError is true)
     */
    public static void setErrorState(
            JTable table,
            DefaultTableModel tableModel,
            int titleColumnIndex,
            TableCellRenderer richRenderer,
            TableCellRenderer defaultRenderer,
            boolean isError,
            String errorMessage,
            Object[] errorRowData,
            Runnable onErrorCallback) {
        assert SwingUtilities.isEventDispatchThread();

        if (isError) {
            table.setRowHeight(30);
            setTitleRenderer(table, titleColumnIndex, richRenderer, defaultRenderer, false);
            showError(tableModel, errorRowData, onErrorCallback);
        } else {
            table.setRowHeight(48);
            setTitleRenderer(table, titleColumnIndex, richRenderer, defaultRenderer, true);
        }
    }

    /**
     * Handles a settings change (token or provider) by executing the provided actions in sequence.
     * Executes on the EDT to ensure thread safety.
     *
     * @param resetUiState A Runnable that resets UI state (e.g., clears error flags, enables controls)
     * @param cancelActiveTasks A Runnable that cancels any active tasks/futures
     * @param refreshData A Runnable that refreshes data (may be scheduled asynchronously afterward)
     */
    public void handleProviderOrTokenChange(Runnable resetUiState, Runnable cancelActiveTasks, Runnable refreshData) {
        SwingUtilities.invokeLater(() -> {
            resetUiState.run();
            cancelActiveTasks.run();
            SwingUtilities.invokeLater(refreshData);
        });
    }

    /**
     * Maps an exception to a user-friendly error message.
     * Handles HttpException with status code branching (401, 403, 404),
     * as well as UnknownHostException, SocketTimeoutException, ConnectException, and IOException.
     *
     * @param ex The exception to map
     * @return A user-facing error message
     */
    public String mapExceptionToUserMessage(Exception ex) {
        if (ex instanceof HttpException httpEx) {
            int statusCode = httpEx.getResponseCode();
            return switch (statusCode) {
                case 401 -> "Authentication failed. Please check your GitHub token in Settings.";
                case 403 -> "Access forbidden. Check API rate limit or repository permissions in Settings.";
                case 404 -> "Repository not found. Verify owner/repo in Settings → Project → Issues → GitHub.";
                default -> "GitHub API error (HTTP " + statusCode + "): " + httpEx.getMessage();
            };
        } else if (ex instanceof UnknownHostException) {
            return "Network connection failed. Please check your internet connection.";
        } else if (ex instanceof SocketTimeoutException) {
            return "Request timed out. Please try again or check your network.";
        } else if (ex instanceof ConnectException) {
            return "Request timed out or connection refused. Please try again.";
        } else if (ex instanceof IOException) {
            return "I/O error: " + ex.getMessage();
        } else {
            return "Error: " + ex.getMessage();
        }
    }
}
