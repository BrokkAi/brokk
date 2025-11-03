package ai.brokk.gui;

import ai.brokk.IConsoleIO;
import ai.brokk.exception.GlobalExceptionHandler;
import java.util.concurrent.ExecutionException;
import javax.swing.SwingWorker;

/**
 * SwingWorker that routes exceptions to GlobalExceptionHandler and surfaces an ERROR notification
 * via the provided IConsoleIO. Use this instead of raw SwingWorker to ensure consistent error UX.
 */
public abstract class ExceptionAwareSwingWorker<T, V> extends SwingWorker<T, V> {
    private final IConsoleIO io;

    protected ExceptionAwareSwingWorker(IConsoleIO io) {
        this.io = io;
    }

    protected final IConsoleIO io() {
        return io;
    }

    @Override
    protected void done() {
        try {
            // Consume result to trigger any exception from doInBackground
            get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            GlobalExceptionHandler.handle(
                    Thread.currentThread(), ie, st -> io.showNotification(IConsoleIO.NotificationRole.ERROR, st));
        } catch (ExecutionException ee) {
            Throwable cause = (ee.getCause() != null) ? ee.getCause() : ee;
            GlobalExceptionHandler.handle(
                    Thread.currentThread(), cause, st -> io.showNotification(IConsoleIO.NotificationRole.ERROR, st));
        }
    }
}
