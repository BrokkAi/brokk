package io.github.jbellis.brokk;

import dev.langchain4j.data.message.ChatMessage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface IContextManager {
    RepoFile toFile(String relName);

    void addToHistory(List<ChatMessage> messages, Map<RepoFile, String> originalContents);

    Set<RepoFile> getEditableFiles();

    void addFiles(Collection<RepoFile> path);

    Set<RepoFile> findMissingFileMentions(String text);

    default Project getProject() {
        return null;
    }
}
