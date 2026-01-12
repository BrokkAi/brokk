package ai.brokk.gui;

import ai.brokk.IConsoleIO;
import ai.brokk.util.ReviewParser;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import org.jspecify.annotations.NullMarked;

/**
 * Service for handling high-level actions related to code reviews,
 * such as exporting to clipboard.
 */
@NullMarked
public class ReviewActions {
    private final IConsoleIO consoleIO;

    public ReviewActions(IConsoleIO consoleIO) {
        this.consoleIO = consoleIO;
    }

    /**
     * Exports the given review to the system clipboard as a tool request.
     */
    public void copyReviewToClipboard(ReviewParser.GuidedReview review) {
        var export = review.toExport();
        var toolRequest = export.toToolRequest();

        var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(toolRequest), null);
        consoleIO.showNotification(IConsoleIO.NotificationRole.INFO, "Review copied to clipboard");
    }

    /**
     * Notifies the user that there is no review available to copy.
     */
    public void notifyNoReviewToCopy() {
        consoleIO.showNotification(IConsoleIO.NotificationRole.INFO, "No review to copy");
    }
}
