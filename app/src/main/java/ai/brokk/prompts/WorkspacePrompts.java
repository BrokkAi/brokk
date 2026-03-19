package ai.brokk.prompts;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.SpecialTextType;
import ai.brokk.util.ImageUtil;
import ai.brokk.util.ProjectGuideResolver;
import com.github.jknack.handlebars.EscapingStrategy;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.helper.ConditionalHelpers;
import com.github.jknack.handlebars.helper.StringHelpers;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates workspace-related prompt construction. Extracted from CodePrompts to centralize workspace rendering.
 * The helpers always:
 * - combine summary fragments into a single api_summaries block,
 * - append an AiMessage acknowledgment,
 * - include build status alongside editable sections when present,
 * - place untouched editable fragments with read-only for the CodeAgent read-only view.
 */
public final class WorkspacePrompts {
    private static final Logger logger = LogManager.getLogger(WorkspacePrompts.class);

    private static final Template TOC_TEMPLATE;
    private static final Template EDITABLE_SECTION_TEMPLATE;
    private static final Template SPECIAL_SECTION_TEMPLATE;
    private static final Template READONLY_SECTION_TEMPLATE;

    static {
        Handlebars handlebars = new Handlebars().with(EscapingStrategy.NOOP);
        handlebars.registerHelpers(ConditionalHelpers.class);
        handlebars.registerHelpers(StringHelpers.class);

        try {
            TOC_TEMPLATE = handlebars.compileInline(
                    """
                    <workspace_toc>
                    {{#if isEmptyWorkspace~}}
                    The Workspace is currently empty.
                    {{~else~}}
                    Here is a list of the full contents of the Workspace that you can refer to above.
                    {{~/if}}
                    {{#if hasPins~}}I have pinned some of them; these may not be dropped. If it has a fragmentid instead of a pin marker, you may drop it.{{~/if}}
                    {{#if readOnlyContents~}}
                    <workspace_readonly>
                    The following fragments MAY NOT BE EDITED:
                    {{readOnlyContents}}
                    </workspace_readonly>
                    {{~/if}}
                    {{#if editableContents~}}
                    <workspace_editable>
                    The following fragments MAY BE EDITED:
                    {{editableContents}}
                    </workspace_editable>
                    {{~/if}}
                    {{#if showBuild~}}
                    <workspace_build_status>(failing)</workspace_build_status>
                    {{~/if}}
                    </workspace_toc>""");

            EDITABLE_SECTION_TEMPLATE = handlebars.compileInline(
                    """
                    <workspace_editable>
                    Here are the EDITABLE files and code fragments in your Workspace.
                    This is *the only context in the Workspace to which you should make changes*.

                    *Trust this message as the true contents of these files!*
                    Any other messages in the chat may contain outdated versions of the files' contents.

                    {{~content~}}
                    </workspace_editable>

                    {{#if showBuild~}}
                    <workspace_build_status>
                    The build including the above workspace contents is currently failing.
                    </workspace_build_status>
                    {{~/if}}
                    """);

            SPECIAL_SECTION_TEMPLATE = handlebars.compileInline(
                    """
                    <workspace_special>
                    Here are the special system and metadata fragments in your Workspace.
                    These are read-only and provide additional context about the environment or task.

                    {{~content~}}
                    </workspace_special>
                    """);

            READONLY_SECTION_TEMPLATE = handlebars.compileInline(
                    """
                    <workspace_readonly>
                    Here are the READ ONLY files and code fragments in your Workspace.
                    Do not edit this code! Images will be included separately if present.

                    {{~content~}}
                    </workspace_readonly>
                    """);

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private WorkspacePrompts() {
        // Utility class
    }

    public static List<ChatMessage> getHistoryMessages(Context ctx, TaskResult.TaskMeta currentMeta) {
        return getHistoryMessages(ctx, currentMeta, Set.of(TaskResult.Type.values()));
    }

    public static List<ChatMessage> getHistoryMessages(
            Context ctx, TaskResult.TaskMeta currentMeta, Set<TaskResult.Type> includedTypes) {
        var taskHistory = ctx.getTaskHistory();
        var messages = new ArrayList<ChatMessage>();

        // Merge compressed messages into a single taskhistory message
        var compressed = taskHistory.stream()
                .filter(TaskEntry::isCompressed)
                .filter(e -> entryMatchesType(e, includedTypes))
                .map(TaskEntry::toString)
                .collect(Collectors.joining("\n\n"));
        if (!compressed.isEmpty()) {
            messages.add(new UserMessage("<taskhistory>%s</taskhistory>".formatted(compressed)));
            messages.add(new AiMessage("Ok, I see the history."));
        }

        // Uncompressed messages: process for tool and S/R block redaction
        taskHistory.stream()
                .filter(e -> !e.isCompressed())
                .filter(e -> entryMatchesType(e, includedTypes))
                .forEach(e -> {
                    var entryRawMessages = castNonNull(e.llmLog()).messages();
                    if (entryRawMessages.isEmpty()) {
                        return;
                    }

                    // Determine the messages to include from the entry
                    var relevantEntryMessages = entryRawMessages.getLast() instanceof AiMessage
                            ? entryRawMessages
                            : entryRawMessages.subList(0, entryRawMessages.size() - 1);

                    var entryMeta = e.meta();

                    var currentPrimaryModel = currentMeta.primaryModel();
                    var entryPrimaryModel = entryMeta == null ? null : entryMeta.primaryModel();

                    // Redact tool calls if the primary models differ
                    boolean redactToolCalls = entryPrimaryModel != null
                            && !currentPrimaryModel.name().equals(entryPrimaryModel.name());

                    messages.addAll(CodePrompts.redactHistoryMessages(relevantEntryMessages, redactToolCalls));
                });

        return messages;
    }

    private static boolean entryMatchesType(TaskEntry entry, Set<TaskResult.Type> includedTypes) {
        var meta = entry.meta();
        if (meta == null) {
            logger.debug("Missing meta type for {}", entry); // legacy entries
            return true;
        }
        return includedTypes.contains(meta.type());
    }

    /**
     * Record containing the single workspace view and optional build failure text.
     * - workspace: combined messages (read-only + editable + build status as applicable)
     * - buildFailure: formatted build fragment text if present, otherwise null
     */
    public record CodeAgentMessages(List<ChatMessage> workspace, @Nullable String buildFailure) {}

    /**
     * Unified workspace table of contents.
     * Shows:
     *   - READ ONLY fragments (if any)
     *   - All EDITABLE fragments in a single section
     * Respects {@code suppressedTypes} to hide special content like build status.
     *
     * @param ctx             the current context
     * @param suppressedTypes types of special text to omit from the TOC
     */
    public static String formatToc(Context ctx, Set<SpecialTextType> suppressedTypes) {
        var buildFragment = ctx.getBuildFragment();
        boolean hideBuild = suppressedTypes.contains(SpecialTextType.BUILD_RESULTS);

        var readOnlyContents = ctx.getReadonlyFragments()
                .filter(cf -> buildFragment.isEmpty() || cf != buildFragment.get())
                .map(cf -> cf.formatToc(ctx.isPinned(cf)))
                .collect(Collectors.joining("\n"));

        var editableContents = ContextFragment.sortByMtime(ctx.getEditableFragments())
                .map(cf -> cf.formatToc(ctx.isPinned(cf)))
                .collect(Collectors.joining("\n"));

        boolean hasNoWorkspaceContents =
                readOnlyContents.isBlank() && editableContents.isBlank() && buildFragment.isEmpty();
        record TocData(
                boolean hasPins,
                String readOnlyContents,
                String editableContents,
                boolean showBuild,
                boolean isEmptyWorkspace) {}

        var data = new TocData(
                ctx.getPinnedFragments().findAny().isPresent(),
                readOnlyContents,
                editableContents,
                !hideBuild && buildFragment.isPresent(),
                hasNoWorkspaceContents);

        try {
            return TOC_TEMPLATE.apply(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Convenience overload for callers that don't control build-status visibility. */
    public static String formatToc(Context ctx) {
        return formatToc(ctx, Collections.emptySet());
    }

    public static String formatTocForJanitor(Context ctx) {
        return ContextFragment.sortByMtime(
                        ctx.allFragments().filter(cf -> !ctx.isPinned(cf)).filter(cf -> {
                            if (cf instanceof ContextFragments.StringFragment sf) {
                                return sf.specialType().isEmpty();
                            }
                            return true;
                        }))
                .map(cf -> cf.formatToc(false))
                .collect(Collectors.joining("\n"));
    }

    /**
     * All fragments in the order they were added ({@code ctx.allFragments()}), split into individual messages.
     * Pinned items are emitted first, then non-pinned items.
     */
    @Blocking
    public static List<ChatMessage> getMessagesInAddedOrder(Context ctx, Set<SpecialTextType> suppressedTypes) {
        var allFragments = ctx.allFragments()
                .sorted((f1, f2) -> {
                    boolean s1 = isSpecial(f1);
                    boolean s2 = isSpecial(f2);
                    if (s1 == s2) return 0;
                    return s1 ? 1 : -1;
                })
                .toList();
        var styleGuide = ProjectGuideResolver.resolve(ctx);

        if (allFragments.isEmpty() && styleGuide.isBlank()) {
            return List.of();
        }

        var messages = new ArrayList<ChatMessage>();

        // style guide and other static content up front for caching
        if (!styleGuide.isBlank()) {
            messages.add(new UserMessage("<project_guide>\n%s\n</project_guide>".formatted(styleGuide.trim())));
        }

        int lastContiguousPinnedUserMessageIndex = messages.size() - 1;

        boolean inContiguousPinnedRun = true;
        for (var cf : allFragments) {
            int beforeCount = messages.size();
            addFragmentMessage(messages, cf, suppressedTypes);
            int afterCount = messages.size();

            boolean isPinned = ctx.isPinned(cf) && !isSpecial(cf);
            if (inContiguousPinnedRun && isPinned) {
                // If any messages were actually added for this fragment, the last one is our current candidate
                if (afterCount > beforeCount) {
                    var lastAdded = messages.getLast();
                    if (lastAdded instanceof UserMessage) {
                        lastContiguousPinnedUserMessageIndex = messages.size() - 1;
                    }
                }
            } else if (afterCount > beforeCount) {
                // We added something that wasn't pinned, or we were already out of the run
                inContiguousPinnedRun = false;
            }
        }

        if (lastContiguousPinnedUserMessageIndex != -1) {
            var msg = messages.get(lastContiguousPinnedUserMessageIndex);
            if (msg instanceof UserMessage um) {
                var contents = um.contents();
                if (!contents.isEmpty()) {
                    var newContents = new ArrayList<>(contents);
                    int lastIndex = newContents.size() - 1;
                    var lastContent = newContents.get(lastIndex);
                    if (lastContent instanceof TextContent tc) {
                        newContents.set(lastIndex, TextContent.withCacheControl(tc, "ephemeral"));
                    } else if (lastContent instanceof ImageContent ic) {
                        newContents.set(lastIndex, ImageContent.withCacheControl(ic, "ephemeral"));
                    }
                    messages.set(lastContiguousPinnedUserMessageIndex, new UserMessage(um.name(), newContents));
                }
            }
        }

        messages.add(AiMessage.from("Thank you for providing these Workspace contents."));

        return List.copyOf(messages);
    }

    private static void addFragmentMessage(
            List<ChatMessage> messages, ContextFragment cf, Set<SpecialTextType> suppressedTypes) {
        if (cf.isText()) {
            if (cf instanceof ContextFragments.StringFragment sf) {
                if (sf.specialType().isPresent()
                        && suppressedTypes.contains(sf.specialType().get())) {
                    return;
                }
            }
            String formatted =
                    """
                <fragment description="%s">
                %s
                </fragment>"""
                            .formatted(cf.description().join(), cf.text().join());
            messages.add(new UserMessage(formatted.trim()));
        } else {
            var contents = new ArrayList<Content>();
            contents.add(new TextContent(cf.text().join()));
            try {
                var cv = cf.imageBytes();
                if (cv != null) {
                    var bytes = cv.join();
                    if (bytes != null) {
                        var converted = ImageUtil.bytesToImage(bytes);
                        if (converted != null) {
                            var l4jImage = ImageUtil.toL4JImage(converted);
                            contents.add(ImageContent.from(l4jImage));
                        }
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                logger.error(
                        "Failed to process image fragment {} for LLM message",
                        cf.description().join(),
                        e);
                contents.add(new TextContent("[Error processing image: %s - %s]"
                        .formatted(cf.description().join(), e.getMessage())));
            }
            messages.add(new UserMessage(contents));
        }
    }

    /**
     * Generic combined workspace: readonly + editable(all) + build status, wrapped in a single
     * {@code <workspace>} block.
     *
     * @param ctx                       current context
     * @param suppressedTypes           types of special text to omit from the workspace
     */
    @Blocking
    public static List<ChatMessage> getMessagesGroupedByMutability(Context ctx, Set<SpecialTextType> suppressedTypes) {
        // Compose read-only + editable + special into a single <workspace> message
        var readOnlyMessages = buildReadOnlyForContents(ctx, suppressedTypes);
        var editableMessages = buildEditableAll(ctx, suppressedTypes);
        var specialMessages = buildSpecial(ctx, suppressedTypes);
        var styleGuide = ProjectGuideResolver.resolve(ctx);

        if (readOnlyMessages.isEmpty()
                && editableMessages.isEmpty()
                && specialMessages.isEmpty()
                && styleGuide.isBlank()) {
            return List.of();
        }

        var allContents = new ArrayList<Content>();
        var combinedText = new StringBuilder();

        // Helper to extract from UserMessages
        List<List<ChatMessage>> messageGroups = List.of(readOnlyMessages, editableMessages, specialMessages);
        for (var group : messageGroups) {
            if (!group.isEmpty()) {
                var userMessage = group.stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .findFirst();
                if (userMessage.isPresent()) {
                    var contents = userMessage.get().contents();
                    for (var content : contents) {
                        if (content instanceof TextContent textContent) {
                            combinedText.append(textContent.text()).append("\n\n");
                        } else if (content instanceof ImageContent imageContent) {
                            allContents.add(imageContent);
                        }
                    }
                }
            }
        }

        var workspaceBuilder = new StringBuilder();
        if (!styleGuide.isBlank()) {
            workspaceBuilder.append("<project_guide>\n");
            workspaceBuilder.append(styleGuide.trim());
            workspaceBuilder.append("\n</project_guide>\n\n");
        }
        workspaceBuilder.append("<workspace>\n");
        workspaceBuilder.append(combinedText.toString().trim());
        workspaceBuilder.append("\n</workspace>");

        allContents.addFirst(new TextContent(workspaceBuilder.toString()));

        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing these Workspace contents."));
    }

    /**
     * Workspace views used by CodeAgent.
     *
     * @param ctx                       current context
     * @param suppressedTypes           types of special text to omit from the workspace
     * @return record with the workspace messages and buildFailure details
     */
    @Blocking
    public static CodeAgentMessages getMessagesForCodeAgent(Context ctx, Set<SpecialTextType> suppressedTypes) {
        var workspace = getMessagesGroupedByMutability(ctx, suppressedTypes);
        var buildFailure = ctx.getBuildFragment().map(f -> f.text().join()).orElse(null);
        return new CodeAgentMessages(workspace, buildFailure);
    }

    private static List<ChatMessage> buildEditableAll(Context ctx, Set<SpecialTextType> suppressedTypes) {
        var editableFragments =
                ContextFragment.sortByMtime(ctx.getEditableFragments()).toList();
        var editableTextFragments = formatWithPolicy(editableFragments, suppressedTypes).text;

        boolean shouldShowBuild = !suppressedTypes.contains(SpecialTextType.BUILD_RESULTS)
                && ctx.getBuildFragment().isPresent();

        if (editableTextFragments.isEmpty() && !shouldShowBuild) {
            return List.of();
        }

        record EditableData(String content, boolean showBuild) {}
        var data = new EditableData(editableTextFragments.trim(), shouldShowBuild);

        try {
            var combinedText = EDITABLE_SECTION_TEMPLATE.apply(data);
            var userMessage = new UserMessage(combinedText.trim());
            String ack = "Thank you for the editable context and build status.";
            return List.of(userMessage, new AiMessage(ack));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<ChatMessage> buildSpecial(Context ctx, Set<SpecialTextType> suppressedTypes) {
        var specialFragments =
                ctx.allFragments().filter(WorkspacePrompts::isSpecial).toList();

        var renderedSpecial = formatWithPolicy(specialFragments, suppressedTypes);

        if (renderedSpecial.text.isEmpty() && renderedSpecial.images.isEmpty()) {
            return List.of();
        }

        String specialText = "";
        if (!renderedSpecial.text.isEmpty()) {
            try {
                specialText = SPECIAL_SECTION_TEMPLATE.apply(Map.of("content", renderedSpecial.text.trim()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        var allContents = new ArrayList<Content>();
        if (!specialText.isEmpty()) {
            allContents.add(new TextContent(specialText.trim()));
        }
        allContents.addAll(renderedSpecial.images);

        var specialUserMessage = UserMessage.from(allContents);
        return List.of(specialUserMessage, new AiMessage("Thank you for the special Workspace fragments."));
    }

    private static List<ChatMessage> buildReadOnlyForContents(Context ctx, Set<SpecialTextType> suppressedTypes) {
        // Build read-only section; exclude special fragments
        var readOnlyFragments =
                ctx.getReadonlyFragments().filter(f -> !isSpecial(f)).toList();

        var renderedReadOnly = renderReadOnlyFragments(readOnlyFragments, suppressedTypes);

        if (renderedReadOnly.text.isEmpty() && renderedReadOnly.images.isEmpty()) {
            return List.of();
        }

        String readOnlyText = "";
        if (!renderedReadOnly.text.isEmpty()) {
            try {
                readOnlyText = READONLY_SECTION_TEMPLATE.apply(Map.of("content", renderedReadOnly.text.trim()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        var allContents = new ArrayList<Content>();
        if (!readOnlyText.isEmpty()) {
            allContents.add(new TextContent(readOnlyText.trim()));
        }
        allContents.addAll(renderedReadOnly.images);

        var readOnlyUserMessage = UserMessage.from(allContents);
        return List.of(readOnlyUserMessage, new AiMessage("Thank you for the read-only Workspace fragments."));
    }

    // --- Helper rendering utilities (copied and adapted from original CodePrompts) ---

    private static final class RenderedContent {
        final String text;
        final List<ImageContent> images;

        RenderedContent(String text, List<ImageContent> images) {
            this.text = text;
            this.images = images;
        }
    }

    /**
     * Renders readonly fragments into a RenderedContent with combined summary fragments.
     * Always partitions readonly fragments into SummaryFragments and others:
     * - Non-summary fragments are formatted with the viewing policy
     * - Summary fragments are combined into a single <api_summaries> block
     * - All images are collected and returned
     *
     * @param readOnly     readonly fragments to render
     * @return RenderedContent with formatted text and images
     */
    private static RenderedContent renderReadOnlyFragments(
            List<ContextFragment> readOnly, Set<SpecialTextType> suppressedTypes) {
        var summaryFragments = readOnly.stream()
                .filter(ContextFragments.SummaryFragment.class::isInstance)
                .map(ContextFragments.SummaryFragment.class::cast)
                .toList();

        var otherFragments = readOnly.stream()
                .filter(f -> !(f instanceof ContextFragments.SummaryFragment))
                .toList();

        var renderedOther = formatWithPolicy(otherFragments, suppressedTypes);
        var textBuilder = new StringBuilder(renderedOther.text);

        if (!summaryFragments.isEmpty()) {
            var summaryText = ContextFragments.SummaryFragment.combinedText(summaryFragments);
            var combinedBlock =
                    """
                    <api_summaries>
                    %s
                    </api_summaries>
                    """
                            .formatted(summaryText);
            if (!renderedOther.text.isEmpty()) {
                textBuilder.append("\n\n");
            }
            textBuilder.append(combinedBlock);
        }

        return new RenderedContent(textBuilder.toString().trim(), renderedOther.images);
    }

    private static boolean isSpecial(ContextFragment fragment) {
        return fragment instanceof ContextFragments.StringFragment sf
                && sf.specialType().isPresent();
    }

    private static RenderedContent formatWithPolicy(
            List<ContextFragment> fragments, Set<SpecialTextType> suppressedTypes) {
        var textBuilder = new StringBuilder();
        var imageList = new ArrayList<ImageContent>();

        for (var cf : fragments) {
            if (cf.isText()) {
                if (cf instanceof ContextFragments.StringFragment sf) {
                    if (sf.specialType().isPresent()
                            && suppressedTypes.contains(sf.specialType().get())) {
                        continue;
                    }
                }
                textBuilder.append(cf.format()).append("\n\n");
                continue;
            }

            assert cf instanceof ContextFragments.ImageFileFragment
                            || cf instanceof ContextFragments.AnonymousImageFragment
                    : cf;
            try {
                var cv = cf.imageBytes();
                if (cv != null) {
                    var bytes = cv.join();
                    if (bytes != null) { // NOT gratuitous
                        var converted = ImageUtil.bytesToImage(bytes);
                        if (converted != null) {
                            var l4jImage = ImageUtil.toL4JImage(converted);
                            imageList.add(ImageContent.from(l4jImage));
                        }
                    }
                }
                textBuilder.append(cf.text().join()).append("\n\n");
            } catch (IOException | UncheckedIOException e) {
                logger.error(
                        "Failed to process image fragment {} for LLM message",
                        cf.description().join(),
                        e);
                textBuilder.append(String.format(
                        "[Error processing image: %s - %s]\n\n",
                        cf.description().join(), e.getMessage()));
            }
        }

        return new RenderedContent(textBuilder.toString().trim(), imageList);
    }
}
