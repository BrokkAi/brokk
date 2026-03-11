package ai.brokk.context;

import static java.util.Objects.requireNonNull;

import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.util.Lines;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
 */
public interface ContextFragment {
    Logger logger = LogManager.getLogger(ContextFragment.class);

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
     * Replaces polymorphic methods or instanceof checks with something that can easily apply to test fragments.
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
        LINE_RANGE,
        HISTORY,
        TASK,
        PASTE_TEXT,
        PASTE_IMAGE,
        STACKTRACE;

        private static final EnumSet<FragmentType> PATH_TYPES =
                EnumSet.of(PROJECT_PATH, GIT_FILE, EXTERNAL_PATH, IMAGE_FILE);

        private static final EnumSet<FragmentType> OUTPUT_TYPES = EnumSet.of(HISTORY, TASK);

        private static final EnumSet<FragmentType> EDITABLE_TYPES = EnumSet.of(PROJECT_PATH, USAGE, CODE, LINE_RANGE);

        private static final EnumSet<FragmentType> PROJECT_GUIDE_TYPES =
                EnumSet.of(PROJECT_PATH, CODE, LINE_RANGE, SKELETON);

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

    @Blocking
    static String describe(Stream<ContextFragment> fragments) {
        return fragments
                .map(cf -> cf.description().join())
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Extracts ProjectFile references from a pasted list of file paths.
     */
    static Set<ProjectFile> extractFilesFromText(String text, IContextManager contextManager) {
        return contextManager.getProject().getAllFiles().parallelStream()
                .filter(f -> Lines.containsBareToken(text, f.toString()))
                .collect(Collectors.toSet());
    }

    /**
     * Set of fragments that should be added alongside this one.
     * <p>
     * This method is called during {@link Context#addFragments(Collection)} to expand a fragment with related context.
     */
    @Blocking
    default Set<ContextFragment> supportingFragments() {
        return Set.of();
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

    @Blocking
    default String format() {
        return """
               <fragment description="%s">
               %s
               </fragment>
               """
                .formatted(description().join(), text().join());
    }

    /**
     * fragment toc entry, usually id + description
     */
    @Blocking
    default String formatToc(boolean isPinned) {
        String idOrPinned = isPinned ? "pinned=\"true\"" : "fragmentid=\"%s\"".formatted(id());
        return """
                <fragment-toc description="%s" %s />"""
                .formatted(description().join(), idOrPinned);
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
     * Returns all repo files referenced by this fragment, used for "edit all" type actions in the GUI when
     * we have a fragment that references other files (e.g. a stacktrace or a git diff).
     */
    ComputedValue<Set<ProjectFile>> referencedFiles();

    /**
     * Returns all files whose contents are incorporated (including partially) by this fragment.
     */
    default ComputedValue<Set<ProjectFile>> sourceFiles() {
        return sources().map(ss -> ss.stream().map(CodeUnit::source).collect(Collectors.toSet()));
    }

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

    /**
     * Sorts editable fragments by the minimum file modification time (mtime) across their associated files.
     * - Fragments with no files or inaccessible mtimes are given an mtime of 0 and will appear first.
     * - Sorting is ascending (oldest first, newest last).
     *
     * @param editableFragments stream of editable fragments (typically from {@link Context#getEditableFragments()})
     * @return stream of fragments sorted by min mtime
     */
    @Blocking
    static Stream<ContextFragment> sortByMtime(Stream<ContextFragment> editableFragments) {
        // Materialize min mtime for each fragment first to avoid recomputing during sort comparisons.
        record Key(ContextFragment fragment, long minMtime) {}

        return editableFragments
                .map(cf -> {
                    long minMtime = cf.referencedFiles().join().stream()
                            .mapToLong(pf -> {
                                try {
                                    return pf.mtime();
                                } catch (IOException e) {
                                    logger.warn(
                                            "Could not get mtime for file in fragment [{}]; using 0",
                                            cf.shortDescription(),
                                            e);
                                    return 0L;
                                }
                            })
                            .min()
                            .orElse(0L);
                    return new Key(cf, minMtime);
                })
                .sorted(Comparator.comparingLong(k -> k.minMtime))
                .map(k -> k.fragment);
    }
}
