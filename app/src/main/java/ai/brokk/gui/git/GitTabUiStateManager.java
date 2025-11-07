package ai.brokk.gui.git;

import java.awt.Component;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Encapsulates common UI state management patterns for Git-related tabs (Issues and Pull Requests).
 * Provides centralized methods for displaying errors, managing control state, and toggling cell renderers.
 */
public final class GitTabUiStateManager {
    private static final Logger logger = LogManager.getLogger(GitTabUiStateManager.class);

    private GitTabUiStateManager() {}

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

        // Clear and add single error row
        tableModel.setRowCount(0);
        tableModel.addRow(errorRow);

        // Disable reload controls and perform cleanup
        disableControlsCallback.run();

        logger.debug("Displayed error in table");
    }

    /**
     * Enables or disables a set of UI components.
     * Must be called on the EDT.
     *
     * @param enabled Whether to enable or disable the controls
     * @param controls The components to enable/disable
     */
    public static void setReloadControlsEnabled(boolean enabled, Component... controls) {
        assert SwingUtilities.isEventDispatchThread();

        for (Component control : controls) {
            if (control != null) {
                control.setEnabled(enabled);
            }
        }

        logger.debug("Set {} UI controls to enabled={}", controls.length, enabled);
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

        logger.debug("Set title renderer to {} for column {}", useRichRenderer ? "rich" : "simple", columnIndex);
    }
}
