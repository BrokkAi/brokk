package ai.brokk.util;

import ai.brokk.IProject;
import ai.brokk.Service;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jetbrains.annotations.Nullable;

public class ServiceWrapper {
    @Nullable
    private volatile CompletableFuture<Service> future = null;

    @Nullable
    private volatile Throwable lastInitializationError = null;

    public void reinit(IProject project) {
        lastInitializationError = null; // Clear previous error
        future = CompletableFuture.supplyAsync(() -> new Service(project))
                .exceptionally(ex -> {
                    lastInitializationError = ex;
                    // Re-throw the exception to propagate the failure
                    throw ex instanceof RuntimeException runtimeException ? runtimeException : new RuntimeException(ex);
                });
    }

    public Service get() {
        CompletableFuture<Service> currentFuture = future; // Local copy for thread safety
        if (currentFuture == null) {
            throw new IllegalStateException("ServiceWrapper not initialized. Call reinit() first.");
        }
        try {
            Service result = currentFuture.get();
            lastInitializationError = null; // Clear error on successful completion
            return result;
        } catch (InterruptedException | ExecutionException e) {
            throw new ServiceInitializationException(e);
        }
    }

    @Nullable
    public StreamingChatModel getModel(Service.ModelConfig config) {
        Service service = get();
        return service.getModel(config);
    }

    public StreamingChatModel quickModel() {
        return get().quickModel();
    }

    /**
     * Returns the last initialization error captured from a failed future, or null if the last
     * initialization succeeded. This can help distinguish configuration errors (e.g., invalid proxy URL,
     * bad API key) from transient network failures.
     *
     * @return The last initialization error, or null if initialization succeeded.
     */
    @Nullable
    public Throwable getLastInitializationError() {
        return lastInitializationError;
    }

    public static class ServiceInitializationException extends RuntimeException {
        public ServiceInitializationException(Exception e) {
            super(e);
        }
    }
}
