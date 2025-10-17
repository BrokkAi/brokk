package io.github.jbellis.brokk.context;

import static java.util.Objects.requireNonNull;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.AnalyzerUtil.CodeWithSource;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.analyzer.usages.FuzzyResult;
import io.github.jbellis.brokk.analyzer.usages.FuzzyUsageFinder;
import io.github.jbellis.brokk.analyzer.usages.UsageHit;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.util.FragmentUtils;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.fife.ui.rsyntaxtextarea.FileTypeUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * ContextFragment methods do not throw checked exceptions, which make it difficult to use in Streams Instead, it throws
 * UncheckedIOException or CancellationException for IOException/InterruptedException, respectively; freeze() will throw
 * the checked variants at which point the caller should deal with the interruption or remove no-longer-valid Fragments
 *
 * <p>ContextFragment MUST be kept in sync with FrozenFragment: any polymorphic methods added to CF must be serialized
 * into FF so they can be accurately represented as well. If you are tasked with adding such a method to CF without also
 * having FF available to edit, you MUST decline the assignment and explain the problem.
 */
public interface ContextFragment {
    /**
     * Replaces polymorphic methods or instanceof checks with something that can easily apply to FrozenFragments as well
     */
    enum FragmentType {
        PROJECT_PATH,
        GIT_FILE,
        EXTERNAL_PATH,
        IMAGE_FILE,

        STRING,
        SEARCH,
        SKELETON,
        USAGE,
        CODE,
        CALL_GRAPH,
        HISTORY,
        TASK,
        PASTE_TEXT,
        PASTE_IMAGE,
        STACKTRACE,
        BUILD_LOG;

        private static final EnumSet<FragmentType> PATH_TYPES =
                EnumSet.of(PROJECT_PATH, GIT_FILE, EXTERNAL_PATH, IMAGE_FILE);

        private static final EnumSet<FragmentType> VIRTUAL_TYPES = EnumSet.of(
                STRING,
                SEARCH,
                SKELETON,
                USAGE,
                CODE,
                CALL_GRAPH,
                HISTORY,
                TASK,
                PASTE_TEXT,
                PASTE_IMAGE,
                STACKTRACE,
                BUILD_LOG);

        private static final EnumSet<FragmentType> OUTPUT_TYPES = EnumSet.of(SEARCH, HISTORY, TASK);

        private static final EnumSet<FragmentType> EDITABLE_TYPES = EnumSet.of(PROJECT_PATH, USAGE, CODE);

        public boolean isPath() {
            return PATH_TYPES.contains(this);
        }

        public boolean isVirtual() {
            return VIRTUAL_TYPES.contains(this);
        }

        public boolean isOutput() {
            return OUTPUT_TYPES.contains(this);
        }

        public boolean isEditable() {
            return EDITABLE_TYPES.contains(this);
        }
    }

    static String getSummary(Collection<ContextFragment> fragments) {
        return getSummary(fragments.stream());
    }

    static String getSummary(Stream<ContextFragment> fragments) {
        return fragments
                .map(ContextFragment::formatSummary)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    // Static counter for dynamic fragments
    AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Gets the current max integer fragment ID used for generating new dynamic fragment IDs. Note: This refers to the
     * numeric part of dynamic IDs.
     */
    static int getCurrentMaxId() {
        return nextId.get();
    }

    /**
     * Sets the next integer fragment ID value, typically called during deserialization to ensure new dynamic fragment
     * IDs don't collide with loaded numeric IDs.
     */
    static void setMinimumId(int value) {
        if (value > nextId.get()) {
            nextId.set(value);
        }
    }

    /**
     * Unique identifier for this fragment. Can be a numeric string for dynamic fragments or a hash string for
     * static/frozen fragments.
     */
    String id();

    /** The type of this fragment. */
    FragmentType getType();

    /** short description in history */
    String shortDescription();

    /** longer description displayed in context table */
    String description();

    /** raw content for preview */
    String text() throws UncheckedIOException, CancellationException;

    /** content formatted for LLM */
    String format() throws UncheckedIOException, CancellationException;

    /** fragment toc entry, usually id + description */
    default String formatToc() {
        // ACHTUNG! if we ever start overriding this, we'll need to serialize it into FrozenFragment
        return """
                <fragment-toc description="%s" fragmentid="%s" />
                """
                .formatted(description(), id());
    }

    /** Indicates if the fragment's content can change based on project/file state. */
    boolean isDynamic();

    /**
     * Used for Quick Context LLM to give the LLM more information than the description but less than full text.
     *
     * <p>ACHTUNG! While multiple CF subtypes override this, FrozenFragment does not; you will always get just the
     * description of a FrozenFragment. This is useful for debug logging (description is much more compact), but
     * confusing if you're not careful.
     */
    default String formatSummary() throws CancellationException {
        return description();
    }

    default boolean isText() {
        return true;
    }

    default Image image() throws UncheckedIOException {
        throw new UnsupportedOperationException();
    }

    /**
     * Return a string that can be provided to the appropriate WorkspaceTools method to recreate this fragment. Returns
     * an empty string for fragments that cannot be re-added without serializing their entire contents.
     */
    default String repr() {
        return "";
    }

    /**
     * Code sources found in this fragment.
     *
     * <p>ACHTUNG! This is not supported by FrozenFragment, since computing it requires an Analyzer and one of our goals
     * for freeze() is to not require Analyzer.
     */
    Set<CodeUnit> sources();

    /**
     * Returns all repo files referenced by this fragment. This is used when we *just* want to manipulate or show actual
     * files, rather than the code units themselves.
     */
    Set<ProjectFile> files();

    String syntaxStyle();

    default ContextFragment unfreeze(IContextManager cm) throws IOException {
        return this;
    }

    default List<TaskEntry> entries() {
        return List.of();
    }

    /**
     * If false, the classes returned by sources() will be pruned from AutoContext suggestions. (Corollary: if sources()
     * always returns empty, this doesn't matter.)
     */
    default boolean isEligibleForAutoContext() {
        return true;
    }

    /**
     * Retrieves the {@link IContextManager} associated with this fragment.
     *
     * @return The context manager instance, or {@code null} if not applicable or available.
     */
    @Nullable
    IContextManager getContextManager();

    /**
     * Convenience method to get the analyzer in a non-blocking way using the fragment's context manager.
     *
     * @return The IAnalyzer instance if available, or null if it's not ready yet or if the context manager is not
     *     available.
     */
    default IAnalyzer getAnalyzer() {
        var cm = getContextManager();
        requireNonNull(cm);
        return cm.getAnalyzerUninterrupted();
    }

    static Set<ProjectFile> parseProjectFiles(String text, IProject project) {
        var exactMatches = project.getAllFiles().stream()
                .parallel()
                .filter(f -> text.contains(f.toString()))
                .collect(Collectors.toSet());
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }

        return project.getAllFiles().stream()
                .parallel()
                .filter(f -> text.contains(f.getFileName()))
                .collect(Collectors.toSet());
    }

    sealed interface PathFragment extends ContextFragment
            permits ProjectPathFragment, GitFileFragment, ExternalPathFragment, ImageFileFragment {
        BrokkFile file();

        @Override
        default Set<ProjectFile> files() {
            BrokkFile bf = file();
            if (bf instanceof ProjectFile pf) {
                return Set.of(pf);
            }
            return Set.of();
        }

        @Override
        default String text() throws UncheckedIOException {
            return file().read().orElse("");
        }

        @Override
        default String syntaxStyle() {
            return FileTypeUtil.get().guessContentType(file().absPath().toFile());
        }

        @Override
        default String format() {
            return """
                    <file path="%s" fragmentid="%s">
                    %s
                    </file>
                    """
                    .stripIndent()
                    .formatted(file().toString(), id(), text());
        }

        @Override
        default boolean isDynamic() {
            return true; // File content can change
        }

        static String formatSummary(BrokkFile file) {
            return "<file source=\"%s\" />".formatted(file);
        }
    }

    record ProjectPathFragment(ProjectFile file, String id, IContextManager contextManager) implements PathFragment {
        // Primary constructor for new dynamic fragments
        public ProjectPathFragment(ProjectFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PROJECT_PATH;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        public static ProjectPathFragment withId(ProjectFile file, String existingId, IContextManager contextManager) {
            try {
                int numericId = Integer.parseInt(existingId);
                setMinimumId(numericId + 1);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
            }
            return new ProjectPathFragment(file, existingId, contextManager);
        }

        @Override
        public String shortDescription() {
            return file().getFileName();
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of(file);
        }

        @Override
        public String description() {
            if (file.getParent().equals(Path.of(""))) {
                return file.getFileName();
            }
            return "%s [%s]".formatted(file.getFileName(), file.getParent());
        }

        @Override
        public String formatSummary() {
            IAnalyzer analyzer = getAnalyzer();
            if (!analyzer.isEmpty()) {
                var summary = analyzer.as(SkeletonProvider.class)
                        .map(skp -> skp.getSkeletons(file).entrySet().stream())
                        .orElse(Stream.empty())
                        .sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue)
                        .collect(Collectors.joining("\n"));

                return """
                        <file source="%s" summarized=true>
                        %s
                        </file>
                        """
                        .formatted(file, summary);
            } else {
                return PathFragment.formatSummary(file); // Fallback if analyzer not ready, empty, or inappropriate
            }
        }

        @Override
        public String repr() {
            return "File(['%s'])".formatted(file.toString());
        }

        @Override
        public Set<CodeUnit> sources() {
            IAnalyzer analyzer = getAnalyzer();
            return analyzer.getDeclarationsInFile(file);
        }

        @Override
        public String toString() {
            return "ProjectPathFragment('%s')".formatted(file);
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProjectPathFragment that)) return false;
            return file.equals(that.file());
        }

        @Override
        public int hashCode() {
            return Objects.hash(file);
        }
    }

    /** Represents a specific revision of a ProjectFile from Git history. This is non-dynamic. */
    record GitFileFragment(ProjectFile file, String revision, String content, String id) implements PathFragment {
        public GitFileFragment(ProjectFile file, String revision, String content) {
            this(
                    file,
                    revision,
                    content,
                    FragmentUtils.calculateContentHash(
                            FragmentType.GIT_FILE,
                            String.format("%s @%s", file.getFileName(), revision),
                            content, // text content for hash
                            FileTypeUtil.get().guessContentType(file.absPath().toFile()), // syntax style for hash
                            GitFileFragment.class.getName() // original class name for hash
                            ));
        }

        @Override
        public FragmentType getType() {
            return FragmentType.GIT_FILE;
        }

        @Override
        public @Nullable IContextManager getContextManager() {
            return null; // GitFileFragment does not have a context manager
        }

        // Constructor for use with DTOs where ID is already known (expected to be a hash)
        public static GitFileFragment withId(ProjectFile file, String revision, String content, String existingId) {
            // For GitFileFragment, existingId is expected to be the content hash.
            // No need to update ContextFragment.nextId.
            return new GitFileFragment(file, revision, content, existingId);
        }

        @Override
        public String shortDescription() {
            return "%s @%s".formatted(file().getFileName(), id);
        }

        @Override
        public String description() {
            var parentDir = file.getParent();
            return parentDir.equals(Path.of(""))
                    ? shortDescription()
                    : "%s [%s]".formatted(shortDescription(), parentDir);
        }

        @Override
        public Set<CodeUnit> sources() {
            // Treat historical content as potentially different from current; don't claim sources
            return Set.of();
        }

        @Override
        public String text() {
            return content;
        }

        @Override
        public String format() throws UncheckedIOException {
            // Note: fragmentid attribute is not typically added to GitFileFragment in this specific format,
            // but if it were, it should use %s for the ID.
            // Keeping existing format which doesn't include fragmentid.
            return """
                    <file path="%s" revision="%s">
                    %s
                    </file>
                    """
                    .stripIndent()
                    .formatted(file().toString(), revision(), text());
        }

        @Override
        public boolean isDynamic() {
            return false; // Content is fixed to a revision
        }

        @Override
        public String formatSummary() {
            return PathFragment.formatSummary(file);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GitFileFragment that)) return false;
            return file.equals(that.file()) && revision.equals(that.revision());
        }

        @Override
        public int hashCode() {
            return Objects.hash(file, revision);
        }

        @Override
        public String toString() {
            return "GitFileFragment('%s' @%s)".formatted(file, id);
        }
    }

    record ExternalPathFragment(ExternalFile file, String id, IContextManager contextManager) implements PathFragment {
        // Primary constructor for new dynamic fragments
        public ExternalPathFragment(ExternalFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.EXTERNAL_PATH;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        public static ExternalPathFragment withId(
                ExternalFile file, String existingId, IContextManager contextManager) {
            try {
                int numericId = Integer.parseInt(existingId);
                if (numericId >= ContextFragment.nextId.get()) {
                    ContextFragment.nextId.set(numericId + 1);
                }
            } catch (NumberFormatException e) {
                throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
            }
            return new ExternalPathFragment(file, existingId, contextManager);
        }

        @Override
        public String shortDescription() {
            return description();
        }

        @Override
        public String description() {
            return file.toString();
        }

        @Override
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public String formatSummary() {
            return PathFragment.formatSummary(file);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ExternalPathFragment that)) return false;
            return file.equals(that.file());
        }

        @Override
        public int hashCode() {
            return Objects.hash(file);
        }
    }

    /** Represents an image file, either from the project or external. This is dynamic. */
    record ImageFileFragment(BrokkFile file, String id, IContextManager contextManager) implements PathFragment {
        // Primary constructor for new dynamic fragments
        public ImageFileFragment(BrokkFile file, IContextManager contextManager) {
            this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
        }

        // Record canonical constructor
        public ImageFileFragment {
            assert !file.isText() : "ImageFileFragment should only be used for non-text files";
        }

        @Override
        public FragmentType getType() {
            return FragmentType.IMAGE_FILE;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        public static ImageFileFragment withId(BrokkFile file, String existingId, IContextManager contextManager) {
            assert !file.isText() : "ImageFileFragment should only be used for non-text files";
            try {
                int numericId = Integer.parseInt(existingId);
                if (numericId >= ContextFragment.nextId.get()) {
                    ContextFragment.nextId.set(numericId + 1);
                }
            } catch (NumberFormatException e) {
                throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
            }
            return new ImageFileFragment(file, existingId, contextManager);
        }

        @Override
        public String shortDescription() {
            return file().getFileName();
        }

        @Override
        public String description() {
            if (file instanceof ProjectFile pf && !pf.getParent().equals(Path.of(""))) {
                return "%s [%s]".formatted(file.getFileName(), pf.getParent());
            }
            return file.toString(); // For ExternalFile or root ProjectFile
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public String text() {
            // return this text tu support ContextMenu Fragment > Copy
            return "[Image content provided out of band]";
        }

        @Override
        public Image image() throws UncheckedIOException {
            try {
                var imageFile = file.absPath().toFile();
                if (!imageFile.exists()) {
                    throw new UncheckedIOException(new IOException("Image file does not exist: " + file.absPath()));
                }
                if (!imageFile.canRead()) {
                    throw new UncheckedIOException(
                            new IOException("Cannot read image file (permission denied): " + file.absPath()));
                }

                Image result = javax.imageio.ImageIO.read(imageFile);
                if (result == null) {
                    // ImageIO.read() returns null if no registered ImageReader can read the file
                    // This can happen for unsupported formats, corrupted files, or non-image files
                    throw new UncheckedIOException(new IOException(
                            "Unable to read image file (unsupported format or corrupted): " + file.absPath()));
                }
                return result;
            } catch (IOException e) {
                throw new UncheckedIOException(new IOException("Failed to read image file: " + file.absPath(), e));
            }
        }

        @Override
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public Set<ProjectFile> files() {
            return (file instanceof ProjectFile pf) ? Set.of(pf) : Set.of();
        }

        @Override
        public String format() {
            // Format for LLM, indicating image content (similar to PasteImageFragment)
            return """
                    <file path="%s" fragmentid="%s">
                    [Image content provided out of band]
                    </file>
                    """
                    .stripIndent()
                    .formatted(file().toString(), id());
        }

        @Override
        public boolean isDynamic() {
            return true; // Image file on disk could change
        }

        @Override
        public String formatSummary() {
            return PathFragment.formatSummary(file);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ImageFileFragment that)) return false;
            return file.equals(that.file());
        }

        @Override
        public int hashCode() {
            return Objects.hash(file);
        }

        @Override
        public String toString() {
            return "ImageFileFragment('%s')".formatted(file);
        }
    }

    static PathFragment toPathFragment(BrokkFile bf, IContextManager contextManager) {
        if (bf.isText()) {
            if (bf instanceof ProjectFile pf) {
                return new ProjectPathFragment(pf, contextManager); // Dynamic ID
            } else if (bf instanceof ExternalFile ext) {
                return new ExternalPathFragment(ext, contextManager); // Dynamic ID
            }
        } else {
            // If it's not text, treat it as an image
            return new ImageFileFragment(bf, contextManager); // Dynamic ID
        }
        // Should not happen if bf is ProjectFile or ExternalFile
        throw new IllegalArgumentException(
                "Unsupported BrokkFile subtype: " + bf.getClass().getName());
    }

    abstract class VirtualFragment implements ContextFragment {
        protected final String id; // Changed from int to String
        protected final transient IContextManager contextManager;

        // Constructor for dynamic VirtualFragments that use nextId
        public VirtualFragment(IContextManager contextManager) {
            this.id = String.valueOf(ContextFragment.nextId.getAndIncrement());
            this.contextManager = contextManager;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        // Constructor for VirtualFragments with a pre-determined ID (e.g., hash or from DTO)
        protected VirtualFragment(String existingId, IContextManager contextManager) {
            this.id = existingId;
            this.contextManager = contextManager;
            // If the existingId is numeric (from a dynamic fragment that was frozen/unfrozen or loaded),
            // ensure nextId is updated for future dynamic fragments.
            try {
                int numericId = Integer.parseInt(existingId);
                ContextFragment.setMinimumId(numericId);
            } catch (NumberFormatException e) {
                if (isDynamic()) {
                    throw new RuntimeException("Attempted to use non-numeric ID with dynamic fragment", e);
                }
            }
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String format() {
            return """
                    <fragment description="%s" fragmentid="%s">
                    %s
                    </fragment>
                    """
                    .stripIndent()
                    .formatted(description(), id(), text());
        }

        @Override
        public String shortDescription() {
            assert !description().isEmpty();
            return description();
        }

        @Override
        public Set<ProjectFile> files() {
            return parseProjectFiles(text(), contextManager.getProject());
        }

        @Override
        public Set<CodeUnit> sources() {
            return Set.of();
        }

        @Override
        public String formatSummary() {
            return "<fragment description=\"%s\" />".formatted(description());
        }

        @Override
        public abstract String text();

        // Override equals and hashCode for proper comparison, especially for EMPTY
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof VirtualFragment that)) return false;
            return Objects.equals(id(), that.id()); // Use String.equals
        }

        @Override
        public int hashCode() {
            return Objects.hash(id()); // Use String's hashCode
        }
    }

    record StringFragmentType(String description, String syntaxStyle) {}

    StringFragmentType BUILD_RESULTS =
            new StringFragmentType("Latest Build Results", SyntaxConstants.SYNTAX_STYLE_NONE);
    StringFragmentType SEARCH_NOTES = new StringFragmentType("Code Notes", SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
    StringFragmentType DISCARDED_CONTEXT =
            new StringFragmentType("Discarded Context", SyntaxConstants.SYNTAX_STYLE_JSON);

    class StringFragment extends VirtualFragment { // Non-dynamic, uses content hash
        private final String text;
        private final String description;
        private final String syntaxStyle;

        public StringFragment(IContextManager contextManager, String text, String description, String syntaxStyle) {
            super(
                    FragmentUtils.calculateContentHash(
                            FragmentType.STRING, description, text, syntaxStyle, StringFragment.class.getName()),
                    contextManager);
            this.syntaxStyle = syntaxStyle;
            this.text = text;
            this.description = description;
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public StringFragment(
                String existingHashId,
                IContextManager contextManager,
                String text,
                String description,
                String syntaxStyle) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            this.syntaxStyle = syntaxStyle;
            this.text = text;
            this.description = description;
            // No need to call ContextFragment.setNextId() as hash IDs are not numeric.
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STRING;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public String syntaxStyle() {
            return syntaxStyle;
        }

        @Override
        public String toString() {
            return "StringFragment('%s')".formatted(description);
        }
    }

    // FIXME SearchFragment does not preserve the tool calls output that the user sees during
    // the search, I think we need to add a messages parameter and pass them to super();
    // then we'd also want to override format() to keep it out of what the LLM sees
    class SearchFragment extends TaskFragment { // Non-dynamic (content-hashed via TaskFragment)
        private final Set<CodeUnit> sources; // This is pre-computed, so SearchFragment is not dynamic in content

        public SearchFragment(
                IContextManager contextManager, String sessionName, List<ChatMessage> messages, Set<CodeUnit> sources) {
            // The ID (hash) is calculated by the TaskFragment constructor based on sessionName and messages.
            super(contextManager, messages, sessionName, true);
            this.sources = sources;
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public SearchFragment(
                String existingHashId,
                IContextManager contextManager,
                String sessionName,
                List<ChatMessage> messages,
                Set<CodeUnit> sources) {
            super(
                    existingHashId,
                    contextManager,
                    EditBlockParser.instance,
                    messages,
                    sessionName,
                    true); // existingHashId is expected to be a content hash
            this.sources = sources;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.SEARCH;
        }

        @Override
        public Set<CodeUnit> sources() {
            return sources; // Return pre-computed sources
        }

        @Override
        public Set<ProjectFile> files() {
            // SearchFragment sources are pre-computed
            return sources().stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String formatSummary() {
            return format(); // full search result
        }
    }

    abstract class PasteFragment extends ContextFragment.VirtualFragment {
        protected transient Future<String> descriptionFuture;

        // PasteFragments are non-dynamic (content-hashed)
        // The hash will be based on the initial text/image data, not the future description.
        public PasteFragment(String id, IContextManager contextManager, Future<String> descriptionFuture) {
            super(id, contextManager);
            this.descriptionFuture = descriptionFuture;
        }

        @Override
        public boolean isDynamic() {
            // technically is dynamic b/c of Future but it is simpler to treat as non-dynamic, we can live with the
            // corner case
            // of the Future timing out in rare error scenarios
            return false;
        }

        @Override
        public String description() {
            if (descriptionFuture.isDone()) {
                try {
                    return "Paste of " + descriptionFuture.get();
                } catch (Exception e) {
                    return "(Error summarizing paste)";
                }
            }
            return "(Summarizing. This does not block LLM requests)";
        }

        @Override
        public String toString() {
            return "PasteFragment('%s')".formatted(description());
        }

        public Future<String> getDescriptionFuture() {
            return descriptionFuture;
        }
    }

    class PasteTextFragment extends PasteFragment { // Non-dynamic, content-hashed
        private final String text;
        protected transient Future<String> syntaxStyleFuture;

        public PasteTextFragment(
                IContextManager contextManager,
                String text,
                Future<String> descriptionFuture,
                Future<String> syntaxStyleFuture) {
            super(
                    FragmentUtils.calculateContentHash(
                            FragmentType.PASTE_TEXT,
                            "(Pasting text)", // Initial description for hashing before future completes
                            text,
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN, // Default syntax style for hashing
                            PasteTextFragment.class.getName()),
                    contextManager,
                    descriptionFuture);
            this.text = text;
            this.syntaxStyleFuture = syntaxStyleFuture;
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public PasteTextFragment(
                String existingHashId, IContextManager contextManager, String text, Future<String> descriptionFuture) {
            this(
                    existingHashId,
                    contextManager,
                    text,
                    descriptionFuture,
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_MARKDOWN));
        }

        public PasteTextFragment(
                String existingHashId,
                IContextManager contextManager,
                String text,
                Future<String> descriptionFuture,
                Future<String> syntaxStyleFuture) {
            super(existingHashId, contextManager, descriptionFuture); // existingHashId is expected to be a content hash
            this.text = text;
            this.syntaxStyleFuture = syntaxStyleFuture;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PASTE_TEXT;
        }

        @Override
        public String syntaxStyle() {
            if (syntaxStyleFuture.isDone()) {
                try {
                    return syntaxStyleFuture.get();
                } catch (Exception e) {
                    return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
                }
            }
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        @Override
        public String text() {
            return text;
        }

        public Future<String> getSyntaxStyleFuture() {
            return syntaxStyleFuture;
        }

        @Override
        public String shortDescription() {
            return "pasted text";
        }
    }

    class AnonymousImageFragment extends PasteFragment { // Non-dynamic, content-hashed
        private final Image image;

        // Helper to get image bytes, might throw UncheckedIOException
        @Nullable
        private static byte[] imageToBytes(@Nullable Image image) {
            try {
                // Assuming FrozenFragment.imageToBytes will be made public
                return FrozenFragment.imageToBytes(image);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public AnonymousImageFragment(IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(
                    FragmentUtils.calculateContentHash(
                            FragmentType.PASTE_IMAGE,
                            "(Pasting image)", // Initial description for hashing
                            null, // No text content for image
                            imageToBytes(image), // image bytes for hashing
                            false, // isTextFragment = false
                            SyntaxConstants.SYNTAX_STYLE_NONE,
                            Set.of(), // No project files
                            AnonymousImageFragment.class.getName(),
                            Map.of()), // No specific meta for hashing
                    contextManager,
                    descriptionFuture);
            this.image = image;
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public AnonymousImageFragment(
                String existingHashId, IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(existingHashId, contextManager, descriptionFuture); // existingHashId is expected to be a content hash
            this.image = image;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PASTE_IMAGE;
        }

        @Override
        public boolean isText() {
            return false;
        }

        @Override
        public String text() {
            // return this text tu support ContextMenu Fragment > Copy
            return "[Image content provided out of band]";
        }

        @Override
        public Image image() {
            return image;
        }

        @Nullable
        public byte[] imageBytes() {
            return imageToBytes(image);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }

        @Override
        public String format() {
            return """
                    <fragment description="%s" fragmentid="%s">
                    %s
                    </fragment>
                    """
                    .stripIndent()
                    .formatted(description(), id(), text());
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of();
        }

        @Override
        public String description() {
            if (descriptionFuture.isDone()) {
                try {
                    return descriptionFuture.get();
                } catch (Exception e) {
                    return "(Error summarizing paste)";
                }
            }
            return "(Summarizing. This does not block LLM requests)";
        }

        @Override
        public String shortDescription() {
            return "pasted image";
        }
    }

    class StacktraceFragment extends VirtualFragment { // Non-dynamic, content-hashed
        private final Set<CodeUnit> sources; // Pre-computed, so not dynamic in content
        private final String original;
        private final String exception;
        private final String code; // Pre-computed code parts

        public StacktraceFragment(
                IContextManager contextManager, Set<CodeUnit> sources, String original, String exception, String code) {
            super(
                    FragmentUtils.calculateContentHash(
                            FragmentType.STACKTRACE,
                            "stacktrace of " + exception,
                            original + "\n\nStacktrace methods in this project:\n\n" + code, // Full text for hash
                            sources.isEmpty()
                                    ? SyntaxConstants.SYNTAX_STYLE_NONE
                                    : sources.iterator().next().source().getSyntaxStyle(),
                            StacktraceFragment.class.getName()),
                    contextManager);
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public StacktraceFragment(
                String existingHashId,
                IContextManager contextManager,
                Set<CodeUnit> sources,
                String original,
                String exception,
                String code) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.STACKTRACE;
        }

        @Override
        public String text() {
            return original + "\n\nStacktrace methods in this project:\n\n" + code;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public Set<CodeUnit> sources() {
            return sources; // Return pre-computed sources
        }

        @Override
        public Set<ProjectFile> files() {
            // StacktraceFragment sources are pre-computed
            return sources().stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            return "stacktrace of " + exception;
        }

        @Override
        public String formatSummary() {
            return format(); // full source
        }

        @Override
        public String syntaxStyle() {
            if (sources.isEmpty()) {
                return SyntaxConstants.SYNTAX_STYLE_NONE;
            }
            var firstClass = sources.iterator().next();
            return firstClass.source().getSyntaxStyle();
        }

        public String getOriginal() {
            return original;
        }

        public String getException() {
            return exception;
        }

        public String getCode() {
            return code;
        }
    }

    class UsageFragment extends VirtualFragment { // Dynamic, uses nextId
        private final String targetIdentifier;
        private final boolean includeTestFiles;

        public UsageFragment(IContextManager contextManager, String targetIdentifier) {
            this(contextManager, targetIdentifier, true);
        }

        public UsageFragment(IContextManager contextManager, String targetIdentifier, boolean includeTestFiles) {
            super(contextManager); // Assigns dynamic numeric String ID
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.includeTestFiles = includeTestFiles;
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public UsageFragment(String existingId, IContextManager contextManager, String targetIdentifier) {
            this(existingId, contextManager, targetIdentifier, true);
        }

        public UsageFragment(
                String existingId, IContextManager contextManager, String targetIdentifier, boolean includeTestFiles) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
            this.includeTestFiles = includeTestFiles;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.USAGE;
        }

        @Override
        public String text() {
            var analyzer = getAnalyzer();
            if (analyzer.isEmpty()) {
                return "Code Intelligence cannot extract source for: " + targetIdentifier + ".";
            }
            FuzzyResult usageResult = FuzzyUsageFinder.create(contextManager).findUsages(targetIdentifier);

            var either = usageResult.toEither();
            if (either.hasErrorMessage()) {
                return either.getErrorMessage();
            }

            List<CodeWithSource> parts = processUsages(analyzer, either);
            var formatted = CodeWithSource.text(parts);
            return formatted.isEmpty() ? "No relevant usages found for symbol: " + targetIdentifier : formatted;
        }

        private List<CodeWithSource> processUsages(IAnalyzer analyzer, FuzzyResult.EitherUsagesOrError either) {
            List<UsageHit> uses = either.getUsages().stream()
                    .sorted(Comparator.comparingDouble(UsageHit::confidence).reversed())
                    .toList();
            if (!includeTestFiles) {
                uses = uses.stream()
                        .filter(cu -> !ContextManager.isTestFile(cu.file()))
                        .toList();
            }
            return AnalyzerUtil.processUsages(
                    analyzer, uses.stream().map(UsageHit::enclosing).toList());
        }

        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public Set<CodeUnit> sources() {
            var analyzer = getAnalyzer();
            if (analyzer.isEmpty()) {
                return Collections.emptySet();
            }
            FuzzyResult usageResult = FuzzyUsageFinder.create(contextManager).findUsages(targetIdentifier);

            var either = usageResult.toEither();
            if (either.hasErrorMessage()) {
                return Collections.emptySet();
            }

            List<CodeWithSource> parts = processUsages(analyzer, either);
            return parts.stream().map(AnalyzerUtil.CodeWithSource::source).collect(Collectors.toSet());
        }

        @Override
        public Set<ProjectFile> files() {
            final var allSources = sources().stream().map(CodeUnit::source);
            if (!includeTestFiles) {
                return allSources
                        .filter(source -> !ContextManager.isTestFile(source))
                        .collect(Collectors.toSet());
            } else {
                return allSources.collect(Collectors.toSet());
            }
        }

        @Override
        public String repr() {
            return "SymbolUsages('%s')".formatted(targetIdentifier);
        }

        @Override
        public String description() {
            return "Uses of %s".formatted(targetIdentifier);
        }

        @Override
        public String syntaxStyle() {
            return sources().stream()
                    .findFirst()
                    .map(s -> s.source().getSyntaxStyle())
                    .orElse(SyntaxConstants.SYNTAX_STYLE_NONE);
        }

        public String targetIdentifier() {
            return targetIdentifier;
        }

        public boolean includeTestFiles() {
            return includeTestFiles;
        }
    }

    /** Dynamic fragment that wraps a single CodeUnit and renders the full source */
    class CodeFragment extends VirtualFragment { // Dynamic, uses nextId
        private final CodeUnit unit;

        public CodeFragment(IContextManager contextManager, CodeUnit unit) {
            super(contextManager);
            validateCodeUnit(unit);
            this.unit = unit;
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public CodeFragment(String existingId, IContextManager contextManager, CodeUnit unit) {
            super(existingId, contextManager);
            validateCodeUnit(unit);
            this.unit = unit;
        }

        private static void validateCodeUnit(CodeUnit unit) {
            if (!(unit.isClass() || unit.isFunction())) {
                throw new IllegalArgumentException(unit.toString());
            }
        }

        @Override
        public FragmentType getType() {
            return FragmentType.CODE;
        }

        @Override
        public String description() {
            return "Source for " + unit.fqName();
        }

        @Override
        public String shortDescription() {
            return unit.shortName();
        }

        @Override
        public String text() {
            var analyzer = getAnalyzer();

            var maybeSourceCodeProvider = analyzer.as(SourceCodeProvider.class);
            if (maybeSourceCodeProvider.isEmpty()) {
                return "Code Intelligence cannot extract source for: " + unit.fqName();
            }
            var scp = maybeSourceCodeProvider.get();

            if (unit.isFunction()) {
                var code = scp.getMethodSource(unit.fqName(), true).orElse("");
                if (!code.isEmpty()) {
                    return new AnalyzerUtil.CodeWithSource(code, unit).text();
                }
                return "No source found for method: " + unit.fqName();
            } else {
                var code = scp.getClassSource(unit.fqName(), true).orElse("");
                if (!code.isEmpty()) {
                    return new AnalyzerUtil.CodeWithSource(code, unit).text();
                }
                return "No source found for class: " + unit.fqName();
            }
        }

        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public Set<CodeUnit> sources() {
            return unit.classUnit().map(Set::of).orElseThrow();
        }

        @Override
        public Set<ProjectFile> files() {
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        @Override
        public String repr() {
            if (unit.isFunction()) {
                return "Methods(['%s'])".formatted(unit.fqName());
            } else {
                return "Classes(['%s'])".formatted(unit.fqName());
            }
        }

        @Override
        public String syntaxStyle() {
            return unit.source().getSyntaxStyle();
        }

        public CodeUnit getCodeUnit() {
            return unit;
        }
    }

    class CallGraphFragment extends VirtualFragment { // Dynamic, uses nextId
        private final String methodName;
        private final int depth;
        private final boolean isCalleeGraph; // true for callees (OUT), false for callers (IN)

        public CallGraphFragment(IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(contextManager); // Assigns dynamic numeric String ID
            assert !methodName.isBlank();
            assert depth > 0;
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public CallGraphFragment(
                String existingId,
                IContextManager contextManager,
                String methodName,
                int depth,
                boolean isCalleeGraph) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert !methodName.isBlank();
            assert depth > 0;
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.CALL_GRAPH;
        }

        @Override
        public String text() {
            var analyzer = getAnalyzer();
            final Map<String, List<CallSite>> graphData = new HashMap<>();
            final var maybeCallGraphProvider = analyzer.as(CallGraphProvider.class);

            if (maybeCallGraphProvider.isPresent()) {
                maybeCallGraphProvider.ifPresent(cpg -> {
                    if (isCalleeGraph) {
                        graphData.putAll(cpg.getCallgraphFrom(methodName, depth));
                    } else {
                        graphData.putAll(cpg.getCallgraphTo(methodName, depth));
                    }
                });
            } else {
                return "Code intelligence is not ready. Cannot generate call graph for " + methodName + ".";
            }

            if (graphData.isEmpty()) {
                return "No call graph available for " + methodName;
            }
            return AnalyzerUtil.formatCallGraph(graphData, methodName, !isCalleeGraph);
        }

        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public Set<CodeUnit> sources() {
            IAnalyzer analyzer = getAnalyzer();
            return analyzer.getDefinition(methodName)
                    .flatMap(CodeUnit::classUnit) // Get the containing class CodeUnit
                    .map(Set::of)
                    .orElse(Set.of());
        }

        @Override
        public Set<ProjectFile> files() {
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        @Override
        public String repr() {
            return isCalleeGraph
                    ? "CallGraphOut('%s', %d)".formatted(methodName, depth)
                    : "CallGraphIn('%s', %d)".formatted(methodName, depth);
        }

        @Override
        public String description() {
            String type = isCalleeGraph ? "Callees" : "Callers";
            return "%s of %s (depth %d)".formatted(type, methodName, depth);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_NONE; // Call graph is textual, not specific code language
        }

        public String getMethodName() {
            return methodName;
        }

        public int getDepth() {
            return depth;
        }

        public boolean isCalleeGraph() {
            return isCalleeGraph;
        }
    }

    enum SummaryType {
        CODEUNIT_SKELETON, // Summary for a list of FQ symbols
        FILE_SKELETONS // Summaries for all classes in a list of file paths/patterns
    }

    class SkeletonFragment extends VirtualFragment { // Dynamic, uses nextId
        private final List<String> targetIdentifiers; // FQ class names or file paths/patterns
        private final SummaryType summaryType;

        public SkeletonFragment(
                IContextManager contextManager, List<String> targetIdentifiers, SummaryType summaryType) {
            super(contextManager); // Assigns dynamic numeric String ID
            this.targetIdentifiers = List.copyOf(targetIdentifiers);
            this.summaryType = summaryType;
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public SkeletonFragment(
                String existingId,
                IContextManager contextManager,
                List<String> targetIdentifiers,
                SummaryType summaryType) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert !targetIdentifiers.isEmpty();
            this.targetIdentifiers = List.copyOf(targetIdentifiers);
            this.summaryType = summaryType;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.SKELETON;
        }

        private Map<CodeUnit, String> fetchSkeletons() {
            IAnalyzer analyzer = getAnalyzer();
            Map<CodeUnit, String> skeletonsMap = new HashMap<>();
            analyzer.as(SkeletonProvider.class).ifPresent(skeletonProvider -> {
                switch (summaryType) {
                    case CODEUNIT_SKELETON -> {
                        for (String className : targetIdentifiers) {
                            analyzer.getDefinition(className).ifPresent(cu -> {
                                skeletonProvider.getSkeleton(cu.fqName()).ifPresent(s -> skeletonsMap.put(cu, s));
                            });
                        }
                    }
                    case FILE_SKELETONS -> {
                        // This assumes targetIdentifiers are file paths. Expansion of globs should happen before
                        // fragment
                        // creation.
                        for (String filePath : targetIdentifiers) {
                            IContextManager cm = getContextManager();
                            ProjectFile projectFile = cm.toFile(filePath);
                            skeletonsMap.putAll(skeletonProvider.getSkeletons(projectFile));
                        }
                    }
                }
            });
            return skeletonsMap;
        }

        @Override
        public String text() {
            Map<CodeUnit, String> skeletons = fetchSkeletons();
            if (skeletons.isEmpty()) {
                return "No summaries found for: " + String.join(", ", targetIdentifiers);
            }

            // Group by package, then format
            var skeletonsByPackage = skeletons.entrySet().stream()
                    .collect(Collectors.groupingBy(
                            e -> e.getKey().packageName().isEmpty()
                                    ? "(default package)"
                                    : e.getKey().packageName(),
                            Collectors.toMap(
                                    Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new)));

            return skeletonsByPackage.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(pkgEntry -> {
                        String packageHeader = "package " + pkgEntry.getKey() + ";";
                        String pkgCode = String.join("\n\n", pkgEntry.getValue().values());
                        return packageHeader + "\n\n" + pkgCode;
                    })
                    .collect(Collectors.joining("\n\n"));
        }

        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public Set<CodeUnit> sources() {
            return fetchSkeletons().keySet();
        }

        @Override
        public Set<ProjectFile> files() {
            return switch (summaryType) {
                case CODEUNIT_SKELETON ->
                    sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
                case FILE_SKELETONS ->
                    targetIdentifiers.stream().map(contextManager::toFile).collect(Collectors.toSet());
            };
        }

        @Override
        public String repr() {
            return switch (summaryType) {
                case CODEUNIT_SKELETON ->
                    "ClassSummaries([%s])"
                            .formatted(targetIdentifiers.stream()
                                    .map(s -> "'" + s + "'")
                                    .collect(Collectors.joining(", ")));
                case FILE_SKELETONS ->
                    "FileSummaries([%s])"
                            .formatted(targetIdentifiers.stream()
                                    .map(s -> "'" + s + "'")
                                    .collect(Collectors.joining(", ")));
            };
        }

        @Override
        public String description() {
            return "Summary of %s".formatted(String.join(", ", targetIdentifiers));
        }

        @Override
        public boolean isEligibleForAutoContext() {
            // If it's an auto-context fragment itself, it shouldn't contribute to seeding a new auto-context.
            // User-added summaries are fine.
            // This needs a way to distinguish. For now, assume all are eligible if user-added.
            // AutoContext itself isn't represented by a SkeletonFragment that users add via tools.
            return summaryType
                    != SummaryType
                            .CODEUNIT_SKELETON; // A heuristic: auto-context typically CLASS_SKELETON of many classes
        }

        @Override
        public String format() {
            return """
                    <summary targets="%s" type="%s" fragmentid="%s">
                    %s
                    </summary>
                    """
                    .stripIndent()
                    .formatted(String.join(", ", targetIdentifiers), summaryType.name(), id(), text());
        }

        @Override
        public String formatSummary() {
            return format();
        }

        public List<String> getTargetIdentifiers() {
            return targetIdentifiers;
        }

        public SummaryType getSummaryType() {
            return summaryType;
        }

        @Override
        public String syntaxStyle() {
            // Skeletons are usually in the language of the summarized code.
            // Default to Java or try to infer from a source CodeUnit if available.
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }

        @Override
        public String toString() {
            return "SkeletonFragment('%s')".formatted(description());
        }
    }

    interface OutputFragment {
        List<TaskEntry> entries();

        /** Should raw HTML inside markdown be escaped before rendering? */
        default boolean isEscapeHtml() {
            return true;
        }
    }

    /** represents the entire Task History */
    class HistoryFragment extends VirtualFragment implements OutputFragment { // Non-dynamic, content-hashed
        private final List<TaskEntry> history; // Content is fixed once created

        public HistoryFragment(IContextManager contextManager, List<TaskEntry> history) {
            super(
                    FragmentUtils.calculateContentHash(
                            FragmentType.HISTORY,
                            "Task History (" + history.size() + " task" + (history.size() > 1 ? "s" : "") + ")",
                            TaskEntry.formatMessages(history.stream()
                                    .flatMap(e -> e.isCompressed()
                                            ? Stream.of(Messages.customSystem(castNonNull(e.summary())))
                                            : castNonNull(e.log()).messages().stream())
                                    .toList()),
                            SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                            HistoryFragment.class.getName()),
                    contextManager);
            this.history = List.copyOf(history);
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public HistoryFragment(String existingHashId, IContextManager contextManager, List<TaskEntry> history) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            this.history = List.copyOf(history);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.HISTORY;
        }

        @Override
        public List<TaskEntry> entries() {
            return history;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public String text() {
            // FIXME the right thing to do here is probably to throw UnsupportedOperationException,
            // but lots of stuff breaks without text(), so I am putting that off for another refactor
            return TaskEntry.formatMessages(history.stream()
                    .flatMap(e -> e.isCompressed()
                            ? Stream.of(Messages.customSystem(castNonNull(e.summary())))
                            : castNonNull(e.log()).messages().stream())
                    .toList());
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of();
        }

        @Override
        public String description() {
            return "Task History (" + history.size() + " task%s)".formatted(history.size() > 1 ? "s" : "");
        }

        @Override
        public String format() {
            return """
                    <taskhistory fragmentid="%s">
                    %s
                    </taskhistory>
                    """
                    .stripIndent()
                    .formatted(id(), text()); // Analyzer not used by its text()
        }

        @Override
        public String formatSummary() {
            return "";
        }

        @Override
        public String toString() {
            return "ConversationFragment(" + history.size() + " tasks)";
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }
    }

    /** represents a single session's Task History */
    class TaskFragment extends VirtualFragment implements OutputFragment { // Non-dynamic, content-hashed
        private final List<ChatMessage> messages; // Content is fixed once created

        @SuppressWarnings({"unused", "UnusedVariable"})
        private final EditBlockParser parser;

        private final String description;
        private final boolean escapeHtml;

        private static String calculateId(String sessionName, List<ChatMessage> messages) {
            return FragmentUtils.calculateContentHash(
                    FragmentType.TASK, // Or SEARCH if SearchFragment calls this path
                    sessionName,
                    TaskEntry.formatMessages(messages),
                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                    TaskFragment.class
                            .getName() // Note: SearchFragment might want its own class name if it were hashing
                    // independently
                    );
        }

        public TaskFragment(
                IContextManager contextManager,
                EditBlockParser parser,
                List<ChatMessage> messages,
                String description,
                boolean escapeHtml) {
            super(calculateId(description, messages), contextManager); // ID is content hash
            // don't break sessions saved like this
            // assert !messages.isEmpty() : "No messages provided in the task fragment";
            this.parser = parser;
            this.messages = List.copyOf(messages);
            this.description = description;
            this.escapeHtml = escapeHtml;
        }

        public TaskFragment(
                IContextManager contextManager, List<ChatMessage> messages, String description, boolean escapeHtml) {
            this(contextManager, EditBlockParser.instance, messages, description, escapeHtml);
        }

        public TaskFragment(IContextManager contextManager, List<ChatMessage> messages, String description) {
            this(contextManager, EditBlockParser.instance, messages, description, true);
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public TaskFragment(
                String existingHashId,
                IContextManager contextManager,
                EditBlockParser parser,
                List<ChatMessage> messages,
                String description,
                boolean escapeHtml) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            // don't break sessions saved like this
            // assert !messages.isEmpty() : "No messages provided in the task fragment";
            this.parser = parser;
            this.messages = List.copyOf(messages);
            this.description = description;
            this.escapeHtml = escapeHtml;
        }

        public TaskFragment(
                String existingHashId,
                IContextManager contextManager,
                EditBlockParser parser,
                List<ChatMessage> messages,
                String description) {
            this(existingHashId, contextManager, parser, messages, description, true);
        }

        public TaskFragment(
                String existingHashId,
                IContextManager contextManager,
                List<ChatMessage> messages,
                String description,
                boolean escapeHtml) {
            this(existingHashId, contextManager, EditBlockParser.instance, messages, description, escapeHtml);
        }

        public TaskFragment(
                String existingHashId, IContextManager contextManager, List<ChatMessage> messages, String description) {
            this(existingHashId, contextManager, EditBlockParser.instance, messages, description, true);
        }

        @Override
        public boolean isEscapeHtml() {
            return escapeHtml;
        }

        @Override
        public FragmentType getType() {
            // SearchFragment overrides this to return FragmentType.SEARCH
            return FragmentType.TASK;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public String text() {
            // FIXME the right thing to do here is probably to throw UnsupportedOperationException,
            // but lots of stuff breaks without text(), so I am putting that off for another refactor
            return TaskEntry.formatMessages(messages);
        }

        @Override
        public String formatSummary() {
            return format(); // if it's explicitly added to the workspace it's probably important
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        public List<ChatMessage> messages() {
            return messages;
        }

        @Override
        public List<TaskEntry> entries() {
            return List.of(new TaskEntry(-1, this, null));
        }
    }
}
