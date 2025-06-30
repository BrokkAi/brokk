package io.github.jbellis.brokk.context;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sealed interfaces and records for fragment DTOs with Jackson polymorphic support.
 */
public class FragmentDtos {

    /**
     * Common marker interface for DTOs that can be referenced in editable/readonly lists.
     * This helps Jackson with polymorphic deserialization into List<ReferencedFragmentDto>.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class") // Using @class to avoid conflict with "type" from Path/Virtual
    public sealed interface ReferencedFragmentDto permits PathFragmentDto, FrozenFragmentDto {
        String id();
    }

    
    /**
     * Sealed interface for path-based fragments (files).
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "type")
    // PathFragmentDto now also implements ReferencedFragmentDto
    public sealed interface PathFragmentDto extends ReferencedFragmentDto permits ProjectFileDto, ExternalFileDto, ImageFileDto, GitFileFragmentDto {
    }
    
    /**
     * Sealed interface for virtual fragments (non-file content).
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "type")
    // Note: FrozenFragmentDto implements ReferencedFragmentDto directly, not necessarily through VirtualFragmentDto for this specific list purpose
    public sealed interface VirtualFragmentDto permits TaskFragmentDto, StringFragmentDto, SearchFragmentDto, SkeletonFragmentDto, UsageFragmentDto, PasteTextFragmentDto, PasteImageFragmentDto, StacktraceFragmentDto, CallGraphFragmentDto, HistoryFragmentDto, FrozenFragmentDto {
        String id();
    }
    
    /**
     * DTO for ProjectFile - contains root and relative path as strings.
     */
    public record ProjectFileDto(String id, String repoRoot, String relPath) implements PathFragmentDto { // id changed to String
        public ProjectFileDto {
            if (repoRoot.isEmpty()) {
                throw new IllegalArgumentException("repoRoot cannot be null or empty");
            }
            if (relPath.isEmpty()) {
                throw new IllegalArgumentException("relPath cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for ExternalFile - contains absolute path as string.
     */
    public record ExternalFileDto(String id, String absPath) implements PathFragmentDto { // id changed to String
        public ExternalFileDto {
            if (absPath.isEmpty()) {
                throw new IllegalArgumentException("absPath cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for ImageFile - contains absolute path and media type.
     */
    public record ImageFileDto(String id, String absPath, @Nullable String mediaType) implements PathFragmentDto { // id changed to String
        public ImageFileDto {
            if (absPath.isEmpty()) {
                throw new IllegalArgumentException("absPath cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for TaskEntry - represents a task history entry.
     */
    public record TaskEntryDto(int sequence, @Nullable TaskFragmentDto log, @Nullable String summary) {
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
    public record TaskFragmentDto(String id, List<ChatMessageDto> messages, String sessionName) implements VirtualFragmentDto { // id changed to String
        public TaskFragmentDto {
            messages = List.copyOf(messages);
        }
    }
    
    /**
     * DTO for ChatMessage - simplified representation with role and content.
     */
    public record ChatMessageDto(String role, String content) {
        public ChatMessageDto {
            if (role.isEmpty()) {
                throw new IllegalArgumentException("role cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for StringFragment - contains text content with description and syntax style.
     */
    public record StringFragmentDto(String id, String text, String description, String syntaxStyle) implements VirtualFragmentDto { // id changed to String
    }
    
    /**
     * DTO for SearchFragment - contains search query, explanation, sources and messages.
     */
    public record SearchFragmentDto(String id, String query, String explanation, Set<CodeUnitDto> sources, List<ChatMessageDto> messages) implements VirtualFragmentDto { // id changed to String
        public SearchFragmentDto {
            sources = Set.copyOf(sources);
            messages = List.copyOf(messages);
        }
    }
    
    /**
     * DTO for SkeletonFragment - contains target identifiers (FQ class names or file paths) and summary type.
     */
    public record SkeletonFragmentDto(String id, List<String> targetIdentifiers, String summaryType) implements VirtualFragmentDto { // id changed to String
        public SkeletonFragmentDto {
            if (targetIdentifiers.isEmpty()) {
                throw new IllegalArgumentException("targetIdentifiers cannot be null or empty");
            }
            targetIdentifiers = List.copyOf(targetIdentifiers);
            if (summaryType.isEmpty()) {
                throw new IllegalArgumentException("summaryType cannot be null or empty");
            }
        }
    }

    /**
     * DTO for UsageFragment - contains target identifier.
     */
    public record UsageFragmentDto(String id, String targetIdentifier) implements VirtualFragmentDto { // id changed to String
        public UsageFragmentDto {
            if (targetIdentifier.isEmpty()) {
                throw new IllegalArgumentException("targetIdentifier cannot be null or empty");
            }
        }
    }

    /**
     * DTO for GitFileFragment - represents a specific revision of a file from Git history.
     */
    public record GitFileFragmentDto(String id, String repoRoot, String relPath, String revision, String content) implements PathFragmentDto { // id changed to String
        public GitFileFragmentDto {
            if (repoRoot.isEmpty()) {
                throw new IllegalArgumentException("repoRoot cannot be null or empty");
            }
            if (relPath.isEmpty()) {
                throw new IllegalArgumentException("relPath cannot be null or empty");
            }
            if (revision.isEmpty()) {
                throw new IllegalArgumentException("revision cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for PasteTextFragment - contains pasted text with resolved description.
     */
    public record PasteTextFragmentDto(String id, String text, String description) implements VirtualFragmentDto { // id changed to String
    }
    
    /**
     * DTO for PasteImageFragment - contains base64-encoded image data with resolved description.
     */
    public record PasteImageFragmentDto(String id, String base64ImageData, String description) implements VirtualFragmentDto { // id changed to String
        public PasteImageFragmentDto {
            if (base64ImageData.isEmpty()) {
                throw new IllegalArgumentException("base64ImageData cannot be null or empty");
            }
        }
    }
    
    /**
     * DTO for StacktraceFragment - contains stacktrace analysis data.
     */
    public record StacktraceFragmentDto(String id, Set<CodeUnitDto> sources, String original, String exception, String code) implements VirtualFragmentDto { // id changed to String
        public StacktraceFragmentDto {
            sources = Set.copyOf(sources);
        }
    }
    
    /**
     * DTO for CallGraphFragment - contains method name, depth, and graph type (callee/caller).
     */
    public record CallGraphFragmentDto(String id, String methodName, int depth, boolean isCalleeGraph) implements VirtualFragmentDto { // id changed to String
        public CallGraphFragmentDto {
            if (methodName.isEmpty()) {
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
    public record HistoryFragmentDto(String id, List<TaskEntryDto> history) implements VirtualFragmentDto { // id changed to String
        public HistoryFragmentDto {
            history = List.copyOf(history);
        }
    }
    
    /**
     * DTO for FrozenFragment - contains frozen state of any fragment type.
     */
    public record FrozenFragmentDto(String id,
                                    String originalType,
                                    String description,
                                    String shortDescription,
                                    @Nullable String textContent,
                                    boolean isTextFragment,
                                    String syntaxStyle,
                                    Set<ProjectFileDto> files,
                                    String originalClassName,
                                    Map<String, String> meta)
            implements VirtualFragmentDto, ReferencedFragmentDto
    {
        public FrozenFragmentDto {
            if (originalType.isEmpty()) {
                throw new IllegalArgumentException("originalType cannot be null or empty");
            }
            if (originalClassName.isEmpty()) {
                throw new IllegalArgumentException("originalClassName cannot be null or empty");
            }
            files = Set.copyOf(files);
            meta = Map.copyOf(meta);
        }
    }
    
    /**
     * DTO for CodeUnit - represents a named code element.
     */
    public record CodeUnitDto(ProjectFileDto sourceFile, String kind, String packageName, String shortName) {
        public CodeUnitDto {
            if (kind.isEmpty()) {
                throw new IllegalArgumentException("kind cannot be null or empty");
            }
            if (shortName.isEmpty()) {
                throw new IllegalArgumentException("shortName cannot be null or empty");
            }
        }
    }

    /**
     * DTO for holding all unique fragments in a session history.
     * Used as the top-level object for fragments.json.
     */
    public record AllFragmentsDto(
            int version, // Version of the fragment DTO structure
            Map<String, ReferencedFragmentDto> referenced, 
            Map<String, VirtualFragmentDto> virtual,   
            Map<String, TaskFragmentDto> task) {       
        public AllFragmentsDto {
            if (version < 1) {
                throw new IllegalArgumentException("Version must be 1 or greater");
            }
            referenced = Map.copyOf(referenced);
            virtual = Map.copyOf(virtual);
            task = Map.copyOf(task);
        }
    }

    /**
     * Compact DTO for Context, referring to fragments by ID.
     * Used in contexts.jsonl.
     * This is the primary DTO for representing a Context in persistent history.
     */
    public record CompactContextDto(
            @Nullable String id, // may be null when loading V1
            List<String> editable,      
            List<String> readonly,      
            List<String> virtuals,      
            List<TaskEntryRefDto> tasks,
            @Nullable String parsedOutputId,   
            String action) {
        public CompactContextDto {
            // No validation on id – null means “absent in V1 history”
            editable = List.copyOf(editable);
            readonly = List.copyOf(readonly);
            virtuals = List.copyOf(virtuals);
            tasks = List.copyOf(tasks);
        }
    }

    /**
     * Compact DTO for TaskEntry, referring to its log fragment by ID.
     * Used within CompactContextDto.
     */
    public record TaskEntryRefDto(int sequence, @Nullable String logId, @Nullable String summary) { // logId changed to String
        public TaskEntryRefDto {
            // logId can be null if summary is present, and vice-versa
            if ((logId == null) == (summary == null)) {
                throw new IllegalArgumentException("Exactly one of logId or summary must be non-null");
            }
            if (summary != null && summary.isEmpty()) {
                throw new IllegalArgumentException("summary cannot be empty when present");
            }
        }
    }
}
