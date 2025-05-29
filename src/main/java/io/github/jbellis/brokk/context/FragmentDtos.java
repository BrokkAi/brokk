package io.github.jbellis.brokk.context;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;

import java.util.List;
import java.util.Set;

/**
 * Sealed interfaces and records for fragment DTOs with Jackson polymorphic support.
 */
public class FragmentDtos {
    
    /**
     * Sealed interface for path-based fragments (files).
     */
    @JsonTypeInfo(use = Id.CLASS, include = As.PROPERTY, property = "type")
    public sealed interface PathFragmentDto permits ProjectFileDto, ExternalFileDto, ImageFileDto, GitFileFragmentDto {
    }
    
    /**
     * Sealed interface for virtual fragments (non-file content).
     */
    @JsonTypeInfo(use = Id.CLASS, include = As.PROPERTY, property = "type")
    public sealed interface VirtualFragmentDto permits TaskFragmentDto, StringFragmentDto, SearchFragmentDto, SkeletonFragmentDto, UsageFragmentDto, PasteTextFragmentDto, PasteImageFragmentDto, StacktraceFragmentDto, CallGraphFragmentDto, HistoryFragmentDto {
    }
    
    /**
     * DTO for ProjectFile - contains root and relative path as strings.
     */
    public record ProjectFileDto(int id, String repoRoot, String relPath) implements PathFragmentDto {
        public ProjectFileDto {
            if (repoRoot == null || repoRoot.isEmpty()) {
                throw new IllegalArgumentException("repoRoot cannot be null or empty");
            }
            if (relPath == null || relPath.isEmpty()) {
                throw new IllegalArgumentException("relPath cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for ExternalFile - contains absolute path as string.
     */
    public record ExternalFileDto(int id, String absPath) implements PathFragmentDto {
        public ExternalFileDto {
            if (absPath == null || absPath.isEmpty()) {
                throw new IllegalArgumentException("absPath cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for ImageFile - contains absolute path and media type.
     */
    public record ImageFileDto(int id, String absPath, String mediaType) implements PathFragmentDto {
        public ImageFileDto {
            if (absPath == null || absPath.isEmpty()) {
                throw new IllegalArgumentException("absPath cannot be null or empty");
            }
            // mediaType can be null for unknown types
        }
    }
    
    /**
     * DTO for TaskEntry - represents a task history entry.
     */
    public record TaskEntryDto(int sequence, TaskFragmentDto log, String summary) {
        public TaskEntryDto {
            // Exactly one of log or summary must be non-null (same constraint as TaskEntry)
            if ((log == null) == (summary == null)) {
                throw new IllegalArgumentException("Exactly one of log or summary must be non-null");
            }
            if (summary != null && summary.isEmpty()) {
                throw new IllegalArgumentException("summary cannot be empty when present");
            }
        }
    }
    
    /**
     * DTO for TaskFragment - represents a session's chat messages.
     */
    public record TaskFragmentDto(int id, List<ChatMessageDto> messages, String sessionName) implements VirtualFragmentDto {
        public TaskFragmentDto {
            messages = messages != null ? List.copyOf(messages) : List.of();
            if (sessionName == null) {
                throw new IllegalArgumentException("sessionName cannot be null");
            }
        }
    }
    
    /**
     * DTO for ChatMessage - simplified representation with role and content.
     */
    public record ChatMessageDto(String role, String content) {
        public ChatMessageDto {
            if (role == null || role.isEmpty()) {
                throw new IllegalArgumentException("role cannot be null or empty");
            }
            if (content == null) {
                throw new IllegalArgumentException("content cannot be null");
            }
        }
    }
    
    /**
     * DTO for StringFragment - contains text content with description and syntax style.
     */
    public record StringFragmentDto(int id, String text, String description, String syntaxStyle) implements VirtualFragmentDto {
        public StringFragmentDto {
            if (text == null) {
                throw new IllegalArgumentException("text cannot be null");
            }
            if (description == null) {
                throw new IllegalArgumentException("description cannot be null");
            }
            if (syntaxStyle == null) {
                throw new IllegalArgumentException("syntaxStyle cannot be null");
            }
        }
    }
    
    /**
     * DTO for SearchFragment - contains search query, explanation, sources and messages.
     */
    public record SearchFragmentDto(int id, String query, String explanation, Set<CodeUnitDto> sources, List<ChatMessageDto> messages) implements VirtualFragmentDto {
        public SearchFragmentDto {
            if (query == null) {
                throw new IllegalArgumentException("query cannot be null");
            }
            if (explanation == null) {
                throw new IllegalArgumentException("explanation cannot be null");
            }
            sources = sources != null ? Set.copyOf(sources) : Set.of();
            messages = messages != null ? List.copyOf(messages) : List.of();
        }
    }
    
    /**
     * DTO for SkeletonFragment - contains target identifiers (FQ class names or file paths) and summary type.
     */
    public record SkeletonFragmentDto(int id, List<String> targetIdentifiers, String summaryType) implements VirtualFragmentDto {
        public SkeletonFragmentDto {
            if (targetIdentifiers == null || targetIdentifiers.isEmpty()) {
                throw new IllegalArgumentException("targetIdentifiers cannot be null or empty");
            }
            targetIdentifiers = List.copyOf(targetIdentifiers);
            if (summaryType == null || summaryType.isEmpty()) {
                throw new IllegalArgumentException("summaryType cannot be null or empty");
            }
        }
    }

    /**
     * DTO for UsageFragment - contains target identifier.
     */
    public record UsageFragmentDto(int id, String targetIdentifier) implements VirtualFragmentDto {
        public UsageFragmentDto {
            if (targetIdentifier == null || targetIdentifier.isEmpty()) {
                throw new IllegalArgumentException("targetIdentifier cannot be null or empty");
            }
        }
    }

    /**
     * DTO for GitFileFragment - represents a specific revision of a file from Git history.
     */
    public record GitFileFragmentDto(int id, String repoRoot, String relPath, String revision, String content) implements PathFragmentDto {
        public GitFileFragmentDto {
            if (repoRoot == null || repoRoot.isEmpty()) {
                throw new IllegalArgumentException("repoRoot cannot be null or empty");
            }
            if (relPath == null || relPath.isEmpty()) {
                throw new IllegalArgumentException("relPath cannot be null or empty");
            }
            if (revision == null || revision.isEmpty()) {
                throw new IllegalArgumentException("revision cannot be null or empty");
            }
            if (content == null) {
                throw new IllegalArgumentException("content cannot be null");
            }
        }
    }
    
    /**
     * DTO for PasteTextFragment - contains pasted text with resolved description.
     */
    public record PasteTextFragmentDto(int id, String text, String description) implements VirtualFragmentDto {
        public PasteTextFragmentDto {
            if (text == null) {
                throw new IllegalArgumentException("text cannot be null");
            }
            if (description == null) {
                throw new IllegalArgumentException("description cannot be null");
            }
        }
    }
    
    /**
     * DTO for PasteImageFragment - contains base64-encoded image data with resolved description.
     */
    public record PasteImageFragmentDto(int id, String base64ImageData, String description) implements VirtualFragmentDto {
        public PasteImageFragmentDto {
            if (base64ImageData == null || base64ImageData.isEmpty()) {
                throw new IllegalArgumentException("base64ImageData cannot be null or empty");
            }
            if (description == null) {
                throw new IllegalArgumentException("description cannot be null");
            }
        }
    }
    
    /**
     * DTO for StacktraceFragment - contains stacktrace analysis data.
     */
    public record StacktraceFragmentDto(int id, Set<CodeUnitDto> sources, String original, String exception, String code) implements VirtualFragmentDto {
        public StacktraceFragmentDto {
            if (original == null) {
                throw new IllegalArgumentException("original cannot be null");
            }
            if (exception == null) {
                throw new IllegalArgumentException("exception cannot be null");
            }
            if (code == null) {
                throw new IllegalArgumentException("code cannot be null");
            }
            sources = sources != null ? Set.copyOf(sources) : Set.of();
        }
    }
    
    /**
     * DTO for CallGraphFragment - contains method name, depth, and graph type (callee/caller).
     */
    public record CallGraphFragmentDto(int id, String methodName, int depth, boolean isCalleeGraph) implements VirtualFragmentDto {
        public CallGraphFragmentDto {
            if (methodName == null || methodName.isEmpty()) {
                throw new IllegalArgumentException("methodName cannot be null or empty");
            }
            if (depth <= 0) {
                throw new IllegalArgumentException("depth must be positive");
            }
        }
    }

    /**
     * DTO for HistoryFragment - contains task history entries.
     */
    public record HistoryFragmentDto(int id, List<TaskEntryDto> history) implements VirtualFragmentDto {
        public HistoryFragmentDto {
            if (history == null) {
                throw new IllegalArgumentException("history cannot be null");
            }
            history = List.copyOf(history);
        }
    }
    
    /**
     * DTO for CodeUnit - represents a named code element.
     */
    public record CodeUnitDto(ProjectFileDto sourceFile, String kind, String packageName, String shortName) {
        public CodeUnitDto {
            if (sourceFile == null) {
                throw new IllegalArgumentException("sourceFile cannot be null");
            }
            if (kind == null || kind.isEmpty()) {
                throw new IllegalArgumentException("kind cannot be null or empty");
            }
            if (packageName == null) {
                throw new IllegalArgumentException("packageName cannot be null");
            }
            if (shortName == null || shortName.isEmpty()) {
                throw new IllegalArgumentException("shortName cannot be null or empty");
            }
        }
    }
}
