package io.github.jbellis.brokk.context;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.AnalyzerUtil;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.gui.GitUiUtil;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.util.FragmentUtils;
import io.github.jbellis.brokk.util.Messages;
import org.fife.ui.rsyntaxtextarea.FileTypeUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ContextFragment methods do not throw checked exceptions, which make it difficult to use in Streams
 * Instead, it throws UncheckedIOException or CancellationException for IOException/InterruptedException, respectively;
 * freeze() will throw the checked variants at which point the caller should deal with the interruption or
 * remove no-longer-valid Fragments
 */
public interface ContextFragment {
    enum FragmentType {
        PROJECT_PATH(true, false, false), // Dynamic ID
        GIT_FILE(true, false, false),     // Content-hashed ID
        EXTERNAL_PATH(true, false, false),// Dynamic ID
        IMAGE_FILE(true, false, false),   // Dynamic ID, isText=false for this path fragment

        STRING(false, true, false),       // Content-hashed ID
        SEARCH(false, true, true),        // Content-hashed ID (SearchFragment extends TaskFragment)
        SKELETON(false, true, false),     // Dynamic ID
        USAGE(false, true, false),        // Dynamic ID
        CALL_GRAPH(false, true, false),   // Dynamic ID
        HISTORY(false, true, true),       // Content-hashed ID
        TASK(false, true, true),          // Content-hashed ID
        PASTE_TEXT(false, true, false),   // Content-hashed ID
        PASTE_IMAGE(false, true, false),  // Content-hashed ID, isText=false for this virtual fragment
        STACKTRACE(false, true, false);   // Content-hashed ID

        private final boolean isPath;
        private final boolean isVirtual;
        private final boolean isOutput;

        FragmentType(boolean isPath, boolean isVirtual, boolean isOutput) {
            this.isPath = isPath;
            this.isVirtual = isVirtual;
            this.isOutput = isOutput;
        }

        public boolean isPathFragment() { return isPath; }
        public boolean isVirtualFragment() { return isVirtual; }
        public boolean isOutputFragment() { return isOutput; }
    }

    // Static counter for dynamic fragments
    AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Gets the current max integer fragment ID used for generating new dynamic fragment IDs.
     * Note: This refers to the numeric part of dynamic IDs.
     */
    static int getCurrentMaxId() {
        return nextId.get();
    }

    /**
     * Sets the next integer fragment ID value, typically called during deserialization
     * to ensure new dynamic fragment IDs don't collide with loaded numeric IDs.
     */
    static void setMinimumId(int value) {
        if (value > nextId.get()) {
            nextId.set(value);
        }
    }

    /**
     * Unique identifier for this fragment. Can be a numeric string for dynamic fragments
     * or a hash string for static/frozen fragments.
     */
    String id();

    /**
     * The type of this fragment.
     */
    FragmentType getType();

    /**
     * short description in history
     */
    String shortDescription();

    /**
     * longer description displayed in context table
     */
    String description();

    /**
     * raw content for preview
     */
    String text() throws UncheckedIOException, CancellationException;

    /**
     * content formatted for LLM
     */
    String format() throws UncheckedIOException, CancellationException;

    /**
     * Indicates if the fragment's content can change based on project/file state.
     */
    boolean isDynamic();

    /**
     * for Quick Context LLM
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
     * code sources found in this fragment
     */
    Set<CodeUnit> sources();

    /**
     * Returns all repo files referenced by this fragment.
     * This is used when we *just* want to manipulate or show actual files,
     * rather than the code units themselves.
     */
    Set<ProjectFile> files();

    String syntaxStyle();

    default ContextFragment unfreeze(IContextManager cm) throws IOException {
        return this;
    }

    /**
     * If false, the classes returned by sources() will be pruned from AutoContext suggestions.
     * (Corollary: if sources() always returns empty, this doesn't matter.)
     */
    default boolean isEligibleForAutoContext() {
        return true;
    }

    /**
     * Retrieves the {@link IContextManager} associated with this fragment.
     *
     * @return The context manager instance, or {@code null} if not applicable or available.
     */
    IContextManager getContextManager();

    /**
     * Convenience method to get the analyzer in a non-blocking way using the fragment's context manager.
     *
     * @return The IAnalyzer instance if available, or null if it's not ready yet or if the context manager is not available.
     */
    default IAnalyzer getAnalyzer() {
        return getContextManager().getAnalyzerUninterrupted();
    }

    static Set<ProjectFile> parseProjectFiles(String text, IProject project) {
        var exactMatches = project.getAllFiles().stream().parallel()
                .filter(f -> text.contains(f.toString()))
                .collect(Collectors.toSet());
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }

        return project.getAllFiles().stream().parallel()
                .filter(f -> text.contains(f.getFileName()))
                .collect(Collectors.toSet());
    }

    sealed interface PathFragment extends ContextFragment
            permits ProjectPathFragment, GitFileFragment, ExternalPathFragment, ImageFileFragment
    {
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
            try {
                return file().read();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        default String syntaxStyle() {
            return FileTypeUtil.get().guessContentType(file().absPath().toFile());
        }

        @Override
    default String format() {
        // PathFragments are dynamic, but their text() doesn't need the analyzer here
        // as it reads directly from the file.
        return """
                <file path="%s" fragmentid="%s">
                %s
                </file>
                """.stripIndent().formatted(file().toString(), id(), text());
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

    // Record canonical constructor - ensures `id` is properly set
    public ProjectPathFragment {
        Objects.requireNonNull(file);
        Objects.requireNonNull(id); // id is now always String
        // contextManager can be null for some test/serialization cases if handled by callers
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
        Objects.requireNonNull(existingId);
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
            if (analyzer.isEmpty()) {
                return PathFragment.formatSummary(file); // Fallback if analyzer not ready or empty
            }
            var summary = analyzer.getSkeletons(file).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .collect(Collectors.joining("\n"));

            return """
                   <file source="%s" summarized=true>
                   %s
                   </file>
                   """.formatted(file, summary);
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
        return Objects.equals(id(), that.id());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id());
    }
}

/**
 * Represents a specific revision of a ProjectFile from Git history. This is non-dynamic.
 */
record GitFileFragment(ProjectFile file, String revision, String content, String id) implements PathFragment {
    public GitFileFragment(ProjectFile file, String revision, String content) {
        this(file, revision, content, FragmentUtils.calculateContentHash(
                FragmentType.GIT_FILE,
                String.format("%s @%s", file.getFileName(), GitUiUtil.shortenCommitId(revision)), // description for hash
                content, // text content for hash
                FileTypeUtil.get().guessContentType(file.absPath().toFile()), // syntax style for hash
                GitFileFragment.class.getName() // original class name for hash
        ));
    }

    // Record canonical constructor
    public GitFileFragment {
        Objects.requireNonNull(file);
        Objects.requireNonNull(revision);
        Objects.requireNonNull(content);
        Objects.requireNonNull(id); // ID is content hash
    }

    @Override
    public FragmentType getType() {
        return FragmentType.GIT_FILE;
    }

    @Override
    public IContextManager getContextManager() {
        return null; // GitFileFragment does not have a context manager
    }

    // Constructor for use with DTOs where ID is already known (expected to be a hash)
    public static GitFileFragment withId(ProjectFile file, String revision, String content, String existingId) {
        // For GitFileFragment, existingId is expected to be the content hash.
        // No need to update ContextFragment.nextId.
        return new GitFileFragment(file, revision, content, existingId);
        }

        private String shortRevision() {
            return GitUiUtil.shortenCommitId(revision);
        }

        @Override
        public String shortDescription() {
            return "%s @%s".formatted(file().getFileName(), shortRevision());
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
                """.stripIndent().formatted(file().toString(), revision(), text());
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
        return Objects.equals(id(), that.id());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id());
    }

    @Override
    public String toString() {
        return "GitFileFragment('%s' @%s)".formatted(file, shortRevision());
    }
}

record ExternalPathFragment(ExternalFile file, String id, IContextManager contextManager) implements PathFragment {
    // Primary constructor for new dynamic fragments
    public ExternalPathFragment(ExternalFile file, IContextManager contextManager) {
        this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
    }

    // Record canonical constructor
    public ExternalPathFragment {
        Objects.requireNonNull(file);
        Objects.requireNonNull(id);
    }

    @Override
    public FragmentType getType() {
        return FragmentType.EXTERNAL_PATH;
    }

    @Override
    public IContextManager getContextManager() {
        return contextManager;
    }

    public static ExternalPathFragment withId(ExternalFile file, String existingId, IContextManager contextManager) {
        Objects.requireNonNull(existingId);
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
        return Objects.equals(id(), that.id());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id());
    }
}

/**
 * Represents an image file, either from the project or external. This is dynamic.
 */
record ImageFileFragment(BrokkFile file, String id, IContextManager contextManager) implements PathFragment {
    // Primary constructor for new dynamic fragments
    public ImageFileFragment(BrokkFile file, IContextManager contextManager) {
        this(file, String.valueOf(ContextFragment.nextId.getAndIncrement()), contextManager);
    }

    // Record canonical constructor
    public ImageFileFragment {
        Objects.requireNonNull(file);
        Objects.requireNonNull(id);
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
        Objects.requireNonNull(existingId);
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
                return javax.imageio.ImageIO.read(file.absPath().toFile());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
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
                """.stripIndent().formatted(file().toString(), id());
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
        return Objects.equals(id(), that.id());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id());
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
    throw new IllegalArgumentException("Unsupported BrokkFile subtype: " + bf.getClass().getName());
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
        Objects.requireNonNull(existingId);
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
                """.stripIndent().formatted(description(), id(), text());
        }

        @Override
        public String shortDescription() {
            assert !description().isEmpty();
            // lowercase the first letter in description()
            return description().substring(0, 1).toLowerCase(Locale.ROOT) + description().substring(1);
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

class StringFragment extends VirtualFragment { // Non-dynamic, uses content hash
    private final String text;
    private final String description;
    private final String syntaxStyle;

    public StringFragment(IContextManager contextManager, String text, String description, String syntaxStyle) {
        super(FragmentUtils.calculateContentHash(
                FragmentType.STRING,
                description,
                text,
                syntaxStyle,
                StringFragment.class.getName()),
              contextManager);
        this.syntaxStyle = syntaxStyle;
        assert text != null;
        assert description != null;
        this.text = text;
        this.description = description;
    }

    // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
    public StringFragment(String existingHashId, IContextManager contextManager, String text, String description, String syntaxStyle) {
        super(existingHashId, contextManager); // existingHashId is expected to be a content hash
        this.syntaxStyle = syntaxStyle;
        assert text != null;
        assert description != null;
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

        public SearchFragment(IContextManager contextManager, String sessionName, List<ChatMessage> messages, Set<CodeUnit> sources) {
            // The ID (hash) is calculated by the TaskFragment constructor based on sessionName and messages.
            super(contextManager, messages, sessionName);
            assert sources != null;
            this.sources = sources;
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public SearchFragment(String existingHashId, IContextManager contextManager, String sessionName, List<ChatMessage> messages, Set<CodeUnit> sources) {
            super(existingHashId, contextManager, EditBlockParser.instance, messages, sessionName); // existingHashId is expected to be a content hash
            assert sources != null;
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

    // This constructor is for subclasses to call after they've computed their ID (hash)
    // The nextId based constructor from VirtualFragment is not suitable here.
    // public PasteFragment(IContextManager contextManager, Future<String> descriptionFuture) {
    //    super(contextManager); // This would assign a dynamic ID, which is not desired.
    //    this.descriptionFuture = descriptionFuture;
    // }


    @Override
        public boolean isDynamic() {
            // technically is dynamic b/c of Future but it is simpler to treat as non-dynamic, we can live with the corner case
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
    }

    class PasteTextFragment extends PasteFragment { // Non-dynamic, content-hashed
        private final String text;

        public PasteTextFragment(IContextManager contextManager, String text, Future<String> descriptionFuture) {
            super(FragmentUtils.calculateContentHash(
                    FragmentType.PASTE_TEXT,
                    "(Pasting text)", // Initial description for hashing before future completes
                    text,
                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN, // Default syntax style for hashing
                    PasteTextFragment.class.getName()),
                  contextManager, descriptionFuture);
            assert text != null;
            assert descriptionFuture != null;
            this.text = text;
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public PasteTextFragment(String existingHashId, IContextManager contextManager, String text, Future<String> descriptionFuture) {
            super(existingHashId, contextManager, descriptionFuture); // existingHashId is expected to be a content hash
            assert text != null;
            assert descriptionFuture != null;
            this.text = text;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PASTE_TEXT;
        }

        @Override
        public String syntaxStyle() {
            // TODO infer from contents
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN;
        }

        @Override
        public String text() {
            return text;
        }
    }

    class AnonymousImageFragment extends PasteFragment { // Non-dynamic, content-hashed
        private final Image image;
        // Helper to get bytes for hashing, might throw UncheckedIOException
        private static byte[] imageToBytesForHash(Image image) {
            try {
                // Assuming FrozenFragment.imageToBytes will be made public
                return FrozenFragment.imageToBytes(image); 
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public AnonymousImageFragment(IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(FragmentUtils.calculateContentHash(
                    FragmentType.PASTE_IMAGE,
                    "(Pasting image)", // Initial description for hashing
                    null, // No text content for image
                    imageToBytesForHash(image), // image bytes for hashing
                    false, // isTextFragment = false
                    SyntaxConstants.SYNTAX_STYLE_NONE,
                    Set.of(), // No project files
                    AnonymousImageFragment.class.getName(),
                    Map.of()), // No specific meta for hashing
                  contextManager, descriptionFuture);
            assert image != null;
            assert descriptionFuture != null;
            this.image = image;
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public AnonymousImageFragment(String existingHashId, IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(existingHashId, contextManager, descriptionFuture); // existingHashId is expected to be a content hash
            assert image != null;
            assert descriptionFuture != null;
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
              """.stripIndent().formatted(description(), id(), text());
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
    }

    class StacktraceFragment extends VirtualFragment { // Non-dynamic, content-hashed
        private final Set<CodeUnit> sources; // Pre-computed, so not dynamic in content
        private final String original;
        private final String exception;
        private final String code; // Pre-computed code parts

        public StacktraceFragment(IContextManager contextManager, Set<CodeUnit> sources, String original, String exception, String code) {
            super(FragmentUtils.calculateContentHash(
                    FragmentType.STACKTRACE,
                    "stacktrace of " + exception,
                    original + "\n\nStacktrace methods in this project:\n\n" + code, // Full text for hash
                    sources.isEmpty() ? SyntaxConstants.SYNTAX_STYLE_NONE : sources.iterator().next().source().getSyntaxStyle(),
                    StacktraceFragment.class.getName()),
                  contextManager);
            assert sources != null;
            assert original != null;
            assert exception != null;
            assert code != null;
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public StacktraceFragment(String existingHashId, IContextManager contextManager, Set<CodeUnit> sources, String original, String exception, String code) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            assert sources != null;
            assert original != null;
            assert exception != null;
            assert code != null;
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

    static String toClassname(String methodname) {
        int lastDot = methodname.lastIndexOf('.');
        if (lastDot == -1) {
            return methodname;
        }
        return methodname.substring(0, lastDot);
    }

    class UsageFragment extends VirtualFragment { // Dynamic, uses nextId
        private final String targetIdentifier;

        public UsageFragment(IContextManager contextManager, String targetIdentifier) {
            super(contextManager); // Assigns dynamic numeric String ID
            assert targetIdentifier != null && !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public UsageFragment(String existingId, IContextManager contextManager, String targetIdentifier) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert targetIdentifier != null && !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
        }

        @Override
        public FragmentType getType() {
            return FragmentType.USAGE;
        }

        @Override
        public String text() {
            IAnalyzer analyzer = getAnalyzer();
            if (!analyzer.isCpg()) {
                return "Code intelligence is not ready. Cannot find usages for " + targetIdentifier + ".";
            }
            List<CodeUnit> uses = analyzer.getUses(targetIdentifier);
            var result = AnalyzerUtil.processUsages(analyzer, uses);
            return result.code().isEmpty() ? "No relevant usages found for symbol: " + targetIdentifier : result.code();
        }

        @Override
        public boolean isDynamic() {
            return true;
        }

        @Override
        public Set<CodeUnit> sources() {
            IAnalyzer analyzer = getAnalyzer();
            List<CodeUnit> uses = analyzer.getUses(targetIdentifier);
            var result = AnalyzerUtil.processUsages(analyzer, uses);
            return result.sources();
        }

        @Override
        public Set<ProjectFile> files() {
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
        }

        @Override
        public String description() {
            return "Uses of %s".formatted(targetIdentifier);
        }

        @Override
        public String syntaxStyle() {
            // Syntax can vary based on the language of the usages.
            // Default to Java or try to infer from a source CodeUnit if available.
            // For simplicity, returning Java, but this could be improved.
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }

        public String targetIdentifier() {
            return targetIdentifier;
        }
    }

    class CallGraphFragment extends VirtualFragment { // Dynamic, uses nextId
        private final String methodName;
        private final int depth;
        private final boolean isCalleeGraph; // true for callees (OUT), false for callers (IN)

        public CallGraphFragment(IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(contextManager); // Assigns dynamic numeric String ID
            assert methodName != null && !methodName.isBlank();
            assert depth > 0;
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public CallGraphFragment(String existingId, IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert methodName != null && !methodName.isBlank();
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
            IAnalyzer analyzer = getAnalyzer();
            if (!analyzer.isCpg()) {
                return "Code intelligence is not ready. Cannot generate call graph for " + methodName + ".";
            }
            Map<String, List<CallSite>> graphData;
            if (isCalleeGraph) {
                graphData = analyzer.getCallgraphFrom(methodName, depth);
            } else {
                graphData = analyzer.getCallgraphTo(methodName, depth);
            }

            if (graphData.isEmpty()) {
                return "No call graph available for " + methodName;
            }
            return AnalyzerUtil.formatCallGraph(graphData, methodName, isCalleeGraph);
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
        public String description() {
            String type = isCalleeGraph ? "Callees" : "Callers";
            return "%s of %s (depth %d)".formatted(type, methodName, depth);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_NONE; // Call graph is textual, not specific code language
        }

        public String getMethodName() { return methodName; }
        public int getDepth() { return depth; }
        public boolean isCalleeGraph() { return isCalleeGraph; }
    }

    enum SummaryType {
        CLASS_SKELETON, // Summary for a list of FQ class names
        FILE_SKELETONS  // Summaries for all classes in a list of file paths/patterns
    }

    class SkeletonFragment extends VirtualFragment { // Dynamic, uses nextId
        private final List<String> targetIdentifiers; // FQ class names or file paths/patterns
        private final SummaryType summaryType;

        public SkeletonFragment(IContextManager contextManager, List<String> targetIdentifiers, SummaryType summaryType) {
            super(contextManager); // Assigns dynamic numeric String ID
            assert targetIdentifiers != null;
            assert summaryType != null;
            this.targetIdentifiers = List.copyOf(targetIdentifiers);
            this.summaryType = summaryType;
        }

        // Constructor for DTOs/unfreezing where ID might be a numeric string or hash (if frozen)
        public SkeletonFragment(String existingId, IContextManager contextManager, List<String> targetIdentifiers, SummaryType summaryType) {
            super(existingId, contextManager); // Handles numeric ID parsing for nextId
            assert targetIdentifiers != null && !targetIdentifiers.isEmpty();
            assert summaryType != null;
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
            switch (summaryType) {
                case CLASS_SKELETON -> {
                    for (String className : targetIdentifiers) {
                        analyzer.getDefinition(className).ifPresent(cu -> {
                            if (cu.isClass()) { // Ensure it's a class for getSkeleton
                                analyzer.getSkeleton(cu.fqName()).ifPresent(s -> skeletonsMap.put(cu, s));
                            }
                        });
                    }
                }
                case FILE_SKELETONS -> {
                    // This assumes targetIdentifiers are file paths. Expansion of globs should happen before fragment creation.
                    for (String filePath : targetIdentifiers) {
                        IContextManager cm = getContextManager();
                        if (cm != null) {
                            ProjectFile projectFile = cm.toFile(filePath);
                            skeletonsMap.putAll(analyzer.getSkeletons(projectFile));
                        }
                    }
                }
            }
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
                            e -> e.getKey().packageName().isEmpty() ? "(default package)" : e.getKey().packageName(),
                            Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v1, LinkedHashMap::new)
                    ));

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
                case CLASS_SKELETON -> sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
                case FILE_SKELETONS -> targetIdentifiers.stream().map(contextManager::toFile).collect(Collectors.toSet());
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
            return summaryType != SummaryType.CLASS_SKELETON; // A heuristic: auto-context typically CLASS_SKELETON of many classes
        }

        @Override
        public String format() {
            return """
                    <summary targets="%s" type="%s" fragmentid="%s">
                    %s
                    </summary>
                    """.stripIndent().formatted(
                    String.join(", ", targetIdentifiers),
                    summaryType.name(),
                    id(),
                    text() // No analyzer
            );
        }

        @Override
        public String formatSummary() {
            return format();
        }

        public List<String> getTargetIdentifiers() { return targetIdentifiers; }
        public SummaryType getSummaryType() { return summaryType; }

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

    /**
     * represents the entire Task History
     */
    class HistoryFragment extends VirtualFragment implements OutputFragment { // Non-dynamic, content-hashed
        private final List<TaskEntry> history; // Content is fixed once created

        public HistoryFragment(IContextManager contextManager, List<TaskEntry> history) {
            super(FragmentUtils.calculateContentHash(
                    FragmentType.HISTORY,
                    "Task History (" + history.size() + " task" + (history.size() > 1 ? "s" : "") + ")",
                    TaskEntry.formatMessages(history.stream().flatMap(e -> e.isCompressed()
                                                                          ? Stream.of(Messages.customSystem(e.summary()))
                                                                          : e.log().messages().stream()).toList()),
                    SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                    HistoryFragment.class.getName()),
                  contextManager);
            assert history != null;
            this.history = List.copyOf(history);
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public HistoryFragment(String existingHashId, IContextManager contextManager, List<TaskEntry> history) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            assert history != null;
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
        public boolean isText() {
            return true;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public String text() {
            // FIXME the right thing to do here is probably to throw UnsupportedOperationException,
            // but lots of stuff breaks without text(), so I am putting that off for another refactor
            return TaskEntry.formatMessages(history.stream().flatMap(e -> e.isCompressed()
                                                                          ? Stream.of(Messages.customSystem(e.summary()))
                                                                          : e.log().messages().stream()).toList());
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
                    """.stripIndent().formatted(id(), text()); // Analyzer not used by its text()
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

    /**
     * represents a single session's Task History
     */
    class TaskFragment extends VirtualFragment implements OutputFragment { // Non-dynamic, content-hashed
        private final EditBlockParser parser; // TODO this doesn't belong in TaskFragment anymore
        private final List<ChatMessage> messages; // Content is fixed once created
        private final String sessionName;
        private final boolean escapeHtml;

        private static String calculateId(String sessionName, List<ChatMessage> messages) {
            return FragmentUtils.calculateContentHash(
                FragmentType.TASK, // Or SEARCH if SearchFragment calls this path
                sessionName,
                TaskEntry.formatMessages(messages),
                SyntaxConstants.SYNTAX_STYLE_MARKDOWN,
                TaskFragment.class.getName() // Note: SearchFragment might want its own class name if it were hashing independently
            );
        }

        public TaskFragment(IContextManager contextManager, EditBlockParser parser, List<ChatMessage> messages, String sessionName, boolean escapeHtml) {
            super(calculateId(sessionName, messages), contextManager); // ID is content hash
            this.parser = parser;
            this.messages = List.copyOf(messages);
            this.sessionName = sessionName;
            this.escapeHtml = escapeHtml;
        }

        public TaskFragment(IContextManager contextManager, EditBlockParser parser, List<ChatMessage> messages, String sessionName) {
            this(contextManager, parser, messages, sessionName, true);
        }

        public TaskFragment(IContextManager contextManager, List<ChatMessage> messages, String sessionName, boolean escapeHtml) {
            this(contextManager, EditBlockParser.instance, messages, sessionName, escapeHtml);
        }

        public TaskFragment(IContextManager contextManager, List<ChatMessage> messages, String sessionName) {
            this(contextManager, EditBlockParser.instance, messages, sessionName, true);
        }

        // Constructor for DTOs/unfreezing where ID is a pre-calculated hash
        public TaskFragment(String existingHashId, IContextManager contextManager, EditBlockParser parser, List<ChatMessage> messages, String sessionName, boolean escapeHtml) {
            super(existingHashId, contextManager); // existingHashId is expected to be a content hash
            this.parser = parser;
            this.messages = List.copyOf(messages);
            this.sessionName = sessionName;
            this.escapeHtml = escapeHtml;
        }

        public TaskFragment(String existingHashId, IContextManager contextManager, EditBlockParser parser, List<ChatMessage> messages, String sessionName) {
            this(existingHashId, contextManager, parser, messages, sessionName, true);
        }

        public TaskFragment(String existingHashId, IContextManager contextManager, List<ChatMessage> messages, String sessionName, boolean escapeHtml) {
            this(existingHashId, contextManager, EditBlockParser.instance, messages, sessionName, escapeHtml);
        }

        public TaskFragment(String existingHashId, IContextManager contextManager, List<ChatMessage> messages, String sessionName) {
            this(existingHashId, contextManager, EditBlockParser.instance, messages, sessionName, true);
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
        public boolean isText() {
            return true;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public String description() {
            return sessionName;
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

        public EditBlockParser parser() {
            return parser;
        }
    }
}
