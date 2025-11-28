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
 * Minimal builder options (per decision in the conversation):
 * - view: one of the predefined workspace views used by callers.
 * - viewingPolicy: controls StringFragment visibility rendering.
 * - changedFiles: optional set of changed ProjectFiles (used for CodeAgent flows).
 *
 * The builder always:
 * - combines summary fragments into a single api_summaries block,
 * - appends an AiMessage acknowledgment,
 * - includes build status alongside editable sections when present,
 * - places untouched editable fragments with read-only for the CodeAgent read-only view.
 */
public final class WorkspacePrompts {
    private static final Logger logger = LogManager.getLogger(WorkspacePrompts.class);

    private final Context ctx;
    private final ViewingPolicy viewingPolicy;
    private final WorkspaceView view;
    private final Set<ProjectFile> changedFiles;

    private WorkspacePrompts(
            Context ctx, ViewingPolicy viewingPolicy, WorkspaceView view, Set<ProjectFile> changedFiles) {
        this.ctx = ctx;
        this.viewingPolicy = viewingPolicy;
        this.view = view;
        this.changedFiles = changedFiles;
    }

    public static Builder builder(Context ctx, ViewingPolicy viewingPolicy) {
        return new Builder(ctx, viewingPolicy);
    }

    public static String formatWorkspaceToc(Context ctx) {
        var editableContents =
                ctx.getEditableFragments().map(ContextFragment::formatToc).collect(Collectors.joining("\n"));
        var readOnlyContents =
                ctx.getReadonlyFragments().map(ContextFragment::formatToc).collect(Collectors.joining("\n"));
        var buildFragment = ctx.getBuildFragment();
        var workspaceBuilder = new StringBuilder();

        workspaceBuilder.append("<workspace_toc>\n");

        if (!readOnlyContents.isBlank()) {
            workspaceBuilder.append(
                    """
                    <workspace_readonly>
                    The following fragments MAY NOT BE EDITED:
                    %s
                    </workspace_readonly>
                    """
                            .formatted(readOnlyContents));
        } else {
            workspaceBuilder.append("  <workspace_readonly>\n  </workspace_readonly>\n");
        }

        if (!editableContents.isBlank()) {
            workspaceBuilder.append(
                    """
                    <workspace_editable_unchanged>
                    The following fragments MAY BE EDITED:
                    %s
                    </workspace_editable_unchanged>
                    """
                            .formatted(editableContents));
        } else {
            workspaceBuilder.append("  <workspace_editable_unchanged>\n  </workspace_editable_unchanged>\n");
        }

        workspaceBuilder.append("  <workspace_editable_changed>\n  </workspace_editable_changed>\n");

        if (buildFragment.isPresent()) {
            workspaceBuilder.append(
                    "  <workspace_build_status>\n  Build status information may be included.\n  </workspace_build_status>\n");
        } else {
            workspaceBuilder.append("  <workspace_build_status>\n  </workspace_build_status>\n");
        }

        workspaceBuilder.append("</workspace_toc>");

        return workspaceBuilder.toString();
    }

    public enum WorkspaceView {
        // Read-only fragments + untouched editable (used by CodeAgent as the first workspace message)
        CODE_READONLY_PLUS_UNTOUCHED,
        // Editable fragments that have been changed in the current task + build status
        EDITABLE_CHANGED,
        // Generic combined workspace: readonly + editable(all) + build status (wrapped in <workspace>)
        GROUPED_BY_MUTABILITY,
        // All fragments in the added order (ctx.allFragments()), wrapped in <workspace>
        IN_ADDED_ORDER
    }

    public static final class Builder {
        private final Context ctx;
        private final ViewingPolicy viewingPolicy;
        private WorkspaceView view = WorkspaceView.GROUPED_BY_MUTABILITY;
        private Set<ProjectFile> changedFiles = Set.of();

        private Builder(Context ctx, ViewingPolicy viewingPolicy) {
            this.ctx = ctx;
            this.viewingPolicy = viewingPolicy;
        }

        public Builder view(WorkspaceView v) {
            this.view = v;
            return this;
        }

        public Builder changedFiles(Set<ProjectFile> changed) {
            this.changedFiles = changed;
            return this;
        }

        public List<ChatMessage> build() {
            var wp = new WorkspacePrompts(ctx, viewingPolicy, view, changedFiles);
            return wp.buildMessages();
        }
    }

    private List<ChatMessage> buildMessages() {
        return switch (view) {
            case CODE_READONLY_PLUS_UNTOUCHED -> buildReadOnlyPlusUntouched();
            case EDITABLE_CHANGED -> buildEditableChanged();
            case GROUPED_BY_MUTABILITY -> buildContents();
            case IN_ADDED_ORDER -> buildInAddedOrder();
        };
    }

    private List<ChatMessage> buildReadOnlyPlusUntouched() {
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

        // Include build fragment when changedFiles is empty (avoid duplication when EDITABLE_CHANGED will also include
        // it)
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

    private List<ChatMessage> buildEditableChanged() {
        // Build editable fragments that intersect changedFiles, include buildFragment as workspace_build_status
        if (changedFiles.isEmpty()) {
            return List.of();
        }

        var allEditable = ctx.getEditableFragments().toList();
        var changedEditable = allEditable.stream()
                .filter(f -> f.files().stream().anyMatch(changedFiles::contains))
                .toList();

        return buildEditableInternal(changedEditable, ctx.getBuildFragment().orElse(null), true);
    }

    private List<ChatMessage> buildEditableAll() {
        var editableFragments = ctx.getEditableFragments().toList();
        return buildEditableInternal(editableFragments, ctx.getBuildFragment().orElse(null), false);
    }

    private List<ChatMessage> buildEditableInternal(
            List<ContextFragment> editableFragments,
            @Nullable ContextFragment buildFragment,
            boolean highlightChanged) {
        var editableTextFragments = new StringBuilder();
        editableFragments.forEach(fragment -> {
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
            String tagName;
            if (highlightChanged) {
                tagName = "workspace_editable_changed";
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
                tagName = "workspace_editable_unchanged";
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

    private List<ChatMessage> buildContents() {
        // Compose read-only (without build fragment) + all editable + build status into a single <workspace> message
        var readOnlyMessages = buildReadOnlyForContents();
        var editableMessages = buildEditableAll();

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

        allContents.addFirst(new TextContent(workspaceText));

        var workspaceUserMessage = UserMessage.from(allContents);
        return List.of(workspaceUserMessage, new AiMessage("Thank you for providing these Workspace contents."));
    }

    private List<ChatMessage> buildReadOnlyForContents() {
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

    private List<ChatMessage> buildInAddedOrder() {
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

    // --- Helper rendering utilities (copied and adapted from original CodePrompts) ---

    private static final class RenderedContent {
        final String text;
        final List<ImageContent> images;

        RenderedContent(String text, List<ImageContent> images) {
            this.text = text;
            this.images = images;
        }
    }

    private RenderedContent formatWithPolicy(List<ContextFragment> fragments, ViewingPolicy vp) {
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
