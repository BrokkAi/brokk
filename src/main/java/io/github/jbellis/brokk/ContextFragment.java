package io.github.jbellis.brokk;

import dev.langchain4j.data.message.*;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.prompts.EditBlockParser;
import io.github.jbellis.brokk.util.Messages;
import org.fife.ui.rsyntaxtextarea.FileTypeUtil;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import java.awt.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface ContextFragment extends Serializable {
    // Static counter for all fragments
    // TODO reset this on new session (when we have sessions)
    AtomicInteger NEXT_ID = new AtomicInteger(1);

    /**
     * Gets the current max fragment ID for serialization purposes
     */
    static int getCurrentMaxId() {
        return NEXT_ID.get();
    }

    /**
     * Sets the next fragment ID value (used during deserialization)
     */
    static void setNextId(int value) {
        if (value > NEXT_ID.get()) {
            NEXT_ID.set(value);
        }
    }

    /**
     * Unique identifier for this fragment
     */
    int id();

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
    String text() throws IOException;

    /**
     * content formatted for LLM
     */
    String format() throws IOException;

    /**
     * for Quick Context LLM
     */
    default String formatSummary(IAnalyzer analyzer) {
        return description();
    }

    default boolean isText() {
        return true;
    }

    default Image image() throws IOException {
        throw new UnsupportedOperationException();
    }

    /**
     * code sources found in this fragment
     */
    Set<CodeUnit> sources(IAnalyzer analyzer);

    /**
     * Returns all repo files referenced by this fragment.
     * This is used when we *just* want to manipulate or show actual files,
     * rather than the code units themselves.
     */
    Set<ProjectFile> files(IProject project);

    String syntaxStyle();

    /**
     * If false, the classes returned by sources() will be pruned from AutoContext suggestions.
     * (Corollary: if sources() always returns empty, this doesn't matter.)
     */
    default boolean isEligibleForAutoContext() {
        return true;
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
        default Set<ProjectFile> files(IProject project) {
            return Set.of();
        }

        @Override
        default String text() throws IOException {
            return file().read();
        }

        @Override
        default String syntaxStyle() {
            return FileTypeUtil.get().guessContentType(file().absPath().toFile());
        }

        @Override
        default String format() throws IOException {
            return """
                    <file path="%s" fragmentid="%d">
                    %s
                    </file>
                    """.stripIndent().formatted(file().toString(), id(), text());
        }

        static String formatSummary(BrokkFile file) {
            return "<file source=\"%s\" />".formatted(file);
        }
    }

    record ProjectPathFragment(ProjectFile file, int id) implements PathFragment {
        private static final long serialVersionUID = 2L;

        public ProjectPathFragment(ProjectFile file) {
            this(file, NEXT_ID.getAndIncrement());
        }

        @Override
        public String shortDescription() {
            return file().getFileName();
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
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
        public String formatSummary(IAnalyzer analyzer) {
            var summary = analyzer.getSkeletons(file).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .collect(Collectors.joining("\n"));
            if (summary.isBlank()) {
                // this also handles the analyzer.isEmpty case
                return PathFragment.formatSummary(file);
            }
            return """
                   <file source="%s" summarized=true>
                   %s
                   </file>
                   """.formatted(file, summary);
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
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
        private static final long serialVersionUID = 2L;

        public GitFileFragment(ProjectFile file, String revision, String content) {
            this(file, revision, content, NEXT_ID.getAndIncrement());
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
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            // Treat historical content as potentially different from current; don't claim sources
            return Set.of();
        }

        @Override
        public String text() {
            return content;
        }

        @Override
        public String format() throws IOException {
            return """
                    <file path="%s" revision="%s">
                    %s
                    </file>
                    """.stripIndent().formatted(file().toString(), revision(), text());
        }

        @Override
        public String formatSummary(IAnalyzer analyzer) {
            return PathFragment.formatSummary(file);
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "GitFileFragment('%s' @%s)".formatted(file, shortRevision());
        }
    }


    record ExternalPathFragment(ExternalFile file, int id) implements PathFragment {
        private static final long serialVersionUID = 2L;

        public ExternalPathFragment(ExternalFile file) {
            this(file, NEXT_ID.getAndIncrement());
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
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            return Set.of();
        }

        @Override
        public String formatSummary(IAnalyzer analyzer) {
            return PathFragment.formatSummary(file);
        }
    }

    /**
     * Represents an image file, either from the project or external.
     */
    record ImageFileFragment(BrokkFile file, int id) implements PathFragment {
        private static final long serialVersionUID = 1L;

        public ImageFileFragment(BrokkFile file) {
            this(file, NEXT_ID.getAndIncrement());
            assert !file.isText() : "ImageFileFragment should only be used for non-text files";
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
        public Image image() throws IOException {
            return javax.imageio.ImageIO.read(file.absPath().toFile());
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            return Set.of();
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
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
        public String formatSummary(IAnalyzer analyzer) {
            return PathFragment.formatSummary(file);
        }

        @Override
        public String toString() {
            return "ImageFileFragment('%s')".formatted(file);
        }
    }

    static PathFragment toPathFragment(BrokkFile bf) {
        if (bf.isText()) {
            if (bf instanceof ProjectFile pf) {
                return new ProjectPathFragment(pf);
            } else if (bf instanceof ExternalFile ext) {
                return new ExternalPathFragment(ext);
            }
        } else {
            // If it's not text, treat it as an image
            return new ImageFileFragment(bf);
        }
        // Should not happen if bf is ProjectFile or ExternalFile
        throw new IllegalArgumentException("Unsupported BrokkFile subtype: " + bf.getClass().getName());
    }

    abstract class VirtualFragment implements ContextFragment {
        private static final long serialVersionUID = 2L;
        private final int id;

        public VirtualFragment() {
            this.id = NEXT_ID.getAndIncrement();
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
        public Set<ProjectFile> files(IProject project) {
            return parseProjectFiles(text(), project);
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            return Set.of();
        }

        @Override
        public String formatSummary(IAnalyzer analyzer) {
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
        private static final long serialVersionUID = 3L;
        private final String text;
        private final String description;
        private final String syntaxStyle;

        public StringFragment(String text, String description, String syntaxStyle) {
            super();
            this.syntaxStyle = syntaxStyle;
            assert text != null;
            assert description != null;
            this.text = text;
            this.description = description;
        }

        @Override
        public String text() {
            return text;
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
        private static final long serialVersionUID = 5L;
        private final Set<CodeUnit> sources;

        public SearchFragment(String sessionName, List<ChatMessage> messages, Set<CodeUnit> sources) {
            super(messages, sessionName);
            assert sources != null;
            this.sources = sources;
        }


        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            return sources;
        }

        public Set<ProjectFile> files(IProject project) {
            return sources.stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String formatSummary(IAnalyzer analyzer) {
            return format(); // full search result
        }

        // --- Custom Serialization using Proxy Pattern ---
        // SearchFragment extends TaskFragment, which has its own proxy for messages.
        // We only need to handle SearchFragment's own fields here. TaskFragment's state
        // should be handled by its proxy during the serialization process.

        @Serial
        private Object writeReplace() {
            return new SerializationProxy(this);
        }

        @Serial
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            // This method should not be called if writeReplace is used.
            throw new java.io.NotSerializableException("SearchFragment must be serialized via SerializationProxy");
        }

        private static class SerializationProxy implements Serializable {
            @Serial
            private static final long serialVersionUID = 41L;

            private final String serializedMessages; // Store messages as JSON string
            private final String sessionName;
            private final Set<CodeUnit> sources;

            SerializationProxy(SearchFragment fragment) {
                // Store the class name of the parser
                this.sessionName = fragment.description();
                this.serializedMessages = ChatMessageSerializer.messagesToJson(fragment.messages());
                this.sources = fragment.sources;
            }

            /**
             * Reconstruct the TaskFragment instance after the SerializationProxy is deserialized.
             */
            @Serial
            private Object readResolve() throws java.io.ObjectStreamException {
                List<ChatMessage> deserializedMessages = ChatMessageDeserializer.messagesFromJson(serializedMessages);
                return new SearchFragment(sessionName, deserializedMessages, sources);
            }
        }
    }

    abstract class PasteFragment extends ContextFragment.VirtualFragment {
        protected transient Future<String> descriptionFuture;

        public PasteFragment(Future<String> descriptionFuture) {
            super();
            this.descriptionFuture = descriptionFuture;
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

        private void writeObject(java.io.ObjectOutputStream out) throws IOException {
            out.defaultWriteObject();
            String desc;
            if (descriptionFuture.isDone()) {
                try {
                    desc = descriptionFuture.get();
                } catch (Exception e) {
                    desc = "(Error summarizing paste)";
                }
            } else {
                desc = "(Paste summary incomplete)";
            }
            out.writeObject(desc);
        }

        private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            String desc = (String) in.readObject();
            this.descriptionFuture = java.util.concurrent.CompletableFuture.completedFuture(desc);
        }
    }

    class PasteTextFragment extends PasteFragment {
        private static final long serialVersionUID = 4L;
        private final String text;

        public PasteTextFragment(String text, Future<String> descriptionFuture) {
            super(descriptionFuture);
            assert text != null;
            assert descriptionFuture != null;
            this.text = text;
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
        private static final long serialVersionUID = 1L;
        private final Image image;

        public PasteImageFragment(Image image, Future<String> descriptionFuture) {
            super(descriptionFuture);
            assert image != null;
            assert descriptionFuture != null;
            this.image = image;
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
            throw new UnsupportedOperationException();
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
        public Set<ProjectFile> files(IProject project) {
            return Set.of();
        }
    }

    class StacktraceFragment extends VirtualFragment {
        private static final long serialVersionUID = 2L;
        private final Set<CodeUnit> sources;
        private final String original;
        private final String exception;
        private final String code;

        public StacktraceFragment(Set<CodeUnit> sources, String original, String exception, String code) {
            super();
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
        public String text() {
            return original + "\n\nStacktrace methods in this project:\n\n" + code;
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            return sources;
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return sources.stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            return "stacktrace of " + exception;
        }

        @Override
        public String formatSummary(IAnalyzer analyzer) {
            return format(); // full source
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
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
        private static final long serialVersionUID = 3L;
        private final String targetIdentifier;
        private final Set<CodeUnit> classes;
        private final String code;

        public UsageFragment(String targetIdentifier, Set<CodeUnit> classes, String code) {
            super();
            assert targetIdentifier != null;
            assert classes != null;
            assert code != null;
            this.targetIdentifier = targetIdentifier;
            this.classes = classes;
            this.code = code;
        }

        @Override
        public String text() {
            return code;
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            return classes;
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return classes.stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            return "Uses of %s".formatted(targetIdentifier);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }
    }

    class CallGraphFragment extends VirtualFragment {
        private static final long serialVersionUID = 1L;
        private final String type;
        private final String targetIdentifier;
        private final Set<CodeUnit> classes;
        private final String code;

        public CallGraphFragment(String type, String targetIdentifier, Set<CodeUnit> classes, String code) {
            super();
            assert type != null;
            assert targetIdentifier != null;
            assert classes != null;
            this.type = type;
            this.targetIdentifier = targetIdentifier;
            this.classes = classes;
            this.code = code;
        }

        @Override
        public String text() {
            return code;
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            return classes;
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return classes.stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            return "%s of %s".formatted(type, targetIdentifier);
        }

        @Override
        public String syntaxStyle() {
            return SyntaxConstants.SYNTAX_STYLE_JAVA;
        }
    }

    class SkeletonFragment extends VirtualFragment {
        private static final long serialVersionUID = 3L;
        final Map<CodeUnit, String> skeletons;

        public SkeletonFragment(Map<CodeUnit, String> skeletons) {
            super();
            assert skeletons != null;
            this.skeletons = skeletons;
        }

        @Override
        public String text() {
            if (isEmpty()) {
                return "";
            }
            var skeletonsByPackage = skeletons.entrySet().stream()
                    .collect(Collectors.groupingBy(
                            e -> {
                                var pkg = e.getKey().packageName();
                                return pkg.isEmpty() ? "(default package)" : pkg;
                            },
                            Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue,
                                    (v1, v2) -> v1,
                                    java.util.LinkedHashMap::new
                            )
                    ));
            if (skeletons.isEmpty()) return "";
            return skeletonsByPackage.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(pkgEntry -> {
                        var packageHeader = "package " + pkgEntry.getKey() + ";";
                        var pkgCode = String.join("\n\n", pkgEntry.getValue().values());
                        return packageHeader + "\n\n" + pkgCode;
                    })
                    .collect(Collectors.joining("\n\n"));
        }

        public static SkeletonFragment merge(Collection<SkeletonFragment> fragments) {
            var combinedSkeletons = new HashMap<CodeUnit, String>();
            for (var fragment : fragments) {
                combinedSkeletons.putAll(fragment.skeletons());
            }
            return new SkeletonFragment(combinedSkeletons);
        }

        private Set<CodeUnit> nonDummy() {
            return skeletons.keySet();
        }

        private boolean isEmpty() {
            return nonDummy().isEmpty();
        }

        @Override
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            return nonDummy();
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
            return nonDummy().stream().map(CodeUnit::source).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public String description() {
            assert !isEmpty();
            return "Summary of " + skeletons.keySet().stream()
                    .map(CodeUnit::identifier)
                    .sorted(Comparator.comparingInt((String s) -> (int) s.chars().filter(ch -> ch == '$').count())
                                      .thenComparing(Comparator.naturalOrder()))
                    .collect(Collectors.joining(", "));
        }

        @Override
        public boolean isEligibleForAutoContext() {
            return false;
        }

        @Override
        public String format() {
            assert !isEmpty();
            return """
                    <summary classes="%s" fragmentid="%d">
                    %s
                    </summary>
                    """.stripIndent().formatted(
                    skeletons.keySet().stream()
                            .map(CodeUnit::fqName)
                            .sorted()
                            .collect(Collectors.joining(", ")),
                    id(),
                    text()
            );
        }

        @Override
        public String formatSummary(IAnalyzer analyzer) {
            return format();
        }

        public Map<CodeUnit, String> skeletons() {
            return skeletons;
        }

        @Override
        public String syntaxStyle() {
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
        private static final long serialVersionUID = 3L;
        private final List<TaskEntry> history;

        public HistoryFragment(List<TaskEntry> history) {
            super();
            assert history != null;
            this.history = List.copyOf(history);
        }

        public List<TaskEntry> entries() {
            return history;
        }

        @Override
        public boolean isText() {
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
        public Set<CodeUnit> sources(IAnalyzer analyzer) {
            return Set.of();
        }

        @Override
        public Set<ProjectFile> files(IProject project) {
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
                    """.stripIndent().formatted(id(), text());
        }

        @Override
        public String formatSummary(IAnalyzer analyzer) {
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
        private static final long serialVersionUID = 5L;
        private final EditBlockParser parser; // TODO this doesn't belong in TaskFragment anymore
        private final List<ChatMessage> messages;
        private final String sessionName;

        public TaskFragment(EditBlockParser parser, List<ChatMessage> messages, String sessionName) {
            super();
            this.parser = parser;
            this.messages = messages;
            this.sessionName = sessionName;
        }

        public TaskFragment(List<ChatMessage> messages, String sessionName) {
            this(EditBlockParser.instance, messages, sessionName);
        }

        @Override
        public boolean isText() {
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
        public String formatSummary(IAnalyzer analyzer) {
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

        // --- Custom Serialization using Proxy Pattern ---

        /**
         * Replace this TaskFragment instance with a SerializationProxy during serialization.
         * This allows us to convert the non-serializable ChatMessage list to JSON.
         */
        @Serial
        private Object writeReplace() {
            return new SerializationProxy(this);
        }

        /**
         * Prevent direct deserialization of TaskFragment; must go through the proxy.
         */
        @Serial
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            throw new java.io.NotSerializableException("TaskFragment must be serialized via SerializationProxy");
        }

        /**
         * A helper class to handle the serialization and deserialization of TaskFragment.
         * It stores the ChatMessage list as a JSON string.
         */
        private static class SerializationProxy implements Serializable {
            @Serial
            private static final long serialVersionUID = 1L;

            private final String serializedMessages; // Store messages as JSON string
            private final String sessionName;

            SerializationProxy(TaskFragment fragment) {
                // Store the class name of the parser
                this.sessionName = fragment.sessionName;
                this.serializedMessages = ChatMessageSerializer.messagesToJson(fragment.messages());
            }

            /**
             * Reconstruct the TaskFragment instance after the SerializationProxy is deserialized.
             */
            @Serial
            private Object readResolve() throws java.io.ObjectStreamException {
                List<ChatMessage> deserializedMessages = ChatMessageDeserializer.messagesFromJson(serializedMessages);
                return new TaskFragment(deserializedMessages, sessionName);
            }
        }
    }
}
