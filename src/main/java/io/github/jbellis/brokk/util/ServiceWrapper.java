package io.github.jbellis.brokk.util;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Service;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class ServiceWrapper {
    @Nullable private volatile CompletableFuture<Service> future = null;

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
    public StreamingChatLanguageModel getModel(String modelName, Service.ReasoningLevel reasoning) {
        return get().getModel(modelName, reasoning);
    }

    public StreamingChatLanguageModel quickModel() {
        return get().quickModel();
    }

    public String nameOf(StreamingChatLanguageModel model) {
        return get().nameOf(model);
    }

    public StreamingChatLanguageModel quickestModel() {
        return get().quickestModel();
    }
}
