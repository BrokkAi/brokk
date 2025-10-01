package io.github.jbellis.brokk.context;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.ComputedValue;
import io.github.jbellis.brokk.util.LoggingExecutorService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.awt.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

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

    // Dedicated executor for ContextFragment async computations (separate from ContextManager backgroundTasks)
    Logger logger = LogManager.getLogger(ContextFragment.class);

    @VisibleForTesting
    static LoggingExecutorService getFragmentExecutor() {
        return FragmentExecutorHolder.INSTANCE;
    }

    final class FragmentExecutorHolder {
        static final LoggingExecutorService INSTANCE = new LoggingExecutorService(
                new ThreadPoolExecutor(
                        4,
                        Runtime.getRuntime().availableProcessors(),
                        60L,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(),
                        Executors.defaultThreadFactory()),
                th -> logger.error("Uncaught exception in ContextFragment executor", th));
    }

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
    default boolean isDynamic() {
        return this instanceof ComputedFragment;
    }

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

    static Fragments.PathFragment toPathFragment(BrokkFile bf, IContextManager contextManager) {
        if (bf.isText()) {
            if (bf instanceof ProjectFile pf) {
                return new Fragments.ProjectPathFragment(pf, contextManager); // Dynamic ID
            } else if (bf instanceof ExternalFile ext) {
                return new Fragments.ExternalPathFragment(ext, contextManager); // Dynamic ID
            }
        } else {
            // If it's not text, treat it as an image
            return new Fragments.ImageFileFragment(bf, contextManager); // Dynamic ID
        }
        // Should not happen if bf is ProjectFile or ExternalFile
        throw new IllegalArgumentException(
                "Unsupported BrokkFile subtype: " + bf.getClass().getName());
    }

    /**
     * Compares the source of this fragment with another fragment to determine if they represent
     * the "same source," allowing for content differences.
     * The comparison is based on the class name, associated files, and the fragment's representation (`repr()`).
     *
     * @param other The other ContextFragment to compare against.
     * @return true if the fragments are considered to originate from the same source, false otherwise.
     */
    default boolean hasSameSource(ContextFragment other) {
        // Compare class names
        if (!this.getClass().getName().equals(other.getClass().getName())) {
            return false;
        }
        // Compare associated files (assuming Set.equals performs content-based comparison)
        if (!this.files().equals(other.files())) {
            return false;
        }
        // Compare representation string
        return this.repr().equals(other.repr());
    }

    static boolean contentEquals(ContextFragment a, ContextFragment b) {
        if (a == b) return true;
        if (!a.getClass().getName().equals(b.getClass().getName())) {
            return false;
        }
        if (a.isText() && b.isText()) {
            return a.text().equals(b.text());
        }
        if (a instanceof ImageFragment ai && b instanceof ImageFragment bi) {
            return ai.contentHash().equals(bi.contentHash());
        }
        throw new AssertionError(a.getClass());
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
            // lowercase the first letter in description()
            return description().substring(0, 1).toLowerCase(Locale.ROOT)
                    + description().substring(1);
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

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ContextFragment other)) return false;
            return ContextFragment.contentEquals(this, other);
        }

        @Override
        public int hashCode() {
            if (isText()) {
                return text().hashCode();
            }
            if (this instanceof ImageFragment img) {
                return img.contentHash().hashCode();
            }
            throw new AssertionError(getClass());
        }
    }

    enum SummaryType {
        CODEUNIT_SKELETON, // Summary for a list of FQ symbols
        FILE_SKELETONS // Summaries for all classes in a list of file paths/patterns
    }

    /**
     * Non-breaking dynamic accessors for fragments that may compute values asynchronously.
     * Default adapters should provide completed values based on current state so legacy
     * call sites keep working without changes.
     */
    interface ComputedFragment {
        ComputedValue<String> computedText();
        ComputedValue<String> computedDescription();
        ComputedValue<String> computedSyntaxStyle();

        /**
         * Optionally provide computed image payload; default is null for non-image fragments.
         */
        default @Nullable ComputedValue<byte[]> computedImageBytes() {
            return null;
        }

        /**
         * Return a copy with cleared ComputedValues; identity (id) is preserved by default.
         * Implementations that track external state may override to trigger recomputation.
         */
        ContextFragment refreshCopy();
    }

    /**
     * Marker interface for fragments that provide image content.
     * Implementations must provide a stable content hash for equality checks.
     */
    interface ImageFragment extends ContextFragment {
        @Override
        Image image() throws UncheckedIOException;

        /**
         * A stable, cached hash of the binary image content and relevant metadata.
         */
        String contentHash();
    }

    /**
     * Base class for dynamic virtual fragments. Uses numeric String IDs and supports async computation via
     * ComputedValue exposed by DynamicFragment.
     */
    abstract class ComputedVirtualFragment extends VirtualFragment implements ComputedFragment {
        private @Nullable ComputedValue<String> textCv;
        private @Nullable ComputedValue<String> descCv;
        private @Nullable ComputedValue<String> syntaxCv;

        protected ComputedVirtualFragment(IContextManager contextManager) {
            super(contextManager);
        }

        protected ComputedVirtualFragment(String existingId, IContextManager contextManager) {
            super(existingId, contextManager);
        }

        @Override
        public ComputedValue<String> computedText() {
            if (textCv == null) {
                textCv = new ComputedValue<>(
                        "dvf-text-" + id(),
                        this::text,
                        ContextFragment.getFragmentExecutor());
            }
            return textCv;
        }

        @Override
        public ComputedValue<String> computedDescription() {
            if (descCv == null) {
                descCv = new ComputedValue<>(
                        "dvf-desc-" + id(),
                        this::description,
                        ContextFragment.getFragmentExecutor());
            }
            return descCv;
        }

        @Override
        public ComputedValue<String> computedSyntaxStyle() {
            if (syntaxCv == null) {
                syntaxCv = new ComputedValue<>(
                        "dvf-syntax-" + id(),
                        this::syntaxStyle,
                        ContextFragment.getFragmentExecutor());
            }
            return syntaxCv;
        }
    }

    /**
     * Base class for dynamic path fragments. Marker for dynamic behavior on top of PathFragment.
     */
    non-sealed abstract class DynamicPathFragment implements Fragments.PathFragment, ComputedFragment {
        private @Nullable ComputedValue<String> textCv;
        private @Nullable ComputedValue<String> descCv;
        private @Nullable ComputedValue<String> syntaxCv;

        protected DynamicPathFragment() {}

        @Override
        public ComputedValue<String> computedText() {
            if (textCv == null) {
                textCv = new ComputedValue<>(
                        "dpf-text-" + id(),
                        this::text,
                        ContextFragment.getFragmentExecutor());
            }
            return textCv;
        }

        @Override
        public ComputedValue<String> computedDescription() {
            if (descCv == null) {
                descCv = new ComputedValue<>(
                        "dpf-desc-" + id(),
                        this::description,
                        ContextFragment.getFragmentExecutor());
            }
            return descCv;
        }

        @Override
        public ComputedValue<String> computedSyntaxStyle() {
            if (syntaxCv == null) {
                syntaxCv = new ComputedValue<>(
                        "dpf-syntax-" + id(),
                        this::syntaxStyle,
                        ContextFragment.getFragmentExecutor());
            }
            return syntaxCv;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ContextFragment other)) return false;
            return ContextFragment.contentEquals(this, other);
        }

        @Override
        public int hashCode() {
            if (isText()) {
                return text().hashCode();
            }
            if (this instanceof ImageFragment img) {
                return img.contentHash().hashCode();
            }
            throw new AssertionError(getClass());
        }
    }

    // PF is the rare CVF that is NOT dynamic; we want to compute its description (via slow LLM), but the actual content never changes
    abstract class PasteFragment extends ComputedVirtualFragment {
        protected transient Future<String> descriptionFuture;
        private final ComputedValue<String> descriptionCv;

        // PasteFragments are non-dynamic (content-hashed)
        // The hash will be based on the initial text/image data, not the future description.
        public PasteFragment(String id, IContextManager contextManager, Future<String> descriptionFuture) {
            super(id, contextManager);
            this.descriptionFuture = descriptionFuture;
            // eagerly compute description using background executor
            this.descriptionCv = new ComputedValue<>(
                    "paste-desc-" + id,
                    () -> {
                        try {
                            return "Paste of " + descriptionFuture.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    ContextFragment.getFragmentExecutor());
        }

        // Pre-seeded constructor to avoid recomputation when loading from history
        public PasteFragment(String id, IContextManager contextManager, String precomputedDescription) {
            super(id, contextManager);
            this.descriptionFuture = CompletableFuture.completedFuture(precomputedDescription);
            this.descriptionCv = ComputedValue.completed("paste-desc-" + id, "Paste of " + precomputedDescription);
        }

        @Override
        public String description() {
            return descriptionCv.renderNowOr("(Loading...)");
        }

        @Override
        public String toString() {
            return "PasteFragment('%s')".formatted(description());
        }

        public Future<String> getDescriptionFuture() {
            return descriptionFuture;
        }

        @Override
        public ComputedValue<String> computedDescription() {
            return descriptionCv;
        }

        @Override
        public boolean isDynamic() {
            return false;
        }

        @Override
        public ContextFragment refreshCopy() {
            // Paste fragments are self-freezing; we don't need to recompute or clone.
            // Keeping the same instance preserves the content-hash id and ComputedValues.
            return this;
        }
    }
}
