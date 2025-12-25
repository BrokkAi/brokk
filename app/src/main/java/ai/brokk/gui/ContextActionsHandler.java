package ai.brokk.gui;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.AnalyzerWrapper;
import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.gui.dialogs.AttachContextDialog;
import ai.brokk.gui.dialogs.CallGraphDialog;
import ai.brokk.gui.dialogs.SymbolSelectionDialog;
import ai.brokk.prompts.CopyExternalPrompts;
import ai.brokk.tools.WorkspaceTools;
import ai.brokk.util.HtmlToMarkdown;
import ai.brokk.util.ImageUtil;
import ai.brokk.util.Messages;
import ai.brokk.util.StackTrace;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import java.awt.Component;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

public class ContextActionsHandler {
    private static final Logger logger = LogManager.getLogger(ContextActionsHandler.class);

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    public enum ContextAction {
        EDIT,
        SUMMARIZE,
        DROP,
        COPY,
        PASTE,
        RUN_TESTS
    }

    public enum PopupMenuMode {
        FULL,
        COPY_ONLY
    }

    public sealed interface PopupScenario permits NoSelection, FileBadge, SingleFragment, MultiFragment {
        List<Action> getActions(ContextActionsHandler actions);
    }

    public static final class NoSelection implements PopupScenario {
        @Override
        public List<Action> getActions(ContextActionsHandler actions) {
            var list = new ArrayList<Action>();

            // Always add drop all action but enable/disable based on workspace state
            var dropAllAction = WorkspaceAction.DROP_ALL.createAction(actions);
            if (!actions.isWorkspaceEditable()) {
                dropAllAction.setEnabled(false);
                dropAllAction.putValue(Action.SHORT_DESCRIPTION, "Drop All is disabled in read-only mode");
            }
            list.add(dropAllAction);

            list.add(WorkspaceAction.COPY_ALL.createAction(actions));
            list.add(WorkspaceAction.PASTE.createAction(actions));

            return list;
        }
    }

    public static final class FileBadge implements PopupScenario {
        private final TableUtils.FileReferenceList.FileReferenceData fileRef;

        public FileBadge(TableUtils.FileReferenceList.FileReferenceData fileRef) {
            this.fileRef = fileRef;
        }

        @Override
        public List<Action> getActions(ContextActionsHandler actions) {
            var list = new ArrayList<Action>();

            if (fileRef.getRepoFile() != null) {
                list.add(WorkspaceAction.SHOW_IN_PROJECT.createFileAction(actions, fileRef.getRepoFile()));
                list.add(WorkspaceAction.VIEW_FILE.createFileAction(actions, fileRef.getRepoFile()));

                if (actions.hasGit()) {
                    list.add(WorkspaceAction.VIEW_HISTORY.createFileAction(actions, fileRef.getRepoFile()));
                } else {
                    list.add(WorkspaceAction.VIEW_HISTORY.createDisabledAction("Git not available for this project."));
                }
                if (ContextManager.isTestFile(fileRef.getRepoFile())) {
                    list.add(WorkspaceAction.RUN_TESTS.createFileRefAction(actions, fileRef));
                } else {
                    var disabledAction = WorkspaceAction.RUN_TESTS.createDisabledAction("Not a test file");
                    list.add(disabledAction);
                }

                list.add(null); // Separator
                list.add(WorkspaceAction.EDIT_FILE.createFileRefAction(actions, fileRef));
                list.add(WorkspaceAction.SUMMARIZE_FILE.createFileRefAction(actions, fileRef));
            }

            return list;
        }
    }

    public static final class SingleFragment implements PopupScenario {
        private final ContextFragment fragment;

        public SingleFragment(ContextFragment fragment) {
            this.fragment = fragment;
        }

        @Override
        @Blocking
        public List<Action> getActions(ContextActionsHandler actions) {
            var list = new ArrayList<Action>();

            // Show in Project (for PROJECT_PATH fragments)
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                fragment.files().join().stream()
                        .findFirst()
                        .ifPresent(projectFile ->
                                list.add(WorkspaceAction.SHOW_IN_PROJECT.createFileAction(actions, projectFile)));
            }

            // View File/Contents
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                list.add(WorkspaceAction.VIEW_FILE.createFragmentAction(actions, fragment));
            } else {
                list.add(WorkspaceAction.SHOW_CONTENTS.createFragmentAction(actions, fragment));
            }

            // View History/Compress History
            if (actions.hasGit() && fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                fragment.files().join().stream()
                        .findFirst()
                        .ifPresent(projectFile ->
                                list.add(WorkspaceAction.VIEW_HISTORY.createFileAction(actions, projectFile)));
            } else if (fragment.getType() == ContextFragment.FragmentType.HISTORY) {
                var cf = (ContextFragments.HistoryFragment) fragment;
                var uncompressedExists = cf.entries().stream().anyMatch(entry -> !entry.isCompressed());
                if (uncompressedExists) {
                    list.add(WorkspaceAction.COMPRESS_HISTORY.createAction(actions));
                } else {
                    list.add(WorkspaceAction.COMPRESS_HISTORY.createDisabledAction(
                            "No uncompressed history to compress"));
                }
            } else {
                list.add(WorkspaceAction.VIEW_HISTORY.createDisabledAction(
                        "View History is available only for single project files."));
            }

            // Add Run Tests action if the fragment is associated with a test file
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH
                    && fragment.files().join().stream().anyMatch(ContextManager::isTestFile)) {
                list.add(WorkspaceAction.RUN_TESTS.createFragmentsAction(actions, List.of(fragment)));
            } else {
                var disabledAction = WorkspaceAction.RUN_TESTS.createDisabledAction("No test files in selection");
                list.add(disabledAction);
            }

            list.add(null); // Separator

            // Edit/Read/Summarize
            if (fragment.getType() == ContextFragment.FragmentType.PROJECT_PATH) {
                fragment.files().join().stream().findFirst().ifPresent(projectFile -> {
                    var fileData = new TableUtils.FileReferenceList.FileReferenceData(
                            projectFile.getFileName(), projectFile.toString(), projectFile);

                    // Check if already editable
                    var ctx = actions.contextManager.selectedContext();
                    boolean isAlreadyEditable =
                            ctx != null && ctx.fileFragments().anyMatch(f -> f == fragment);

                    if (isAlreadyEditable) {
                        list.add(WorkspaceAction.EDIT_FILE.createDisabledAction("Already in edit mode"));
                    } else {
                        list.add(WorkspaceAction.EDIT_FILE.createFileRefAction(actions, fileData));
                    }

                    // Summarize the exact fragment instance that was clicked, so we can drop that same instance after.
                    list.add(new AbstractAction("Summarize " + projectFile.getFileName()) {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            actions.performContextActionAsync(ContextAction.SUMMARIZE, List.of(fragment));
                        }
                    });
                });
            } else {
                var selectedFragments = List.of(fragment);
                actions.addEditAndSummarizeActions(selectedFragments, list);
            }

            list.add(null); // Separator
            list.add(WorkspaceAction.COPY.createFragmentsAction(actions, List.of(fragment)));

            // Always add drop action but enable/disable based on workspace state
            var dropAction = WorkspaceAction.DROP.createFragmentsAction(actions, List.of(fragment));
            if (!actions.isWorkspaceEditable() || !actions.isOnLatestContext()) {
                dropAction.setEnabled(false);
                String tooltip = !actions.isWorkspaceEditable()
                        ? "Drop is disabled in read-only mode"
                        : "Drop is only available when viewing the latest context";
                dropAction.putValue(Action.SHORT_DESCRIPTION, tooltip);
            }
            list.add(dropAction);

            return list;
        }
    }

    public static final class MultiFragment implements PopupScenario {
        private final List<ContextFragment> fragments;

        public MultiFragment(List<ContextFragment> fragments) {
            this.fragments = fragments;
        }

        @Override
        @Blocking
        public List<Action> getActions(ContextActionsHandler actions) {
            var list = new ArrayList<Action>();

            list.add(WorkspaceAction.SHOW_CONTENTS.createDisabledAction(
                    "Cannot view contents of multiple items at once."));
            list.add(WorkspaceAction.VIEW_HISTORY.createDisabledAction("Cannot view history for multiple items."));
            // Add Run Tests action if all selected fragment is associated with a test file
            if (fragments.stream().flatMap(f -> f.files().join().stream()).allMatch(ContextManager::isTestFile)) {
                list.add(WorkspaceAction.RUN_TESTS.createFragmentsAction(actions, fragments));
            } else {
                var disabledAction = WorkspaceAction.RUN_TESTS.createDisabledAction("No test files in selection");
                list.add(disabledAction);
            }

            list.add(null); // Separator

            actions.addEditAndSummarizeActions(fragments, list);

            list.add(null); // Separator
            list.add(WorkspaceAction.COPY.createFragmentsAction(actions, fragments));

            // Always add drop action but enable/disable based on workspace state
            var dropAction = WorkspaceAction.DROP.createFragmentsAction(actions, fragments);
            if (!actions.isWorkspaceEditable() || !actions.isOnLatestContext()) {
                dropAction.setEnabled(false);
                String tooltip = !actions.isWorkspaceEditable()
                        ? "Drop is disabled in read-only mode"
                        : "Drop is only available when viewing the latest context";
                dropAction.putValue(Action.SHORT_DESCRIPTION, tooltip);
            }
            list.add(dropAction);

            return list;
        }
    }

    public enum WorkspaceAction {
        SHOW_IN_PROJECT("Show in Project"),
        VIEW_FILE("View File"),
        SHOW_CONTENTS("Show Contents"),
        VIEW_HISTORY("View History"),
        COMPRESS_HISTORY("Compress History"),
        EDIT_FILE("Edit File"),
        SUMMARIZE_FILE("Summarize File"),
        EDIT_ALL_REFS("Edit all References"),
        SUMMARIZE_ALL_REFS("Summarize all References"),
        COPY("Copy"),
        DROP("Drop"),
        DROP_ALL("Drop All"),
        COPY_ALL("Copy All"),
        PASTE("Paste text, images, urls"),
        RUN_TESTS("Run Tests");

        private final String label;

        WorkspaceAction(String label) {
            this.label = label;
        }

        public AbstractAction createAction(ContextActionsHandler actions) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switch (WorkspaceAction.this) {
                        case DROP_ALL -> actions.performContextActionAsync(ContextAction.DROP, List.of());
                        case COPY_ALL -> actions.performContextActionAsync(ContextAction.COPY, List.of());
                        case PASTE -> actions.performContextActionAsync(ContextAction.PASTE, List.of());
                        case COMPRESS_HISTORY -> actions.contextManager.compressHistoryAsync();
                        case RUN_TESTS -> actions.performContextActionAsync(ContextAction.RUN_TESTS, List.of());
                        default ->
                            throw new UnsupportedOperationException("Action not implemented: " + WorkspaceAction.this);
                    }
                }
            };
        }

        public AbstractAction createDisabledAction(String tooltip) {
            var action = new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    // Disabled actions do nothing
                }
            };
            action.setEnabled(false);
            action.putValue(Action.SHORT_DESCRIPTION, tooltip);
            return action;
        }

        public AbstractAction createFileAction(ContextActionsHandler actions, ProjectFile file) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switch (WorkspaceAction.this) {
                        case SHOW_IN_PROJECT -> actions.chrome.showFileInProjectTree(file);
                        case VIEW_FILE -> {
                            var fragment = new ContextFragments.ProjectPathFragment(file, actions.contextManager);
                            actions.chrome.openFragmentPreview(fragment);
                        }
                        case VIEW_HISTORY -> actions.chrome.addFileHistoryTab(file);
                        default ->
                            throw new UnsupportedOperationException(
                                    "File action not implemented: " + WorkspaceAction.this);
                    }
                }
            };
        }

        public AbstractAction createFileRefAction(
                ContextActionsHandler actions, TableUtils.FileReferenceList.FileReferenceData fileRef) {
            var baseName = this == EDIT_FILE ? "Edit " : "Summarize ";
            return new AbstractAction(baseName + fileRef.getFullPath()) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (fileRef.getRepoFile() != null) {
                        var contextAction =
                                switch (WorkspaceAction.this) {
                                    case EDIT_FILE -> ContextAction.EDIT;
                                    case SUMMARIZE_FILE -> ContextAction.SUMMARIZE;
                                    default ->
                                        throw new UnsupportedOperationException(
                                                "File ref action not implemented: " + WorkspaceAction.this);
                                };
                        var fragment =
                                new ContextFragments.ProjectPathFragment(fileRef.getRepoFile(), actions.contextManager);
                        actions.performContextActionAsync(contextAction, List.of(fragment));
                    } else {
                        actions.chrome.toolError("Cannot " + label.toLowerCase(Locale.ROOT) + ": "
                                + fileRef.getFullPath()
                                + " - no ProjectFile available");
                    }

                    // Apply edit restrictions
                    if (WorkspaceAction.this == EDIT_FILE) {
                        var project = actions.contextManager.getProject();
                        var repoFile = fileRef.getRepoFile();
                        if (!actions.hasGit()) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "Editing not available without Git");
                        } else if (repoFile == null) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "Editing not available for external files");
                        } else if (!project.getRepo().getTrackedFiles().contains(repoFile)) {
                            setEnabled(false);
                            putValue(Action.SHORT_DESCRIPTION, "Cannot edit untracked file: " + fileRef.getFullPath());
                        }
                    }
                }
            };
        }

        public AbstractAction createFragmentAction(ContextActionsHandler actions, ContextFragment fragment) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    switch (WorkspaceAction.this) {
                        case VIEW_FILE, SHOW_CONTENTS -> actions.chrome.openFragmentPreview(fragment);
                        default ->
                            throw new UnsupportedOperationException(
                                    "Fragment action not implemented: " + WorkspaceAction.this);
                    }
                }
            };
        }

        public AbstractAction createFragmentsAction(ContextActionsHandler actions, List<ContextFragment> fragments) {
            return new AbstractAction(label) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    var contextAction =
                            switch (WorkspaceAction.this) {
                                case EDIT_ALL_REFS -> ContextAction.EDIT;
                                case SUMMARIZE_ALL_REFS -> ContextAction.SUMMARIZE;
                                case COPY -> ContextAction.COPY;
                                case DROP -> ContextAction.DROP;
                                case RUN_TESTS -> ContextAction.RUN_TESTS;
                                default ->
                                    throw new UnsupportedOperationException(
                                            "Fragments action not implemented: " + WorkspaceAction.this);
                            };
                    actions.performContextActionAsync(contextAction, fragments);
                }
            };
        }
    }

    public static class PopupBuilder {
        private final JPopupMenu popup;
        private final Chrome chrome;

        private PopupBuilder(Chrome chrome) {
            this.popup = new JPopupMenu();
            this.chrome = chrome;
        }

        public static PopupBuilder create(Chrome chrome) {
            return new PopupBuilder(chrome);
        }

        public PopupBuilder add(List<Action> actions) {
            for (var action : actions) {
                if (action == null) {
                    popup.addSeparator();
                } else {
                    popup.add(new JMenuItem(action));
                }
            }
            return this;
        }

        public PopupBuilder addSeparator() {
            popup.addSeparator();
            return this;
        }

        public void show(Component invoker, int x, int y) {
            chrome.getThemeManager().registerPopupMenu(popup);
            popup.show(invoker, x, y);
        }
    }

    private final Chrome chrome;
    private final ContextManager contextManager;

    public ContextActionsHandler(Chrome chrome, ContextManager contextManager) {
        this.chrome = chrome;
        this.contextManager = contextManager;
    }

    public ContextManager getContextManager() {
        return contextManager;
    }

    public Future<?> performContextActionAsync(
            ContextAction action, List<? extends ContextFragment> selectedFragments) {
        return contextManager.submitContextTask(() -> {
            try {
                switch (action) {
                    case EDIT -> doEditAction(selectedFragments);
                    case COPY -> doCopyAction(selectedFragments);
                    case DROP -> doDropAction(selectedFragments);
                    case SUMMARIZE -> doSummarizeAction(selectedFragments);
                    case PASTE -> doPasteAction();
                    case RUN_TESTS -> doRunTestsAction(selectedFragments);
                }
            } catch (CancellationException | InterruptedException cex) {
                chrome.showNotification(IConsoleIO.NotificationRole.INFO, action + " canceled.");
            } finally {
                SwingUtilities.invokeLater(chrome::focusInput);
            }
        });
    }

    public void attachContextViaDialog() {
        attachContextViaDialog(false);
    }

    public void attachContextViaDialog(boolean defaultSummarizeChecked) {
        assert SwingUtilities.isEventDispatchThread();
        var dlg = new AttachContextDialog(chrome.getFrame(), contextManager, defaultSummarizeChecked);
        dlg.setLocationRelativeTo(chrome.getFrame());
        dlg.setVisible(true);
        var fragments = dlg.getSelectedFragments();

        if (fragments == null) return;

        contextManager.submitContextTask(() -> {
            if (fragments.isEmpty()) {
                return;
            }

            for (var fragment : fragments) {
                if (fragment instanceof ContextFragments.PathFragment pathFrag) {
                    contextManager.addFragmentAsync(pathFrag);
                } else {
                    contextManager.addFragments(fragment);
                }
            }
        });
    }

    @Blocking
    private void doEditAction(List<? extends ContextFragment> selectedFragments) {
        assert !selectedFragments.isEmpty();
        var files = selectedFragments.stream()
                .flatMap(fragment -> fragment.files().join().stream())
                .collect(Collectors.toSet());
        if (files.isEmpty()) {
            chrome.showNotification(
                    IConsoleIO.NotificationRole.INFO, "No files associated with the selection to edit.");
            return;
        }
        contextManager.addFiles(files);
    }

    @Blocking
    private void doCopyAction(List<? extends ContextFragment> selectedFragments) {
        var content = getSelectedContent(selectedFragments);
        var sel = new StringSelection(content);
        var cb = Chrome.getSystemClipboardSafe();
        if (cb == null) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Clipboard temporarily unavailable");
            return;
        }
        try {
            cb.setContents(sel, sel);
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Content copied to clipboard");
        } catch (IllegalStateException e) {
            chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Clipboard temporarily unavailable");
        }
    }

    @Blocking
    private String getSelectedContent(List<? extends ContextFragment> selectedFragments) {
        String content;
        if (selectedFragments.isEmpty()) {
            // gather entire context
            List<ChatMessage> msgs;
            try {
                msgs = CopyExternalPrompts.instance.collectMessages(contextManager);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            var combined = new StringBuilder();
            for (var m : msgs) {
                if (!(m instanceof AiMessage)) {
                    combined.append(Messages.getText(m)).append("\n\n");
                }
            }

            // Get instructions from context
            combined.append("\n<goal>\n").append(chrome.getInputText()).append("\n</goal>");
            content = combined.toString();
        } else {
            // copy only selected fragments
            var sb = new StringBuilder();
            for (var frag : selectedFragments) {
                sb.append(frag.text().join()).append("\n\n");
            }
            content = sb.toString();
        }
        return content;
    }

    private void doPasteAction() {
        assert !SwingUtilities.isEventDispatchThread();

        var clipboard = Chrome.getSystemClipboardSafe();
        if (clipboard == null) {
            chrome.toolError("Clipboard temporarily unavailable");
            return;
        }

        var contents = clipboard.getContents(null);
        if (contents == null) {
            chrome.toolError("Clipboard is empty or unavailable");
            return;
        }

        var flavors = contents.getTransferDataFlavors();
        logger.debug(
                "Clipboard flavors available: {}",
                Arrays.stream(flavors).map(DataFlavor::getMimeType).collect(Collectors.joining(", ")));

        for (var flavor : flavors) {
            try {
                if (flavor.isFlavorJavaFileListType() || flavor.getMimeType().startsWith("image/")) {
                    logger.debug("Attempting to process flavor: {}", flavor.getMimeType());
                    Object data = contents.getTransferData(flavor);
                    Image image = null;

                    switch (data) {
                        case Image image1 -> image = image1;
                        case InputStream inputStream -> {
                            try (inputStream) {
                                image = ImageIO.read(inputStream);
                            }
                        }
                        case List<?> fileList
                        when !fileList.isEmpty() -> {
                            var file = fileList.getFirst();
                            if (file instanceof File f && f.getName().matches("(?i).*(png|jpg|jpeg|gif|bmp)$")) {
                                image = ImageIO.read(f);
                            }
                        }
                        default -> {}
                    }

                    if (image != null) {
                        contextManager.addPastedImageFragment(image);
                        chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Pasted image added to context");
                        return;
                    }
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("INCR")) {
                    chrome.toolError(
                            "Unable to paste image data from Windows to Brokk running under WSL. This is a limitation of WSL. You can write the image to a file and read it that way instead.");
                    return;
                }
                logger.error("Failed to process image flavor: {}", flavor.getMimeType(), e);
            }
        }

        if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            String clipboardText;
            try {
                clipboardText = (String) contents.getTransferData(DataFlavor.stringFlavor);
                if (clipboardText.isBlank()) {
                    chrome.toolError("Clipboard text is empty");
                    return;
                }
            } catch (Exception e) {
                chrome.toolError("Failed to read clipboard text: " + e.getMessage());
                return;
            }

            clipboardText = clipboardText.trim();
            String content = clipboardText;
            boolean wasUrl = false;

            if (isUrl(clipboardText)) {
                URI uri;
                try {
                    uri = new URI(clipboardText);
                } catch (URISyntaxException e) {
                    logger.warn("Thought we had a url but we did not: " + clipboardText);
                    uri = null;
                }

                if (uri != null) {
                    if (ImageUtil.isImageUri(uri, httpClient)) {
                        try {
                            chrome.showNotification(
                                    IConsoleIO.NotificationRole.INFO, "Fetching image from " + clipboardText);
                            Image image = ImageUtil.downloadImage(uri, httpClient);
                            if (image != null) {
                                contextManager.addPastedImageFragment(image);
                                chrome.showNotification(
                                        IConsoleIO.NotificationRole.INFO, "Pasted image from URL added to context");
                                chrome.actionComplete();
                                return;
                            } else {
                                logger.warn(
                                        "URL {} identified as image by ImageUtil, but downloadImage returned null. Falling back to text.",
                                        clipboardText);
                                chrome.showNotification(
                                        IConsoleIO.NotificationRole.INFO,
                                        "Could not load image from URL. Trying to fetch as text.");
                            }
                        } catch (Exception e) {
                            logger.warn(
                                    "Failed to fetch or decode image from URL {}: {}. Falling back to text.",
                                    clipboardText,
                                    e.getMessage());
                            chrome.showNotification(
                                    IConsoleIO.NotificationRole.INFO,
                                    "Failed to load image from URL: " + e.getMessage() + ". Trying to fetch as text.");
                        }
                    }

                    try {
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO, "Fetching content from " + clipboardText);
                        content = WorkspaceTools.fetchUrlContent(uri);
                        content = HtmlToMarkdown.maybeConvertToMarkdown(content);
                        wasUrl = true;
                        chrome.actionComplete();
                    } catch (IOException e) {
                        chrome.toolError("Failed to fetch or process URL content as text: " + e.getMessage());
                        content = clipboardText;
                    }
                }
            }

            var stacktrace = StackTrace.parse(content);
            if (stacktrace != null && contextManager.addStacktraceFragment(stacktrace)) {
                return;
            }

            contextManager.addPastedTextFragment(content);

            if (stacktrace == null) {
                String message = wasUrl ? "URL content fetched and added" : "Clipboard content added as text";
                chrome.showNotification(IConsoleIO.NotificationRole.INFO, message);
            }
        } else {
            chrome.toolError("Unsupported clipboard content type");
        }
    }

    private void doDropAction(List<? extends ContextFragment> selectedFragments) {
        contextManager.dropWithHistorySemantics(selectedFragments);
    }

    @Blocking
    private void doSummarizeAction(List<? extends ContextFragment> selectedFragments) {
        if (!isAnalyzerReady()) {
            return;
        }

        HashSet<ProjectFile> selectedFiles = new HashSet<>();
        HashSet<CodeUnit> selectedClasses = new HashSet<>();

        selectedFragments.stream().flatMap(frag -> frag.files().join().stream()).forEach(selectedFiles::add);

        if (selectedFiles.isEmpty()) {
            chrome.toolError("No files or classes identified for summarization in the selection.");
            return;
        }

        boolean success = contextManager.addSummaries(selectedFiles, selectedClasses);
        if (!success) {
            chrome.toolError("No summarizable content found in the selected files or symbols.");
            return;
        }

        if (selectedFragments.size() == 1) {
            contextManager.drop(List.of(selectedFragments.get(0)));
            return;
        }

        var editFragmentsToRemove =
                selectedFragments.stream().filter(f -> f.getType().isEditable()).toList();

        if (!editFragmentsToRemove.isEmpty()) {
            contextManager.dropWithHistorySemantics(editFragmentsToRemove);
        }
    }

    @Blocking
    private void doRunTestsAction(List<? extends ContextFragment> selectedFragments) throws InterruptedException {
        List<CompletableFuture<Set<ProjectFile>>> fileFutures =
                selectedFragments.stream().map(f -> f.files().future()).toList();

        Set<ProjectFile> testFiles = new HashSet<>();

        if (!fileFutures.isEmpty()) {
            CompletableFuture<Void> all = CompletableFuture.allOf(fileFutures.toArray(new CompletableFuture[0]));
            try {
                all.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception ex) {
                logger.warn("Error awaiting fragment files for test execution", ex);
            }

            testFiles.addAll(fileFutures.stream()
                    .flatMap(cf -> cf.getNow(Set.of()).stream())
                    .filter(ContextManager::isTestFile)
                    .collect(Collectors.toSet()));
        }

        if (testFiles.isEmpty() && !selectedFragments.isEmpty()) {
            chrome.toolError("No test files found in the selection to run.");
            return;
        }

        if (testFiles.isEmpty()) {
            chrome.toolError("No test files specified to run.");
            return;
        }
        chrome.runTests(testFiles);
    }

    public void findSymbolUsageAsync() {
        if (!isAnalyzerReady()) {
            return;
        }

        contextManager.submitContextTask(() -> {
            try {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                if (analyzer.isEmpty()) {
                    chrome.toolError("Code Intelligence is empty; nothing to add");
                    return;
                }

                var selection = showSymbolSelectionDialog("Select Symbol", CodeUnitType.ALL);
                if (selection != null
                        && selection.symbol() != null
                        && !selection.symbol().isBlank()) {
                    contextManager.usageForIdentifier(selection.symbol(), selection.includeTestFiles());
                } else {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No symbol selected.");
                }
            } catch (CancellationException cex) {
                chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Symbol selection canceled.");
            }
        });
    }

    public void findMethodCallersAsync() {
        if (!isAnalyzerReady()) {
            return;
        }

        contextManager.submitContextTask(() -> {
            try {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                if (analyzer.isEmpty()) {
                    chrome.toolError("Code Intelligence is empty; nothing to add");
                    return;
                }

                var dialog = showCallGraphDialog("Select Method", true);
                if (dialog == null || !dialog.isConfirmed()) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No method selected.");
                } else {
                    var selectedMethod = dialog.getSelectedMethod();
                    var callGraph = dialog.getCallGraph();
                    if (selectedMethod != null && callGraph != null) {
                        contextManager.addCallersForMethod(selectedMethod, dialog.getDepth(), callGraph);
                    } else {
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO, "Method selection incomplete or cancelled.");
                    }
                }
            } catch (CancellationException cex) {
                chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Method selection canceled.");
            }
        });
    }

    public void findMethodCalleesAsync() {
        if (!isAnalyzerReady()) {
            return;
        }

        contextManager.submitContextTask(() -> {
            try {
                var analyzer = contextManager.getAnalyzerUninterrupted();
                if (analyzer.isEmpty()) {
                    chrome.toolError("Code Intelligence is empty; nothing to add");
                    return;
                }

                var dialog = showCallGraphDialog("Select Method for Callees", false);
                if (dialog == null || !dialog.isConfirmed()) {
                    chrome.showNotification(IConsoleIO.NotificationRole.INFO, "No method selected.");
                } else {
                    var selectedMethod = dialog.getSelectedMethod();
                    var callGraph = dialog.getCallGraph();
                    if (selectedMethod != null && callGraph != null) {
                        contextManager.calleesForMethod(selectedMethod, dialog.getDepth(), callGraph);
                    } else {
                        chrome.showNotification(
                                IConsoleIO.NotificationRole.INFO, "Method selection incomplete or cancelled.");
                    }
                }
            } catch (CancellationException cex) {
                chrome.showNotification(IConsoleIO.NotificationRole.INFO, "Method selection canceled.");
            }
        });
    }

    private @Nullable SymbolSelectionDialog.SymbolSelection showSymbolSelectionDialog(
            String title, Set<CodeUnitType> typeFilter) {
        var analyzer = contextManager.getAnalyzerUninterrupted();
        var dialogRef = new AtomicReference<SymbolSelectionDialog>();
        SwingUtil.runOnEdt(() -> {
            var dialog = new SymbolSelectionDialog(chrome.getFrame(), analyzer, title, typeFilter);
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), dialog.getHeight());
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });
        var dialog = castNonNull(dialogRef.get());
        return dialog.isConfirmed() ? dialog.getSelection() : null;
    }

    private @Nullable CallGraphDialog showCallGraphDialog(String title, boolean isCallerGraph) {
        var analyzer = contextManager.getAnalyzerUninterrupted();
        var dialogRef = new AtomicReference<CallGraphDialog>();
        SwingUtil.runOnEdt(() -> {
            var dialog = new CallGraphDialog(chrome.getFrame(), analyzer, title, isCallerGraph);
            dialog.setSize((int) (chrome.getFrame().getWidth() * 0.9), dialog.getHeight());
            dialog.setLocationRelativeTo(chrome.getFrame());
            dialog.setVisible(true);
            dialogRef.set(dialog);
        });

        var dialog = castNonNull(dialogRef.get());
        return dialog.isConfirmed() ? dialog : null;
    }

    private boolean isAnalyzerReady() {
        if (!contextManager.getAnalyzerWrapper().isReady()) {
            chrome.systemNotify(
                    AnalyzerWrapper.ANALYZER_BUSY_MESSAGE,
                    AnalyzerWrapper.ANALYZER_BUSY_TITLE,
                    JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        return true;
    }

    private boolean hasGit() {
        return contextManager.getProject().hasGit();
    }

    private void addEditAndSummarizeActions(List<ContextFragment> fragments, List<Action> actions) {
        boolean hasFiles = hasFiles(fragments);
        boolean allTracked = hasFiles && allTrackedProjectFiles(fragments);

        if (!hasFiles) {
            actions.add(WorkspaceAction.EDIT_ALL_REFS.createDisabledAction(
                    "No files associated with the selection to edit."));
            actions.add(WorkspaceAction.SUMMARIZE_ALL_REFS.createDisabledAction(
                    "No files associated with the selection to summarize."));
        } else if (!allTracked) {
            actions.add(WorkspaceAction.EDIT_ALL_REFS.createDisabledAction(
                    "Cannot edit because selection includes untracked or external files."));
            actions.add(WorkspaceAction.SUMMARIZE_ALL_REFS.createFragmentsAction(this, fragments));
        } else {
            actions.add(WorkspaceAction.EDIT_ALL_REFS.createFragmentsAction(this, fragments));
            actions.add(WorkspaceAction.SUMMARIZE_ALL_REFS.createFragmentsAction(this, fragments));
        }
    }

    private boolean isWorkspaceEditable() {
        Context selectedContext = contextManager.selectedContext();
        return selectedContext != null && selectedContext.equals(contextManager.liveContext());
    }

    private boolean isOnLatestContext() {
        return isWorkspaceEditable();
    }

    @Blocking
    private boolean hasFiles(List<ContextFragment> fragments) {
        return fragments.stream()
                .flatMap(frag -> frag.files().join().stream())
                .findAny()
                .isPresent();
    }

    @Blocking
    private boolean allTrackedProjectFiles(List<ContextFragment> fragments) {
        var project = contextManager.getProject();
        var allFiles =
                fragments.stream().flatMap(frag -> frag.files().join().stream()).collect(Collectors.toSet());

        var trackedFiles = project.getRepo().getTrackedFiles();
        return !allFiles.isEmpty() && allFiles.stream().allMatch(pf -> pf.exists() && trackedFiles.contains(pf));
    }

    private boolean isUrl(String text) {
        return text.matches("^https?://\\S+$");
    }
}
