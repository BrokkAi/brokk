package io.github.jbellis.brokk.context;

import dev.langchain4j.data.message.*;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.prompts.EditBlockParser;
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
import io.github.jbellis.brokk.AnalyzerUtil;

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
        PROJECT_PATH(true, false, false),
        GIT_FILE(true, false, false),
        EXTERNAL_PATH(true, false, false),
        IMAGE_FILE(true, false, false), // isText=false for this path fragment

        STRING(false, true, false),
        SEARCH(false, true, true), // SearchFragment extends TaskFragment, TaskFragment implements OutputFragment
        SKELETON(false, true, false),
        USAGE(false, true, false),
        CALL_GRAPH(false, true, false),
        HISTORY(false, true, true),    // Implements OutputFragment
        TASK(false, true, true),       // Implements OutputFragment
        PASTE_TEXT(false, true, false),
        PASTE_IMAGE(false, true, false), // isText=false for this virtual fragment
        STACKTRACE(false, true, false);

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

    // Static counter for all fragments
    // TODO reset this on new session (when we have sessions)
    AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Gets the current max fragment ID for serialization purposes
     */
    static int getCurrentMaxId() {
        return nextId.get();
    }

    /**
     * Sets the next fragment ID value (used during deserialization)
     */
    static void setNextId(int value) {
        if (value > nextId.get()) {
            nextId.set(value);
        }
    }

    /**
     * Unique identifier for this fragment
     */
    int id();

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
                    <file path="%s" fragmentid="%d">
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

    record ProjectPathFragment(ProjectFile file, int id, IContextManager contextManager) implements PathFragment {

        public ProjectPathFragment(ProjectFile file, IContextManager contextManager) {
            this(file, nextId.getAndIncrement(), contextManager);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.PROJECT_PATH;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        public static ProjectPathFragment withId(ProjectFile file, int existingId, IContextManager contextManager) {
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
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
    }

    /**
     * Represents a specific revision of a ProjectFile from Git history.
     */
    record GitFileFragment(ProjectFile file, String revision, String content, int id) implements PathFragment {
        public GitFileFragment(ProjectFile file, String revision, String content) {
            this(file, revision, content, nextId.getAndIncrement());
        }

        @Override
        public FragmentType getType() {
            return FragmentType.GIT_FILE;
        }

        @Override
        public IContextManager getContextManager() {
            return null; // GitFileFragment does not have a context manager
        }

        public static GitFileFragment withId(ProjectFile file, String revision, String content, int existingId) {
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
            }
            return new GitFileFragment(file, revision, content, existingId);
        }

        private String shortRevision() {
            return (revision.length() > 7) ? revision.substring(0, 7) : revision;
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
            return """
                    <file path="%s" revision="%s">
                    %s
                    </file>
                    """.stripIndent().formatted(file().toString(), revision(), text());
        }

        @Override
        public boolean isDynamic() { // Removed 'default'
            return false; // Content is fixed to a revision
        }

        @Override
        public String formatSummary() {
            return PathFragment.formatSummary(file);
        }

        // Removed custom hashCode to rely on the default record implementation,
        // as the Scala compiler might be having issues with the override.
        // The default hashCode is based on all record components.
        // If a specific hashCode behavior (like always returning 0) was intended,
        // this needs to be revisited, along with a corresponding equals().

        @Override
        public String toString() {
            return "GitFileFragment('%s' @%s)".formatted(file, shortRevision());
        }
    }

    record ExternalPathFragment(ExternalFile file, int id, IContextManager contextManager) implements PathFragment {
        public ExternalPathFragment(ExternalFile file, IContextManager contextManager) {
            this(file, nextId.getAndIncrement(), contextManager);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.EXTERNAL_PATH;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        public static ExternalPathFragment withId(ExternalFile file, int existingId, IContextManager contextManager) {
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
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
    }

    /**
     * Represents an image file, either from the project or external.
     */
    record ImageFileFragment(BrokkFile file, int id, IContextManager contextManager) implements PathFragment {
        public ImageFileFragment(BrokkFile file, IContextManager contextManager) {
            this(file, nextId.getAndIncrement(), contextManager);
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

        public static ImageFileFragment withId(BrokkFile file, int existingId, IContextManager contextManager) {
            assert !file.isText() : "ImageFileFragment should only be used for non-text files";
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
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
                    <file path="%s" fragmentid="%d">
                    [Image content provided out of band]
                    </file>
                    """.stripIndent().formatted(file().toString(), id());
        }

        @Override
        public boolean isDynamic() { // Removed 'default'
            return true; // Image file on disk could change
        }

        @Override
        public String formatSummary() {
            return PathFragment.formatSummary(file);
        }

        @Override
        public String toString() {
            return "ImageFileFragment('%s')".formatted(file);
        }
    }

    static PathFragment toPathFragment(BrokkFile bf, IContextManager contextManager) {
        if (bf.isText()) {
            if (bf instanceof ProjectFile pf) {
                return new ProjectPathFragment(pf, contextManager);
            } else if (bf instanceof ExternalFile ext) {
                return new ExternalPathFragment(ext, contextManager);
            }
        } else {
            // If it's not text, treat it as an image
            return new ImageFileFragment(bf, contextManager);
        }
        // Should not happen if bf is ProjectFile or ExternalFile
        throw new IllegalArgumentException("Unsupported BrokkFile subtype: " + bf.getClass().getName());
    }

    abstract class VirtualFragment implements ContextFragment {
        private final int id;
        protected final transient IContextManager contextManager;

        public VirtualFragment(IContextManager contextManager) {
            this.id = nextId.getAndIncrement();
            this.contextManager = contextManager;
        }

        @Override
        public IContextManager getContextManager() {
            return contextManager;
        }

        protected VirtualFragment(int existingId, IContextManager contextManager) {
            this.id = existingId;
            this.contextManager = contextManager;
            // Update the counter if needed to avoid ID conflicts
            if (existingId >= nextId.get()) {
                nextId.set(existingId + 1);
            }
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public String format() {
            return """
                    <fragment description="%s" fragmentid="%d">
                    %s
                    </fragment>
                    """.stripIndent().formatted(description(), id(), text());
        }

        @Override
        public String shortDescription() {
            assert !description().isEmpty();
            // lowercase the first letter in description()
            return description().substring(0, 1).toLowerCase() + description().substring(1);
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
            if (o == null || getClass() != o.getClass()) return false;
            VirtualFragment that = (VirtualFragment) o;
            return id() == that.id();
        }

        @Override
        public int hashCode() {
            // Use id for hashCode
            return Integer.hashCode(id());
        }
    }

    class StringFragment extends VirtualFragment {
        private final String text;
        private final String description;
        private final String syntaxStyle;

        public StringFragment(IContextManager contextManager, String text, String description, String syntaxStyle) {
            super(contextManager);
            this.syntaxStyle = syntaxStyle;
            assert text != null;
            assert description != null;
            this.text = text;
            this.description = description;
        }

        public StringFragment(int existingId, IContextManager contextManager, String text, String description, String syntaxStyle) {
            super(existingId, contextManager);
            this.syntaxStyle = syntaxStyle;
            assert text != null;
            assert description != null;
            this.text = text;
            this.description = description;
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
    class SearchFragment extends TaskFragment {
        private final Set<CodeUnit> sources; // This is pre-computed, so SearchFragment is not dynamic in content

        public SearchFragment(IContextManager contextManager, String sessionName, List<ChatMessage> messages, Set<CodeUnit> sources) {
            super(contextManager, messages, sessionName);
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

        public PasteFragment(IContextManager contextManager, Future<String> descriptionFuture) {
            super(contextManager);
            this.descriptionFuture = descriptionFuture;
        }

        public PasteFragment(int existingId, IContextManager contextManager, Future<String> descriptionFuture) {
            super(existingId, contextManager);
            this.descriptionFuture = descriptionFuture;
        }

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

    class PasteTextFragment extends PasteFragment {
        private final String text;

        public PasteTextFragment(IContextManager contextManager, String text, Future<String> descriptionFuture) {
            super(contextManager, descriptionFuture);
            assert text != null;
            assert descriptionFuture != null;
            this.text = text;
        }

        public PasteTextFragment(int existingId, IContextManager contextManager, String text, Future<String> descriptionFuture) {
            super(existingId, contextManager, descriptionFuture);
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

    class PasteImageFragment extends PasteFragment {
        private final Image image;

        public PasteImageFragment(IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(contextManager, descriptionFuture);
            assert image != null;
            assert descriptionFuture != null;
            this.image = image;
        }

        public PasteImageFragment(int existingId, IContextManager contextManager, Image image, Future<String> descriptionFuture) {
            super(existingId, contextManager, descriptionFuture);
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
              <fragment description="%s" fragmentid="%d">
              %s
              </fragment>
              """.stripIndent().formatted(description(), id(), text());
        }

        @Override
        public Set<ProjectFile> files() {
            return Set.of();
        }
    }

    class StacktraceFragment extends VirtualFragment {
        private final Set<CodeUnit> sources; // Pre-computed, so not dynamic in content
        private final String original;
        private final String exception;
        private final String code; // Pre-computed code parts

        public StacktraceFragment(IContextManager contextManager, Set<CodeUnit> sources, String original, String exception, String code) {
            super(contextManager);
            assert sources != null;
            assert original != null;
            assert exception != null;
            assert code != null;
            this.sources = sources;
            this.original = original;
            this.exception = exception;
            this.code = code;
        }

        public StacktraceFragment(int existingId, IContextManager contextManager, Set<CodeUnit> sources, String original, String exception, String code) {
            super(existingId, contextManager);
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
    }

    static String toClassname(String methodname) {
        int lastDot = methodname.lastIndexOf('.');
        if (lastDot == -1) {
            return methodname;
        }
        return methodname.substring(0, lastDot);
    }

    class UsageFragment extends VirtualFragment {
        private final String targetIdentifier;

        public UsageFragment(IContextManager contextManager, String targetIdentifier) {
            super(contextManager);
            assert targetIdentifier != null && !targetIdentifier.isBlank();
            this.targetIdentifier = targetIdentifier;
        }

        public UsageFragment(int existingId, IContextManager contextManager, String targetIdentifier) {
            super(existingId, contextManager);
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

    class CallGraphFragment extends VirtualFragment {
        private final String methodName;
        private final int depth;
        private final boolean isCalleeGraph; // true for callees (OUT), false for callers (IN)

        public CallGraphFragment(IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(contextManager);
            assert methodName != null && !methodName.isBlank();
            assert depth > 0;
            this.methodName = methodName;
            this.depth = depth;
            this.isCalleeGraph = isCalleeGraph;
        }

        public CallGraphFragment(int existingId, IContextManager contextManager, String methodName, int depth, boolean isCalleeGraph) {
            super(existingId, contextManager);
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

    class SkeletonFragment extends VirtualFragment {
        private final List<String> targetIdentifiers; // FQ class names or file paths/patterns
        private final SummaryType summaryType;

        public SkeletonFragment(IContextManager contextManager, List<String> targetIdentifiers, SummaryType summaryType) {
            super(contextManager);
            assert targetIdentifiers != null && !targetIdentifiers.isEmpty();
            assert summaryType != null;
            this.targetIdentifiers = List.copyOf(targetIdentifiers);
            this.summaryType = summaryType;
        }

        public SkeletonFragment(int existingId, IContextManager contextManager, List<String> targetIdentifiers, SummaryType summaryType) {
            super(existingId, contextManager);
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
            if (!analyzer.isCpg()) { // If analyzer is not CPG, return empty
                return Map.of();
            }
            Map<CodeUnit, String> skeletonsMap = new HashMap<>();
            switch (summaryType) {
                case CLASS_SKELETON:
                    for (String className : targetIdentifiers) {
                        analyzer.getDefinition(className).ifPresent(cu -> {
                            if (cu.isClass()) { // Ensure it's a class for getSkeleton
                                analyzer.getSkeleton(cu.fqName()).ifPresent(s -> skeletonsMap.put(cu, s));
                            }
                        });
                    }
                    break;
                case FILE_SKELETONS:
                    // This assumes targetIdentifiers are file paths. Expansion of globs should happen before fragment creation.
                    for (String filePath : targetIdentifiers) {
                        IContextManager cm = getContextManager();
                        if (cm != null) {
                            ProjectFile projectFile = cm.toFile(filePath);
                            skeletonsMap.putAll(analyzer.getSkeletons(projectFile));
                        }
                    }
                    break;
            }
            return skeletonsMap;
        }

        @Override
        public String text() {
            Map<CodeUnit, String> skeletons = fetchSkeletons();
            if (skeletons.isEmpty()) {
                // Avoid showing "No summaries found for: " if analyzer is not ready yet,
                // as it might fetch correctly on a subsequent refresh.
                IAnalyzer analyzer = getAnalyzer();
                if (!analyzer.isCpg()) {
                    return "Code intelligence is not ready. Summaries will be fetched later.";
                }
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
            return sources().stream().map(CodeUnit::source).collect(Collectors.toSet());
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
                    <summary targets="%s" type="%s" fragmentid="%d">
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
    }

    /**
     * represents the entire Task History
     */
    class HistoryFragment extends VirtualFragment implements OutputFragment {
        private final List<TaskEntry> history; // Content is fixed once created

        public HistoryFragment(IContextManager contextManager, List<TaskEntry> history) {
            super(contextManager);
            assert history != null;
            this.history = List.copyOf(history);
        }

        public HistoryFragment(int existingId, IContextManager contextManager, List<TaskEntry> history) {
            super(existingId, contextManager);
            assert history != null;
            this.history = List.copyOf(history);
        }

        @Override
        public FragmentType getType() {
            return FragmentType.HISTORY;
        }

        public List<TaskEntry> entries() {
            return history;
        }

        @Override
        public boolean isText() {
            return false;
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
                    <taskhistory fragmentid="%d">
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
    class TaskFragment extends VirtualFragment implements OutputFragment {
        private final EditBlockParser parser; // TODO this doesn't belong in TaskFragment anymore
        private final List<ChatMessage> messages; // Content is fixed once created
        private final String sessionName;

        public TaskFragment(IContextManager contextManager, EditBlockParser parser, List<ChatMessage> messages, String sessionName) {
            super(contextManager);
            this.parser = parser;
            this.messages = messages;
            this.sessionName = sessionName;
        }

        public TaskFragment(IContextManager contextManager, List<ChatMessage> messages, String sessionName) {
            this(contextManager, EditBlockParser.instance, messages, sessionName);
        }

        public TaskFragment(int existingId, IContextManager contextManager, EditBlockParser parser, List<ChatMessage> messages, String sessionName) {
            super(existingId, contextManager);
            this.parser = parser;
            this.messages = messages;
            this.sessionName = sessionName;
        }

        public TaskFragment(int existingId, IContextManager contextManager, List<ChatMessage> messages, String sessionName) {
            this(existingId, contextManager, EditBlockParser.instance, messages, sessionName);
        }

        @Override
        public FragmentType getType() {
            // SearchFragment overrides this to return FragmentType.SEARCH
            return FragmentType.TASK;
        }

        @Override
        public boolean isText() {
            return false;
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

        public List<TaskEntry> entries() {
            return List.of(new TaskEntry(-1, this, null));
        }

        public EditBlockParser parser() {
            return parser;
        }
    }
}
