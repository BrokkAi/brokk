package ai.brokk.gui.git;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.kohsuke.github.HttpException;

/**
 * Static utilities for error handling in Git-related tabs (Issues and Pull Requests).
 */
public interface GitTabErrorUtil {

    /**
     * Displays an error message in the table and disables UI controls.
     * Clears the table, adds a single error row, and disables reload controls via the provided callback.
     *
     * @param tableModel The DefaultTableModel backing the table
     * @param errorRow The row data to add (array of column values)
     * @param disableControlsCallback Callback to disable reload controls and clear details (called on EDT)
     */
    static void showError(DefaultTableModel tableModel, Object[] errorRow, Runnable disableControlsCallback) {
        assert SwingUtilities.isEventDispatchThread();

        tableModel.setRowCount(0);
        tableModel.addRow(errorRow);
        disableControlsCallback.run();
    }

    /**
     * Maps an exception to a user-friendly error message.
     * Handles HttpException with status code branching (401, 403, 404),
     * as well as UnknownHostException, SocketTimeoutException, ConnectException, and IOException.
     *
     * @param ex The exception to map
     * @return A user-facing error message
     */
    static String mapExceptionToUserMessage(Exception ex) {
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
        } else {
            var message = ex.getMessage();
            return message != null ? message : "An unexpected error occurred.";
        }
    }
}
