package ai.brokk.context;

import static java.util.Objects.requireNonNull;

import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
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

        private static final EnumSet<FragmentType> FILE_CONTENT_TYPES =
                EnumSet.of(SKELETON, CODE, PROJECT_PATH, GIT_FILE, EXTERNAL_PATH, IMAGE_FILE);

        public boolean isPath() {
            return PATH_TYPES.contains(this);
        }

        public boolean isOutput() {
            return OUTPUT_TYPES.contains(this);
        }

        public boolean isEditable() {
            return EDITABLE_TYPES.contains(this);
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
     * content formatted for LLM
     */
    ComputedValue<String> format();

    /**
     * fragment toc entry, usually id + description
     */
    default String formatToc() {
        // Non-blocking best-effort rendering
        return """
                <fragment-toc description="%s" fragmentid="%s" />
                """
                .formatted(description().renderNowOr(""), id());
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
     * Retrieves the {@link IContextManager} associated with this fragment.
     *
     * @return The context manager instance, or {@code null} if not applicable or available.
     */
    @Nullable
    IContextManager getContextManager();

    /**
     * For live fragments ONLY, isValid reflects current external state (a file fragment whose file is missing is invalid);
     * historical/frozen fragments have already snapshotted their state and are always valid. However, if
     * a fragment is unable to snapshot its content before the source is removed out from under it, it will also
     * end up invalid.
     *
     * @return true if the fragment is valid, false otherwise
     */
    @Blocking
    default boolean isValid() {
        return true;
    }

    /**
     * Convenience method to get the analyzer in a non-blocking way using the fragment's context manager.
     *
     * @return The IAnalyzer instance if available, or null if it's not ready yet or if the context manager is not
     * available.
     */
    default IAnalyzer getAnalyzer() {
        var cm = getContextManager();
        requireNonNull(cm);
        return cm.getAnalyzerUninterrupted();
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
     * Marker for fragments whose identity is dynamic (numeric, session-local).
     * Such fragments must use numeric IDs; content-hash IDs are reserved for non-dynamic fragments.
     */
    interface DynamicIdentity {}

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

    ContextFragments.StringFragmentType BUILD_RESULTS =
            new ContextFragments.StringFragmentType("Latest Build Results", SyntaxConstants.SYNTAX_STYLE_NONE);
    ContextFragments.StringFragmentType SEARCH_NOTES =
            new ContextFragments.StringFragmentType("Code Notes", SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
    ContextFragments.StringFragmentType DISCARDED_CONTEXT =
            new ContextFragments.StringFragmentType("Discarded Context", SyntaxConstants.SYNTAX_STYLE_JSON);

    static @Nullable ContextFragments.StringFragmentType getStringFragmentType(String description) {
        if (description.isBlank()) return null;
        if (BUILD_RESULTS.description().equals(description)) return BUILD_RESULTS;
        if (SEARCH_NOTES.description().equals(description)) return SEARCH_NOTES;
        if (DISCARDED_CONTEXT.description().equals(description)) return DISCARDED_CONTEXT;
        return null;
    }

    enum SummaryType {
        CODEUNIT_SKELETON,
        FILE_SKELETONS
    }
}
