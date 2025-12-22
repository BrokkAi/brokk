package ai.brokk.prompts;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.context.ViewingPolicy;
import ai.brokk.util.ImageUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates workspace-related prompt construction. Extracted from CodePrompts to centralize workspace rendering.
 *
 * The helpers always:
 * - combine summary fragments into a single api_summaries block,
 * - append an AiMessage acknowledgment,
 * - include build status alongside editable sections when present,
 * - place untouched editable fragments with read-only for the CodeAgent read-only view.
 */
public final class WorkspacePrompts {
    private static final Logger logger = LogManager.getLogger(WorkspacePrompts.class);

    private WorkspacePrompts() {
        // Utility class
    }

    /**
     * Record containing the single workspace view and optional build failure text.
     * - workspace: combined messages (read-only + editable + build status as applicable)
     * - buildFailure: formatted build fragment text if present, otherwise null
     */
    public record CodeAgentMessages(List<ChatMessage> workspace, @Nullable String buildFailure) {}

    /**
     * Sorts editable fragments by the minimum file modification time (mtime) across their associated files.
     * - Fragments with no files or inaccessible mtimes are given an mtime of 0 and will appear first.
     * - Sorting is ascending (oldest first, newest last).
     *
     * @param editableFragments stream of editable fragments (typically from {@link Context#getEditableFragments()})
     * @return stream of fragments sorted by min mtime
     */
    public static Stream<ContextFragment> sortByMtime(Stream<ContextFragment> editableFragments) {
        // Materialize min mtime for each fragment first to avoid recomputing during sort comparisons.
        record Key(ContextFragment fragment, long minMtime) {}

        return editableFragments
                .map(cf -> {
                    long minMtime = cf.files().join().stream()
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

    /**
     * Unified workspace table of contents.
     *
     * Shows:
     *   - READ ONLY fragments (if any)
     *   - All EDITABLE fragments in a single section
     * Optionally appends a simple build status indicator.
     *
     * @param ctx             the current context
     * @param showBuildStatus if true, include build status indicator in TOC; if false, omit
     */
    public static String formatToc(Context ctx, boolean showBuildStatus) {
        var readOnlyContents =
                ctx.getReadonlyFragments().map(ContextFragment::formatToc).collect(Collectors.joining("\n"));

        var editableFragments = sortByMtime(ctx.getEditableFragments()).toList();
        var buildFragment = ctx.getBuildFragment();

        var readOnlySection = readOnlyContents.isBlank()
                ? ""
                : """
                  <workspace_readonly>
                  The following fragments MAY NOT BE EDITED:
                  %s
                  </workspace_readonly>"""
                        .formatted(readOnlyContents);

        var parts = new ArrayList<String>();
        if (!readOnlySection.isBlank()) {
            parts.add(readOnlySection);
        }

        var editableContents =
                editableFragments.stream().map(ContextFragment::formatToc).collect(Collectors.joining("\n"));
        if (!editableContents.isBlank()) {
            parts.add(
                    """
                    <workspace_editable>
                    The following fragments MAY BE EDITED:
                    %s
                    </workspace_editable>"""
                            .formatted(editableContents));
        }

        if (showBuildStatus && buildFragment.isPresent()) {
            parts.add("  <workspace_build_status>(failing)</workspace_build_status>");
        }

        return """
               <workspace_toc>
               %s
               </workspace_toc>"""
                .formatted(String.join("\n", parts));
    }

    /** Convenience overload for callers that don't control build-status visibility. */
    public static String formatToc(Context ctx) {
        return formatToc(ctx, true);
    }

    /**
     * All fragments in the order they were added ({@code ctx.allFragments()}), wrapped in a single
     * {@code <workspace>} block.
     */
    public static List<ChatMessage> getMessagesInAddedOrder(Context ctx, ViewingPolicy viewingPolicy) {
        var allFragments = ctx.allFragments().toList();
        if (allFragments.isEmpty()) {
            return List.of();
        }

        var rendered = formatWithPolicy(allFragments, viewingPolicy);
        if (rendered.text.isEmpty() && rendered.images.isEmpty()) {
            return List.of();
        }

        var allContents = new ArrayList<Content>();
        var workspaceText =
                """
                           <workspace>
                           %s
                           </workspace>
                           """
                        .formatted(rendered.text);

        allContents.add(new TextContent(workspaceText));
        allContents.addAll(rendered.images);

        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing these Workspace contents."));
    }

    /**
     * Generic combined workspace: readonly + editable(all) + build status, wrapped in a single
     * {@code <workspace>} block.
     */
    public static List<ChatMessage> getMessagesGroupedByMutability(Context ctx, ViewingPolicy viewingPolicy) {
        // Public entry point keeps the original behavior: show build status in workspace.
        return getMessagesGroupedByMutability(ctx, viewingPolicy, true);
    }

    /**
     * Internal helper that allows callers to choose whether build status appears in the workspace.
     *
     * @param showBuildStatusInWorkspace if true, include build status snippets in workspace sections
     *                                   (both in read-only fragment and editable sections);
     *                                   if false, omit all build status from workspace
     *                                   (it will be shown inline in the request instead).
     */
    private static List<ChatMessage> getMessagesGroupedByMutability(
            Context ctx, ViewingPolicy viewingPolicy, boolean showBuildStatusInWorkspace) {
        // Compose read-only (optionally with build fragment) + all editable + build status into a single <workspace>
        // message
        var readOnlyMessages = buildReadOnlyForContents(ctx, viewingPolicy, showBuildStatusInWorkspace);
        var editableMessages = buildEditableAll(ctx, showBuildStatusInWorkspace);

        if (readOnlyMessages.isEmpty() && editableMessages.isEmpty()) {
            return List.of();
        }

        var allContents = new ArrayList<Content>();
        var combinedText = new StringBuilder();

        // Extract text and images from read-only messages
        if (!readOnlyMessages.isEmpty()) {
            var readOnlyUserMessage = readOnlyMessages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .findFirst();
            if (readOnlyUserMessage.isPresent()) {
                var contents = readOnlyUserMessage.get().contents();
                for (var content : contents) {
                    if (content instanceof TextContent textContent) {
                        combinedText.append(textContent.text()).append("\n\n");
                    } else if (content instanceof ImageContent imageContent) {
                        allContents.add(imageContent);
                    }
                }
            }
        }

        // Extract text from editable messages
        if (!editableMessages.isEmpty()) {
            var editableUserMessage = editableMessages.stream()
                    .filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast)
                    .findFirst();
            if (editableUserMessage.isPresent()) {
                var contents = editableUserMessage.get().contents();
                for (var content : contents) {
                    if (content instanceof TextContent textContent) {
                        combinedText.append(textContent.text()).append("\n\n");
                    } else if (content instanceof ImageContent imageContent) {
                        allContents.add(imageContent);
                    }
                }
            }
        }

        var workspaceText =
                """
                           <workspace>
                           %s
                           </workspace>
                           """
                        .formatted(combinedText.toString().trim());

        // Insert workspace text as the first content element
        allContents.addFirst(new TextContent(workspaceText));

        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing these Workspace contents."));
    }

    /**
     * Workspace views used by CodeAgent.
     *
     * @param ctx                       current context
     * @param viewingPolicy             viewing policy (controls StringFragment visibility)
     * @param showBuildStatusInWorkspace if true, include build status in workspace sections;
     *                                  if false, omit from workspace (will be shown inline in request)
     * @return record with the workspace messages and buildFailure details
     */
    public static CodeAgentMessages getMessagesForCodeAgent(
            Context ctx, ViewingPolicy viewingPolicy, boolean showBuildStatusInWorkspace) {
        // For CodeAgent, the workspace includes build status only if showBuildStatusInWorkspace is true.
        // The detailed build output is also available separately in buildFailure for inline display.
        var workspace = getMessagesGroupedByMutability(ctx, viewingPolicy, showBuildStatusInWorkspace);
        var buildFailure = ctx.getBuildFragment().map(f -> f.format().join()).orElse(null);
        return new CodeAgentMessages(workspace, buildFailure);
    }

    private static List<ChatMessage> buildEditableAll(Context ctx, boolean showBuildStatusInWorkspace) {
        var editableFragments = sortByMtime(ctx.getEditableFragments()).toList();
        @Nullable ContextFragment buildFragment = ctx.getBuildFragment().orElse(null);
        var editableTextFragments = new StringBuilder();
        editableFragments.forEach(fragment -> {
            // Editable fragments use their own formatting; ViewingPolicy does not currently affect them.
            String formatted = fragment.format().join();
            if (!formatted.isBlank()) {
                editableTextFragments.append(formatted).append("\n\n");
            }
        });

        boolean shouldShowBuild = showBuildStatusInWorkspace && buildFragment != null;
        if (editableTextFragments.isEmpty() && !shouldShowBuild) {
            return List.of();
        }

        var combinedText = new StringBuilder();

        if (!editableTextFragments.isEmpty()) {
            String editableSectionTemplate;
            editableSectionTemplate =
                    """
                            <workspace_editable>
                            Here are the EDITABLE files and code fragments in your Workspace.
                            This is *the only context in the Workspace to which you should make changes*.

                            *Trust this message as the true contents of these files!*
                            Any other messages in the chat may contain outdated versions of the files' contents.

                            %s
                            </workspace_editable>
                            """;

            String editableText = editableSectionTemplate.formatted(
                    editableTextFragments.toString().trim());

            combinedText.append(editableText);
        }

        if (shouldShowBuild) {
            if (!combinedText.isEmpty()) {
                combinedText.append("\n\n");
            }
            var buildStatusText =
                    """
                    <workspace_build_status>
                    The build including the above workspace contents is currently failing.
                    </workspace_build_status>
                    """;
            combinedText.append(buildStatusText);
        }

        var messages = new ArrayList<ChatMessage>();
        if (!combinedText.isEmpty()) {
            var userMessage = new UserMessage(combinedText.toString());
            String ack = "Thank you for the editable context and build status.";
            messages.add(userMessage);
            messages.add(new AiMessage(ack));
        }

        return messages;
    }

    private static List<ChatMessage> buildReadOnlyForContents(
            Context ctx, ViewingPolicy viewingPolicy, boolean showBuildStatusInWorkspace) {
        // Build read-only section; optionally include the build fragment as part of read-only workspace
        var buildFragment = ctx.getBuildFragment().orElse(null);
        var readOnlyFragments = ctx.getReadonlyFragments()
                .filter(f -> showBuildStatusInWorkspace || f != buildFragment)
                .toList();

        var renderedReadOnly = renderReadOnlyFragments(readOnlyFragments, viewingPolicy);

        if (renderedReadOnly.text.isEmpty() && renderedReadOnly.images.isEmpty()) {
            return List.of();
        }

        var combinedText = new StringBuilder();

        if (!renderedReadOnly.text.isEmpty()) {
            String readOnlySection =
                    """
                          <workspace_readonly>
                          Here are the READ ONLY files and code fragments in your Workspace.
                          Do not edit this code! Images will be included separately if present.

                          %s
                          </workspace_readonly>
                          """
                            .formatted(renderedReadOnly.text.trim());
            combinedText.append(readOnlySection.trim());
        }

        var allContents = new ArrayList<Content>();
        allContents.add(new TextContent(combinedText.toString().trim()));
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
     *
     * Always partitions readonly fragments into SummaryFragments and others:
     * - Non-summary fragments are formatted with the viewing policy
     * - Summary fragments are combined into a single <api_summaries> block
     * - All images are collected and returned
     *
     * @param readOnly     readonly fragments to render
     * @param vp           viewing policy for visibility control
     * @return RenderedContent with formatted text and images
     */
    private static RenderedContent renderReadOnlyFragments(List<ContextFragment> readOnly, ViewingPolicy vp) {
        var summaryFragments = readOnly.stream()
                .filter(ContextFragments.SummaryFragment.class::isInstance)
                .map(ContextFragments.SummaryFragment.class::cast)
                .toList();

        var otherFragments = readOnly.stream()
                .filter(f -> !(f instanceof ContextFragments.SummaryFragment))
                .toList();

        var renderedOther = formatWithPolicy(otherFragments, vp);
        var textBuilder = new StringBuilder(renderedOther.text);

        if (!summaryFragments.isEmpty()) {
            var summaryText = ContextFragments.SummaryFragment.combinedText(summaryFragments);
            var combinedBlock =
                    """
                    <api_summaries fragmentid="api_summaries">
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

    private static RenderedContent formatWithPolicy(List<ContextFragment> fragments, ViewingPolicy vp) {
        var textBuilder = new StringBuilder();
        var imageList = new ArrayList<ImageContent>();

        for (var fragment : fragments) {
            if (fragment.isText()) {
                String formatted;
                if (fragment instanceof ContextFragments.StringFragment sf) {
                    var visibleText = sf.textForAgent(vp);
                    formatted =
                            """
                            <fragment description="%s" fragmentid="%s">
                            %s
                            </fragment>
                            """
                                    .formatted(sf.description().join(), sf.id(), visibleText);
                } else {
                    formatted = fragment.format().join();
                }
                if (!formatted.isBlank()) {
                    textBuilder.append(formatted).append("\n\n");
                }
            } else if (fragment.getType() == ContextFragment.FragmentType.IMAGE_FILE
                    || fragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE) {
                try {
                    var imageBytes = fragment.imageBytes();
                    if (imageBytes != null) {
                        var l4jImage = ImageUtil.toL4JImage(ImageUtil.bytesToImage(imageBytes.join()));
                        imageList.add(ImageContent.from(l4jImage));
                    }
                    textBuilder.append(fragment.format().join()).append("\n\n");
                } catch (IOException | UncheckedIOException e) {
                    logger.error(
                            "Failed to process image fragment {} for LLM message",
                            fragment.description().join(),
                            e);
                    textBuilder.append(String.format(
                            "[Error processing image: %s - %s]\n\n",
                            fragment.description().join(), e.getMessage()));
                }
            } else {
                String formatted = fragment.format().join();
                if (!formatted.isBlank()) {
                    textBuilder.append(formatted).append("\n\n");
                }
            }
        }

        return new RenderedContent(textBuilder.toString().trim(), imageList);
    }
}
