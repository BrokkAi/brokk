package ai.brokk.util;

import ai.brokk.IProject;
import ai.brokk.Service;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jetbrains.annotations.Nullable;

public class ServiceWrapper implements Service.Provider {
    @Nullable
    private volatile CompletableFuture<Service> future = null;

    @Override
    public void reinit(IProject project) {
        future = CompletableFuture.supplyAsync(() -> new Service(project));
    }

    @Override
    public Service get() {
        CompletableFuture<Service> currentFuture = future; // Local copy for thread safety
        if (currentFuture == null) {
            throw new IllegalStateException("ServiceWrapper not initialized. Call reinit() first.");
        }
        try {
            return currentFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new ServiceInitializationException(e);
        }
    }

    public static class ServiceInitializationException extends RuntimeException {
        public ServiceInitializationException(Exception e) {
            super(e);
        }
    }
}
