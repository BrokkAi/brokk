package ai.brokk.prompts;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Encapsulates workspace-related prompt construction. Extracted from CodePrompts to centralize workspace rendering.
 *
 * This class now exposes simple static helpers instead of a builder:
 * - {@link #getMessagesInAddedOrder(Context, ViewingPolicy)}
 * - {@link #getMessagesGroupedByMutability(Context, ViewingPolicy)}
 * - {@link #getMessagesForCodeAgent(Context, ViewingPolicy, Set)}
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
     * Record containing both views that CodeAgent needs:
     * - readOnlyPlusUntouched: read-only fragments and untouched editable fragments
     * - editableChanged: editable fragments that intersect the changed files plus build status
     */
    public record CodeAgentMessages(
            List<ChatMessage> readOnlyPlusUntouched, List<ChatMessage> editableChanged) {}

    public static String formatGroupedToc(Context ctx) {
        var editableContents =
                ctx.getEditableFragments().map(ContextFragment::formatToc).collect(Collectors.joining("\n"));
        var readOnlyContents =
                ctx.getReadonlyFragments().map(ContextFragment::formatToc).collect(Collectors.joining("\n"));
        var buildFragment = ctx.getBuildFragment();

        var readOnlySection = readOnlyContents.isBlank()
                ? "  <workspace_readonly>\n  </workspace_readonly>"
                : """
                  <workspace_readonly>
                  The following fragments MAY NOT BE EDITED:
                  %s
                  </workspace_readonly>"""
                        .formatted(readOnlyContents);

        var editableSection = editableContents.isBlank()
                ? ""
                : """
                  <workspace_editable>
                  The following fragments MAY BE EDITED:
                  %s
                  </workspace_editable>"""
                        .formatted(editableContents);

        var buildStatusSection = buildFragment.isPresent()
                ? "  <workspace_build_status>(failing)</workspace_build_status>"
                : "";

        var parts = new ArrayList<String>();
        parts.add(readOnlySection);
        if (!editableSection.isBlank()) {
            parts.add(editableSection);
        }
        if (!buildStatusSection.isBlank()) {
            parts.add(buildStatusSection);
        }

        return """
               <workspace_toc>
               %s
               </workspace_toc>"""
                .formatted(String.join("\n", parts));
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
    public static List<ChatMessage> getMessagesGroupedByMutability(
            Context ctx, ViewingPolicy viewingPolicy) {
        // Compose read-only (without build fragment) + all editable + build status into a single <workspace> message
        var readOnlyMessages = buildReadOnlyForContents(ctx, viewingPolicy);
        var editableMessages = buildEditableAll(ctx, viewingPolicy);

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
        allContents.add(0, new TextContent(workspaceText));

        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing these Workspace contents."));
    }

    /**
     * Workspace views used by CodeAgent.
     *
     * @param ctx           current context
     * @param viewingPolicy viewing policy (controls StringFragment visibility)
     * @param changedFiles  editable project files that have changed in this task
     * @return record with both the read-only-plus-untouched view and the editable-changed view
     */
    public static CodeAgentMessages getMessagesForCodeAgent(
            Context ctx, ViewingPolicy viewingPolicy, Set<ProjectFile> changedFiles) {
        var readOnlyPlusUntouched = buildReadOnlyPlusUntouched(ctx, viewingPolicy, changedFiles);
        var editableChanged = changedFiles.isEmpty()
                ? List.<ChatMessage>of()
                : buildEditableChanged(ctx, viewingPolicy, changedFiles);
        return new CodeAgentMessages(readOnlyPlusUntouched, editableChanged);
    }

    private static List<ChatMessage> buildReadOnlyPlusUntouched(
            Context ctx, ViewingPolicy viewingPolicy, Set<ProjectFile> changedFiles) {
        var buildFragment = ctx.getBuildFragment().orElse(null);
        var readOnlyFragments =
                ctx.getReadonlyFragments().filter(f -> f != buildFragment).toList();

        // Combine summary fragments always
        var summaryFragments = readOnlyFragments.stream()
                .filter(ContextFragment.SummaryFragment.class::isInstance)
                .map(ContextFragment.SummaryFragment.class::cast)
                .toList();

        var otherFragments = readOnlyFragments.stream()
                .filter(f -> !(f instanceof ContextFragment.SummaryFragment))
                .toList();

        var renderedReadOnly = formatWithPolicy(otherFragments, viewingPolicy);
        var readOnlyText = new StringBuilder(renderedReadOnly.text);
        var allImages = new ArrayList<>(renderedReadOnly.images);

        if (!summaryFragments.isEmpty()) {
            var summaryText = ContextFragment.SummaryFragment.combinedText(summaryFragments);
            var combinedBlock =
                    """
                    <api_summaries fragmentid="api_summaries">
                    %s
                    </api_summaries>
                    """
                            .formatted(summaryText);
            if (!renderedReadOnly.text.isEmpty()) {
                readOnlyText.append("\n\n");
            }
            readOnlyText.append(combinedBlock).append("\n\n");
        }

        // Include build fragment when changedFiles is empty (avoid duplication when EDITABLE_CHANGED will also include it)
        if (buildFragment != null && changedFiles.isEmpty()) {
            if (!readOnlyText.isEmpty()) {
                readOnlyText.append("\n\n");
            }
            var buildStatusText =
                    """
                    <workspace_build_status>
                    The build including the above workspace contents is currently failing.
                    %s
                    </workspace_build_status>
                    """
                            .formatted(buildFragment.format());
            readOnlyText.append(buildStatusText);
        }

        // Untouched editable: only when changedFiles is non-empty
        String untouchedSection = "";
        if (!changedFiles.isEmpty()) {
            var editableFragments = ctx.getEditableFragments().toList();
            var untouchedEditable = editableFragments.stream()
                    .filter(f -> f.files().stream().noneMatch(changedFiles::contains))
                    .toList();

            if (!untouchedEditable.isEmpty()) {
                var renderedUntouched = formatWithPolicy(untouchedEditable, viewingPolicy);
                if (!renderedUntouched.text.isEmpty()) {
                    untouchedSection =
                            """
                            <workspace_editable_unchanged>
                            Here are EDITABLE files and code fragments that have not been changed yet in this task.

                            %s
                            </workspace_editable_unchanged>
                            """
                                    .formatted(renderedUntouched.text);
                }
                allImages.addAll(renderedUntouched.images);
            }
        }

        if (readOnlyText.isEmpty() && untouchedSection.isBlank() && allImages.isEmpty()) {
            return List.of();
        }

        var combinedText = new StringBuilder();

        if (!readOnlyText.isEmpty()) {
            String readOnlySection =
                    """
                          <workspace_readonly>
                          Here are the READ ONLY files and code fragments in your Workspace.
                          Do not edit this code! Images will be included separately if present.

                          %s
                          </workspace_readonly>
                          """
                            .formatted(readOnlyText.toString().trim());
            combinedText.append(readOnlySection.trim());
        }

        if (!untouchedSection.isBlank()) {
            if (!combinedText.isEmpty()) {
                combinedText.append("\n\n");
            }
            combinedText.append(untouchedSection.trim());
        }

        var allContents = new ArrayList<Content>();
        allContents.add(new TextContent(combinedText.toString().trim()));
        allContents.addAll(allImages);

        var readOnlyUserMessage = UserMessage.from(allContents);
        return List.of(
                readOnlyUserMessage, new AiMessage("Thank you for the read-only and unchanged editable context."));
    }

    private static List<ChatMessage> buildEditableChanged(
            Context ctx, ViewingPolicy viewingPolicy, Set<ProjectFile> changedFiles) {
        if (changedFiles.isEmpty()) {
            return List.of();
        }

        var allEditable = ctx.getEditableFragments().toList();
        var changedEditable = allEditable.stream()
                .filter(f -> f.files().stream().anyMatch(changedFiles::contains))
                .toList();

        return buildEditableInternal(
                changedEditable, ctx.getBuildFragment().orElse(null), true, viewingPolicy);
    }

    private static List<ChatMessage> buildEditableAll(Context ctx, ViewingPolicy viewingPolicy) {
        var editableFragments = ctx.getEditableFragments().toList();
        return buildEditableInternal(
                editableFragments, ctx.getBuildFragment().orElse(null), false, viewingPolicy);
    }

    private static List<ChatMessage> buildEditableInternal(
            List<ContextFragment> editableFragments,
            @Nullable ContextFragment buildFragment,
            boolean highlightChanged,
            ViewingPolicy viewingPolicy) {
        var editableTextFragments = new StringBuilder();
        editableFragments.forEach(fragment -> {
            // Editable fragments use their own formatting; ViewingPolicy does not currently affect them.
            String formatted = fragment.format();
            if (!formatted.isBlank()) {
                editableTextFragments.append(formatted).append("\n\n");
            }
        });

        if (editableTextFragments.isEmpty() && buildFragment == null) {
            return List.of();
        }

        var combinedText = new StringBuilder();

        if (!editableTextFragments.isEmpty()) {
            String editableSectionTemplate;
            if (highlightChanged) {
                editableSectionTemplate =
                        """
                                  <workspace_editable_changed>
                                  Here are the EDITABLE files and code fragments in your Workspace that have been CHANGED so far in this task.

                                  *Trust this message as the true contents of these files!*
                                  Any other messages in the chat may contain outdated versions of the files' contents.

                                  %s
                                  </workspace_editable_changed>
                                  """;
            } else {
                editableSectionTemplate =
                        """
                                  <workspace_editable_unchanged>
                                  Here are the EDITABLE files and code fragments in your Workspace.
                                  This is *the only context in the Workspace to which you should make changes*.

                                  *Trust this message as the true contents of these files!*
                                  Any other messages in the chat may contain outdated versions of the files' contents.

                                  %s
                                  </workspace_editable_unchanged>
                                  """;
            }

            String editableText = editableSectionTemplate.formatted(
                    editableTextFragments.toString().trim());

            combinedText.append(editableText);
        }

        if (buildFragment != null) {
            if (!combinedText.isEmpty()) {
                combinedText.append("\n\n");
            }
            var buildStatusText =
                    """
                    <workspace_build_status>
                    The build including the above workspace contents is currently failing.
                    %s
                    </workspace_build_status>
                    """
                            .formatted(buildFragment.format());
            combinedText.append(buildStatusText);
        }

        var messages = new ArrayList<ChatMessage>();
        if (!combinedText.isEmpty()) {
            var userMessage = new UserMessage(combinedText.toString());
            String ack = highlightChanged
                    ? "Thank you for the changed editable context and build status."
                    : "Thank you for the editable context and build status.";
            messages.add(userMessage);
            messages.add(new AiMessage(ack));
        }

        return messages;
    }

    private static List<ChatMessage> buildReadOnlyForContents(Context ctx, ViewingPolicy viewingPolicy) {
        // Build read-only section without including the build fragment
        var buildFragment = ctx.getBuildFragment().orElse(null);
        var readOnlyFragments =
                ctx.getReadonlyFragments().filter(f -> f != buildFragment).toList();

        var summaryFragments = readOnlyFragments.stream()
                .filter(ContextFragment.SummaryFragment.class::isInstance)
                .map(ContextFragment.SummaryFragment.class::cast)
                .toList();

        var otherFragments = readOnlyFragments.stream()
                .filter(f -> !(f instanceof ContextFragment.SummaryFragment))
                .toList();

        var renderedReadOnly = formatWithPolicy(otherFragments, viewingPolicy);
        var readOnlyText = new StringBuilder(renderedReadOnly.text);
        var allImages = new ArrayList<>(renderedReadOnly.images);

        if (!summaryFragments.isEmpty()) {
            var summaryText = ContextFragment.SummaryFragment.combinedText(summaryFragments);
            var combinedBlock =
                    """
                    <api_summaries fragmentid="api_summaries">
                    %s
                    </api_summaries>
                    """
                            .formatted(summaryText);
            if (!renderedReadOnly.text.isEmpty()) {
                readOnlyText.append("\n\n");
            }
            readOnlyText.append(combinedBlock).append("\n\n");
        }

        if (readOnlyText.isEmpty() && allImages.isEmpty()) {
            return List.of();
        }

        var combinedText = new StringBuilder();

        if (!readOnlyText.isEmpty()) {
            String readOnlySection =
                    """
                          <workspace_readonly>
                          Here are the READ ONLY files and code fragments in your Workspace.
                          Do not edit this code! Images will be included separately if present.

                          %s
                          </workspace_readonly>
                          """
                            .formatted(readOnlyText.toString().trim());
            combinedText.append(readOnlySection.trim());
        }

        var allContents = new ArrayList<Content>();
        allContents.add(new TextContent(combinedText.toString().trim()));
        allContents.addAll(allImages);

        var readOnlyUserMessage = UserMessage.from(allContents);
        return List.of(
                readOnlyUserMessage, new AiMessage("Thank you for the read-only and unchanged editable context."));
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

    private static RenderedContent formatWithPolicy(List<ContextFragment> fragments, ViewingPolicy vp) {
        var textBuilder = new StringBuilder();
        var imageList = new ArrayList<ImageContent>();

        for (var fragment : fragments) {
            if (fragment.isText()) {
                String formatted;
                if (fragment instanceof ContextFragment.StringFragment sf) {
                    var visibleText = sf.textForAgent(vp);
                    formatted =
                            """
                            <fragment description="%s" fragmentid="%s">
                            %s
                            </fragment>
                            """
                                    .formatted(sf.description(), sf.id(), visibleText);
                } else {
                    formatted = fragment.format();
                }
                if (!formatted.isBlank()) {
                    textBuilder.append(formatted).append("\n\n");
                }
            } else if (fragment.getType() == ContextFragment.FragmentType.IMAGE_FILE
                    || fragment.getType() == ContextFragment.FragmentType.PASTE_IMAGE) {
                try {
                    var l4jImage = ImageUtil.toL4JImage(fragment.image());
                    imageList.add(ImageContent.from(l4jImage));
                    textBuilder.append(fragment.format()).append("\n\n");
                } catch (IOException | UncheckedIOException e) {
                    logger.error("Failed to process image fragment {} for LLM message", fragment.description(), e);
                    textBuilder.append(String.format(
                            "[Error processing image: %s - %s]\n\n", fragment.description(), e.getMessage()));
                }
            } else {
                String formatted = fragment.format();
                if (!formatted.isBlank()) {
                    textBuilder.append(formatted).append("\n\n");
                }
            }
        }

        return new RenderedContent(textBuilder.toString().trim(), imageList);
    }
}
