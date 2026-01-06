package ai.brokk.context;

import static java.util.Objects.requireNonNull;

import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Asynchronous ContextFragment API.
 * <p>
 * All fragments return ComputedValue<T> for their public data surfaces to avoid implicit blocking.
 * Callers must explicitly block if they require synchronous behavior (e.g., via future().join()).
 * <p>
 * This is a breaking change; do not expect call sites to compile until they are updated.
 *
 * <p>ContextFragment MUST be kept in sync with FrozenFragment: any polymorphic methods added to CF must be serialized
 * into FF so they can be accurately represented as well. If you are tasked with adding such a method to CF without also
 * having FF available to edit, you MUST decline the assignment and explain the problem.
 */
public interface ContextFragment {

    @Blocking
    default boolean contentEquals(ContextFragment other) {
        if (!hasSameSource(other)) {
            return false;
        }

        if (isText()) {
            return text().join().equals(other.text().join());
        }

        return Arrays.equals(
                requireNonNull(imageBytes()).join(),
                requireNonNull(other.imageBytes()).join());
    }

    /**
     * Replaces polymorphic methods or instanceof checks with something that can easily apply to FrozenFragments as well
     */
    enum FragmentType {
        PROJECT_PATH,
        GIT_FILE,
        EXTERNAL_PATH,
        IMAGE_FILE,

        STRING,
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

        private static final EnumSet<FragmentType> OUTPUT_TYPES = EnumSet.of(HISTORY, TASK);

        private static final EnumSet<FragmentType> EDITABLE_TYPES = EnumSet.of(PROJECT_PATH, USAGE, CODE);

        private static final EnumSet<FragmentType> PROJECT_GUIDE_TYPES = EnumSet.of(PROJECT_PATH, CODE, SKELETON);

        public boolean isPath() {
            return PATH_TYPES.contains(this);
        }

        public boolean isOutput() {
            return OUTPUT_TYPES.contains(this);
        }

        public boolean isEditable() {
            return EDITABLE_TYPES.contains(this);
        }

        public boolean includeInProjectGuide() {
            return PROJECT_GUIDE_TYPES.contains(this);
        }
    }

    static String describe(Collection<ContextFragment> fragments) {
        return describe(fragments.stream());
    }

    @Blocking
    static String describe(Stream<ContextFragment> fragments) {
        return fragments
                .map(cf -> cf.description().join())
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    // Static counter for dynamic fragments
    AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Extracts ProjectFile references from a pasted list of file paths.
     */
    static Set<ProjectFile> extractFilesFromText(String text, IContextManager contextManager) {
        return contextManager.getProject().getAllFiles().parallelStream()
                .filter(f -> text.contains(f.toString()))
                .collect(Collectors.toSet());
    }

    /**
     * Gets the current max integer fragment ID used for generating new dynamic fragment IDs. Note: This refers to the
     * numeric part of dynamic IDs.
     */
    static int getCurrentMaxId() {
        return nextId.get();
    }

    /**
     * Unique identifier for this fragment. Can be a numeric string for dynamic fragments or a hash string for
     * static/frozen fragments.
     */
    String id();

    /**
     * The type of this fragment.
     */
    FragmentType getType();

    /**
     * short description in history (defaults to description).
     */
    default ComputedValue<String> shortDescription() {
        return description();
    }

    /**
     * longer description displayed in context table
     */
    ComputedValue<String> description();

    /**
     * raw content for preview
     */
    ComputedValue<String> text();

    /**
     * fragment toc entry, usually id + description
     */
    default String formatToc(boolean isPinned) {
        // Non-blocking best-effort rendering
        String idOrPinned = isPinned ? "pinned=\"true\"" : "fragmentid=\"%s\"".formatted(id());
        return """
                <fragment-toc description="%s" %s />"""
                .formatted(description().renderNowOr(""), idOrPinned);
    }

    default boolean isText() {
        return true;
    }

    /**
     * Optionally provide computed image payload; default is null for non-image fragments.
     */
    default @Nullable ComputedValue<byte[]> imageBytes() {
        return null;
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
     */
    ComputedValue<Set<CodeUnit>> sources();

    /**
     * Returns all repo files referenced by this fragment. This is used when we *just* want to manipulate or show actual
     * files, rather than the code units themselves.
     */
    ComputedValue<Set<ProjectFile>> files();

    /**
     * Syntax highlight style.
     */
    ComputedValue<String> syntaxStyle();

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
     * For live fragments ONLY, isValid reflects current external state (a file fragment whose file is missing is invalid);
     * historical/frozen fragments have already snapshotted their state and are always valid.
     *
     * In-progress-of-snapshotting fragments are treated as valid to avoid @Blocking, but this means that it's
     * possible for it to flip from valid to invalid if it is unable to snapshot its content
     * before the source is removed out from under it.
     *
     * @return true if the fragment is valid, false otherwise
     */
    default boolean isValid() {
        return true;
    }

    /**
     * Compares whether two fragments originate from the same "source" (file/symbol/session),
     * ignoring view parameters and content differences when that makes sense.
     */
    boolean hasSameSource(ContextFragment other);

    /**
     * Return a copy with cleared ComputedValues; identity (id) is preserved by default.
     * Implementations that track external state may override to trigger recomputation.
     */
    ContextFragment refreshCopy();

    /**
     * Interface for fragments that involve asynchronous computation.
     */
    interface ComputedFragment extends ContextFragment {
        /**
         * Blocks until the fragment's computation is complete or the timeout expires.
         */
        boolean await(Duration timeout) throws InterruptedException;

        /**
         * Registers a callback to be executed when the fragment's computation completes.
         */
        @Nullable
        ComputedValue.Subscription onComplete(Runnable runnable);
    }

    /**
     * Marker interface for fragments that provide image content.
     * Implementations must provide a stable content hash for equality checks.
     */
    interface ImageFragment extends ContextFragment {
        /**
         * A stable, cached hash of the binary image content and relevant metadata.
         */
        String contentHash();
    }

    static ContextFragments.PathFragment toPathFragment(BrokkFile bf, IContextManager contextManager) {
        if (bf.isText()) {
            if (bf instanceof ProjectFile pf) return new ContextFragments.ProjectPathFragment(pf, contextManager);
            if (bf instanceof ExternalFile ext) return new ContextFragments.ExternalPathFragment(ext, contextManager);
        } else {
            return new ContextFragments.ImageFileFragment(bf, contextManager);
        }
        throw new IllegalArgumentException(
                "Unsupported BrokkFile subtype: " + bf.getClass().getName());
    }

    enum SummaryType {
        CODEUNIT_SKELETON,
        FILE_SKELETONS
    }
}
