package io.github.jbellis.brokk.util;

import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class ServiceWrapper {
    private volatile CompletableFuture<Service> future;

    public void reinit(IProject project) {
        future = CompletableFuture.supplyAsync(() -> new Service(project));
    }

    public Service get() {
        try {
            return future.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

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
