package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.RepoFile;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Interface for context manager functionality
 */
public interface IContextManager {
    default RepoFile toFile(String relName) {
        throw new UnsupportedOperationException();
    }

    default Set<RepoFile> getEditableFiles() {
        throw new UnsupportedOperationException();
    }

    default <T> Future<T> submitBackgroundTask(String taskDescription, Callable<T> task) {
        try {
            return CompletableFuture.completedFuture(task.call());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    default void replaceContext(Context newContext, Context replacement) {
        // no-op
    }

    default void addToHistory(List<ChatMessage> messages, Map<RepoFile, String> originalContents, String action) {
    }

    default IAnalyzer getAnalyzer() {
        return getProject().getAnalyzer();
    }

    default IAnalyzer getAnalyzerNonBlocking() {
        return getProject().getAnalyzerNonBlocking();
    }

    default IGitRepo getRepo() {
        return getProject().getRepo();
    }

    default void editFiles(Collection<RepoFile> path) {
        throw new UnsupportedOperationException();
    }

    default Project getProject() {
        return null;
    }

    default void addToGit(String string) throws IOException {}
}
