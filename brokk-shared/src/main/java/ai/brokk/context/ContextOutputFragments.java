package ai.brokk.context;

import ai.brokk.util.FragmentUtils;
import java.util.List;
import java.util.Set;

/** Output/history fragments backed by pre-rendered Markdown (no ChatMessage / TaskEntry dependency). */
public final class ContextOutputFragments {
    private ContextOutputFragments() {}

    public interface OutputFragment {
        /** Per-entry markdown, in sequence order. */
        List<String> entryMarkdowns();

        default boolean isEscapeHtml() {
            return true;
        }
    }

    public static final class HistoryOutputFragment extends ContextFragments.AbstractStaticFragment
            implements OutputFragment {
        private final List<String> entryMarkdowns;

        public HistoryOutputFragment(List<String> entryMarkdowns) {
            this(entryMarkdowns, true);
        }

        public HistoryOutputFragment(List<String> entryMarkdowns, boolean escapeHtml) {
            this(
                    FragmentUtils.calculateContentHash(
                            ContextFragment.FragmentType.HISTORY,
                            descriptionFor(entryMarkdowns.size()),
                            combinedMarkdown(entryMarkdowns),
                            ContextFragments.SYNTAX_STYLE_MARKDOWN,
                            HistoryOutputFragment.class.getName()),
                    entryMarkdowns,
                    escapeHtml);
        }

        public HistoryOutputFragment(String id, List<String> entryMarkdowns, boolean escapeHtml) {
            this(id, entryMarkdowns, combinedMarkdown(entryMarkdowns), escapeHtml);
        }

        public HistoryOutputFragment(
                String id, List<String> entryMarkdowns, String combinedMarkdown, boolean escapeHtml) {
            super(
                    id,
                    descriptionFor(entryMarkdowns.size()),
                    descriptionFor(entryMarkdowns.size()),
                    ContextFragments.SYNTAX_STYLE_MARKDOWN,
                    ContextFragments.ContentSnapshot.textSnapshot(combinedMarkdown, Set.of(), Set.of()));
            this.entryMarkdowns = List.copyOf(entryMarkdowns);
        }

        private static String descriptionFor(int entryCount) {
            return "Conversation (" + entryCount + " thread" + (entryCount == 1 ? "" : "s") + ")";
        }

        private static String combinedMarkdown(List<String> entryMarkdowns) {
            return String.join("\n\n", entryMarkdowns);
        }

        @Override
        public ContextFragment.FragmentType getType() {
            return ContextFragment.FragmentType.HISTORY;
        }

        @Override
        public List<String> entryMarkdowns() {
            return entryMarkdowns;
        }

        @Override
        public ContextFragment refreshCopy() {
            return this;
        }
    }

    public static final class TaskOutputFragment extends ContextFragments.AbstractStaticFragment
            implements OutputFragment {
        private final List<String> entryMarkdowns;
        private final boolean escapeHtml;

        public TaskOutputFragment(String description, String markdown) {
            this(description, markdown, true);
        }

        public TaskOutputFragment(String description, String markdown, boolean escapeHtml) {
            this(
                    FragmentUtils.calculateContentHash(
                            ContextFragment.FragmentType.TASK,
                            description,
                            markdown,
                            ContextFragments.SYNTAX_STYLE_MARKDOWN,
                            TaskOutputFragment.class.getName()),
                    description,
                    markdown,
                    escapeHtml);
        }

        public TaskOutputFragment(String id, String description, String markdown, boolean escapeHtml) {
            super(
                    id,
                    description,
                    description,
                    ContextFragments.SYNTAX_STYLE_MARKDOWN,
                    ContextFragments.ContentSnapshot.textSnapshot(markdown, Set.of(), Set.of()));
            this.entryMarkdowns = List.of(markdown);
            this.escapeHtml = escapeHtml;
        }

        @Override
        public ContextFragment.FragmentType getType() {
            return ContextFragment.FragmentType.TASK;
        }

        @Override
        public List<String> entryMarkdowns() {
            return entryMarkdowns;
        }

        @Override
        public boolean isEscapeHtml() {
            return escapeHtml;
        }

        @Override
        public ContextFragment refreshCopy() {
            return this;
        }
    }
}
