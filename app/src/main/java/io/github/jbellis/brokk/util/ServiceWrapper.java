package io.github.jbellis.brokk.util;

import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Service;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jetbrains.annotations.Nullable;

public class ServiceWrapper {
    @Nullable
    private volatile CompletableFuture<Service> future = null;

    public void reinit(IProject project) {
        future = CompletableFuture.supplyAsync(() -> new Service(project));
    }

    public Service get() {
        CompletableFuture<Service> currentFuture = future; // Local copy for thread safety
        if (currentFuture == null) {
            throw new IllegalStateException("ServiceWrapper not initialized. Call reinit() first.");
        }
        try {
            return currentFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
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

    public String nameOf(StreamingChatModel model) {
        return get().nameOf(model);
    }

    public StreamingChatModel quickestModel() {
        return get().quickestModel();
    }
}
