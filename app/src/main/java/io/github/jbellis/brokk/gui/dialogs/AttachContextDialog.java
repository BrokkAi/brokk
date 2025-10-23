package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.Completions;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.AutoCompleteUtil;
import io.github.jbellis.brokk.gui.Constants;
import io.github.jbellis.brokk.gui.components.OverlayPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.autocomplete.ShorthandCompletion;
import org.jetbrains.annotations.Nullable;

/**
 * AttachContextDialog (ACD) - Segmented control: Files | Folders | Classes | Methods | Usages - Single text input with
 * autocomplete and a light-grey "Search" overlay hint - Checkbox "Include subfolders" (Folders tab only) above
 * "Summarize" - Checkbox "Summarize" placed below - Returns a Result(fragments, summarize) where fragments is
 * Set<ProjectFile> - Enter confirms current input; Escape cancels. No OK/Cancel buttons. - If analyzer is not ready:
 * disable Classes/Methods/Usages; Files/Folders remain enabled. - Segments gated by analyzer capabilities when ready:
 * SkeletonProvider / SourceCodeProvider / UsagesProvider. - Registers an AnalyzerCallback to update gating as analyzer
 * state changes.
 */
public class AttachContextDialog extends JDialog {

    public enum TabType {
        FILES,
        FOLDERS,
        CLASSES,
        METHODS,
        USAGES
    }

    public record Result(Set<ContextFragment> fragments, boolean summarize) {}

    private final ContextManager cm;

    // Segmented control
    private final JPanel tabBar = new JPanel();
    private final JToggleButton filesBtn = new JToggleButton("Files");
    private final JToggleButton foldersBtn = new JToggleButton("Folders");
    private final JToggleButton classesBtn = new JToggleButton("Classes");
    private final JToggleButton methodsBtn = new JToggleButton("Methods");
    private final JToggleButton usagesBtn = new JToggleButton("Usages");
    private final ButtonGroup tabGroup = new ButtonGroup();

    private final JTextField searchField = new JTextField(30);
    private final JCheckBox includeSubfoldersCheck = new JCheckBox("Include subfolders");
    private final JCheckBox includeTestFilesCheck = new JCheckBox("Include tests");
    private final JCheckBox summarizeCheck = new JCheckBox("Summarize");
    private final OverlayPanel searchOverlay;
    private final ClosingAutoCompletion ac;
    private final String hotkeyModifierString;

    // Providers bound to the single text field
    private final FilesProvider filesProvider = new FilesProvider();
    private final FoldersProvider foldersProvider = new FoldersProvider();
    private final SymbolsProvider classesProvider = new SymbolsProvider(SymbolsProvider.Mode.CLASSES);
    private final SymbolsProvider methodsProvider = new SymbolsProvider(SymbolsProvider.Mode.METHODS);
    private final SymbolsProvider usagesProvider = new SymbolsProvider(SymbolsProvider.Mode.ALL);

    private @Nullable Result selection = null;

    private static final String ANALYZER_NOT_READY_TOOLTIP =
            " will be available after code intelligence is initialized.";

    private static final class ClosingAutoCompletion extends AutoCompletion {
        private final Runnable onAccepted;

        ClosingAutoCompletion(DefaultCompletionProvider provider, Runnable onAccepted) {
            super(provider);
            this.onAccepted = onAccepted;
        }

        @Override
        protected void insertCompletion(Completion c, boolean typedParamListStartChar) {
            super.insertCompletion(c, typedParamListStartChar);
            SwingUtilities.invokeLater(onAccepted);
        }
    }

    // Keep a reference to unregister later
    @SuppressWarnings("NullAway.Init")
    private ContextManager.AnalyzerCallback analyzerCallback;

    public AttachContextDialog(Frame parent, ContextManager cm) {
        super(parent, "Attach Context", true);
        this.cm = cm;
        this.hotkeyModifierString =
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() == KeyEvent.CTRL_DOWN_MASK ? "Ctrl" : "⌘";

        setLayout(new BorderLayout(8, 8));
        setResizable(false);

        // Segmented control (replaces JTabbedPane)
        tabBar.setLayout(new BoxLayout(tabBar, BoxLayout.X_AXIS));
        tabBar.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        tabBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        tabGroup.add(filesBtn);
        tabGroup.add(foldersBtn);
        tabGroup.add(classesBtn);
        tabGroup.add(methodsBtn);
        tabGroup.add(usagesBtn);

        // Default selection: Files
        filesBtn.setSelected(true);

        // Wire up selection changes
        var tabListener = (ActionListener) e -> onTabChanged();
        filesBtn.addActionListener(tabListener);
        foldersBtn.addActionListener(tabListener);
        classesBtn.addActionListener(tabListener);
        methodsBtn.addActionListener(tabListener);
        usagesBtn.addActionListener(tabListener);

        tabBar.add(filesBtn);
        tabBar.add(foldersBtn);
        tabBar.add(classesBtn);
        tabBar.add(methodsBtn);
        tabBar.add(usagesBtn);

        // Input area: search field with overlay "Search" and hint
        var inputPanel = new JPanel(new BorderLayout(8, 8));
        var overlay = new OverlayPanel(p -> searchField.requestFocusInWindow(), "");
        var overlayLabel = new JLabel("Search");
        overlayLabel.setForeground(Color.GRAY);
        overlayLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        overlay.setLayout(new BorderLayout());
        overlay.add(overlayLabel, BorderLayout.CENTER);
        searchOverlay = overlay;

        if (searchField.getText().isEmpty()) searchOverlay.showOverlay();
        else searchOverlay.hideOverlay();
        searchField.addCaretListener(e -> {
            if (searchField.getText().isEmpty() && !searchField.hasFocus()) searchOverlay.showOverlay();
            else searchOverlay.hideOverlay();
        });
        searchField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                searchOverlay.hideOverlay();
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (searchField.getText().isEmpty()) searchOverlay.showOverlay();
            }
        });

        var layered = searchOverlay.createLayeredPane(searchField);
        inputPanel.add(layered, BorderLayout.CENTER);

        // Checkboxes area: include-subfolders (Folders tab only) above summarize
        includeSubfoldersCheck.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        includeSubfoldersCheck.setMargin(new Insets(0, 0, 0, 0));
        includeSubfoldersCheck.setVisible(false); // Only visible for Folders tab

        // Include tests (Usages tab only)
        includeTestFilesCheck.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        includeTestFilesCheck.setMargin(new Insets(0, 0, 0, 0));
        includeTestFilesCheck.setSelected(true); // Default to including tests
        includeTestFilesCheck.setVisible(false); // Only visible for Usages tab

        // Summarize checkbox below the include-subfolders
        summarizeCheck.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        summarizeCheck.setMargin(new Insets(0, 0, 0, 0));

        // Stack segmented control (top), search+summarize in a single padded panel
        var top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        var searchAndSummarize = new JPanel(new BorderLayout());
        searchAndSummarize.setBorder(BorderFactory.createEmptyBorder(0, Constants.H_GAP, 0, Constants.H_GAP));
        searchAndSummarize.add(inputPanel, BorderLayout.NORTH);

        var summarizePanel = new JPanel();
        summarizePanel.setLayout(new BoxLayout(summarizePanel, BoxLayout.Y_AXIS));
        summarizePanel.add(includeSubfoldersCheck);
        summarizePanel.add(includeTestFilesCheck);
        summarizePanel.add(summarizeCheck);
        searchAndSummarize.add(summarizePanel, BorderLayout.SOUTH);

        // Ensure components stay top-aligned and don't stretch vertically
        searchAndSummarize.setAlignmentX(Component.LEFT_ALIGNMENT);

        top.add(tabBar);
        top.add(searchAndSummarize);

        add(top, BorderLayout.NORTH);

        // Prepare autocomplete: default to Files
        filesProvider.setAutoActivationRules(
                true, "._-$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        ac = new ClosingAutoCompletion(filesProvider, this::onConfirm);
        ac.setAutoActivationEnabled(true);
        ac.setAutoActivationDelay(0);
        ac.setAutoCompleteSingleChoices(false);
        ac.install(searchField);

        // Confirm on Enter (when autocomplete popup is not visible)
        searchField.addActionListener(e -> onConfirm());

        // Cancel on Escape
        getRootPane()
                .registerKeyboardAction(
                        ev -> {
                            selection = null;
                            dispose();
                        },
                        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Hotkeys for tabs (1-5) - use platform menu shortcut (Cmd on macOS, Ctrl elsewhere)
        var menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getRootPane()
                .registerKeyboardAction(
                        ev -> filesBtn.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_1, menuMask),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane()
                .registerKeyboardAction(
                        ev -> foldersBtn.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_2, menuMask),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane()
                .registerKeyboardAction(
                        ev -> classesBtn.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_3, menuMask),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane()
                .registerKeyboardAction(
                        ev -> methodsBtn.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_4, menuMask),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane()
                .registerKeyboardAction(
                        ev -> usagesBtn.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_5, menuMask),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Hotkey for summarize checkbox
        getRootPane()
                .registerKeyboardAction(
                        ev -> summarizeCheck.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_I, menuMask),
                        JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Register analyzer callback to manage gating lifecycle
        registerAnalyzerCallback();

        // Initial gating + apply selected tab behavior
        gateTabs();
        onTabChanged();

        setMinimumSize(new Dimension(700, 180));
        setPreferredSize(new Dimension(700, 180));
        pack();
        setLocationRelativeTo(parent);

        SwingUtilities.invokeLater(() -> searchField.requestFocusInWindow());

        // Ensure callback is removed on close
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                unregisterAnalyzerCallback();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                unregisterAnalyzerCallback();
            }
        });
    }

    public AttachContextDialog(Frame parent, ContextManager cm, boolean defaultSummarizeChecked) {
        this(parent, cm);
        this.summarizeCheck.setSelected(defaultSummarizeChecked);
    }

    public @Nullable Result getSelection() {
        return selection;
    }

    private TabType getActiveTab() {
        if (filesBtn.isSelected()) return TabType.FILES;
        if (foldersBtn.isSelected()) return TabType.FOLDERS;
        if (classesBtn.isSelected()) return TabType.CLASSES;
        if (methodsBtn.isSelected()) return TabType.METHODS;
        if (usagesBtn.isSelected()) return TabType.USAGES;
        return TabType.FILES;
    }

    private void onTabChanged() {
        DefaultCompletionProvider p =
                switch (getActiveTab()) {
                    case FILES -> filesProvider;
                    case FOLDERS -> foldersProvider;
                    case CLASSES -> classesProvider;
                    case METHODS -> methodsProvider;
                    case USAGES -> usagesProvider;
                };
        p.setAutoActivationRules(true, "._-$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        ac.setCompletionProvider(p);

        // Refresh popup sizing on tab change
        AutoCompleteUtil.sizePopupWindows(ac, searchField, List.of());

        // Update checkbox visibility for each tab
        includeSubfoldersCheck.setVisible(getActiveTab() == TabType.FOLDERS);
        includeTestFilesCheck.setVisible(getActiveTab() == TabType.USAGES);

        searchField.requestFocusInWindow();
    }

    private void gateTabs() {
        boolean analyzerReady = cm.getAnalyzerWrapper().isReady();

        // Manage summarize checkbox
        summarizeCheck.setEnabled(analyzerReady);
        summarizeCheck.setToolTipText(
                analyzerReady ? hotkeyModifierString + "-I" : "Summarize" + ANALYZER_NOT_READY_TOOLTIP);

        // Manage include tests checkbox (only relevant for Usages tab)
        includeTestFilesCheck.setEnabled(false);
        includeTestFilesCheck.setToolTipText("Include tests" + ANALYZER_NOT_READY_TOOLTIP);

        // Files and Folders are always enabled
        filesBtn.setEnabled(true);
        filesBtn.setToolTipText(hotkeyModifierString + "-1");
        foldersBtn.setEnabled(true);
        foldersBtn.setToolTipText(hotkeyModifierString + "-2");

        if (!analyzerReady) {
            classesBtn.setEnabled(false);
            methodsBtn.setEnabled(false);
            usagesBtn.setEnabled(false);

            classesBtn.setToolTipText("Classes" + ANALYZER_NOT_READY_TOOLTIP);
            methodsBtn.setToolTipText("Methods" + ANALYZER_NOT_READY_TOOLTIP);
            usagesBtn.setToolTipText("Usages" + ANALYZER_NOT_READY_TOOLTIP);

            // Ensure a valid selection when gating
            if (!filesBtn.isSelected() && !foldersBtn.isSelected()) {
                filesBtn.setSelected(true);
                onTabChanged();
            }
            return;
        }

        // Analyzer is ready, enable/disable based on capabilities
        IAnalyzer analyzer = cm.getAnalyzerWrapper().getNonBlocking();
        boolean hasSkeleton =
                analyzer != null && analyzer.as(SkeletonProvider.class).isPresent();
        boolean hasSource =
                analyzer != null && analyzer.as(SourceCodeProvider.class).isPresent();
        boolean hasUsages = analyzer != null;

        // Classes segment
        boolean classesEnabled = hasSkeleton || hasSource;
        classesBtn.setEnabled(classesEnabled);
        classesBtn.setToolTipText(
                classesEnabled ? hotkeyModifierString + "-3" : "Classes" + ANALYZER_NOT_READY_TOOLTIP);

        // Methods segment
        boolean methodsEnabled = hasSource;
        methodsBtn.setEnabled(methodsEnabled);
        methodsBtn.setToolTipText(
                methodsEnabled ? hotkeyModifierString + "-4" : "Methods" + ANALYZER_NOT_READY_TOOLTIP);

        // Usages segment
        boolean usagesEnabled = hasUsages;
        usagesBtn.setEnabled(usagesEnabled);
        usagesBtn.setToolTipText(usagesEnabled ? hotkeyModifierString + "-5" : "Usages" + ANALYZER_NOT_READY_TOOLTIP);

        // Include tests checkbox follows the Usages gating
        includeTestFilesCheck.setEnabled(usagesEnabled);
        includeTestFilesCheck.setToolTipText(
                usagesEnabled ? "Include tests" : "Include tests" + ANALYZER_NOT_READY_TOOLTIP);

        // Ensure the selected segment remains valid
        if ((classesBtn.isSelected() && !classesEnabled)
                || (methodsBtn.isSelected() && !methodsEnabled)
                || (usagesBtn.isSelected() && !usagesEnabled)) {
            filesBtn.setSelected(true);
            onTabChanged();
        }
    }

    private void onConfirm() {
        var text = searchField.getText().trim();
        if (text.isEmpty()) {
            selection = null;
            dispose();
            return;
        }

        switch (getActiveTab()) {
            case FILES -> confirmFile(text);
            case FOLDERS -> confirmFolder(text);
            case CLASSES -> confirmClass(text);
            case METHODS -> confirmMethod(text);
            case USAGES -> confirmUsage(text);
        }
    }

    private void confirmFile(String input) {
        ProjectFile chosen = cm.toFile(input);
        if (!cm.getProject().getAllFiles().contains(chosen)) {
            selection = null;
            dispose();
            return;
        }

        var frag = new ContextFragment.ProjectPathFragment(chosen, cm);
        selection = new Result(Set.of(frag), summarizeCheck.isSelected());
        dispose();
    }

    private void confirmFolder(String input) {
        var rel = input.replace("\\", "/");
        rel = rel.startsWith("/") ? rel.substring(1) : rel;
        rel = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;

        var includeSubfolders = includeSubfoldersCheck.isSelected();
        var relPath = Path.of(rel);

        Set<ProjectFile> all = cm.getProject().getAllFiles();
        Set<ProjectFile> selected = new LinkedHashSet<>();
        for (var pf : all) {
            Path fileRel = pf.getRelPath();
            if (includeSubfolders) {
                if (fileRel.startsWith(relPath)) {
                    selected.add(pf);
                }
            } else {
                Path parent = fileRel.getParent();
                if (Objects.equals(parent, relPath)) {
                    selected.add(pf);
                }
            }
        }

        if (selected.isEmpty()) {
            selection = null;
            dispose();
            return;
        }

        Set<ContextFragment> fragments = selected.stream()
                .map(pf -> (ContextFragment) new ContextFragment.ProjectPathFragment(pf, cm))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        selection = new Result(fragments, summarizeCheck.isSelected());
        dispose();
    }

    private void confirmClass(String input) {
        var analyzer = cm.getAnalyzerWrapper().getNonBlocking();
        if (analyzer == null) {
            selection = null;
            dispose();
            return;
        }

        Optional<CodeUnit> opt = analyzer.getDefinition(input).filter(CodeUnit::isClass);
        if (opt.isEmpty()) {
            var s = analyzer.searchDefinitions(input).stream()
                    .filter(CodeUnit::isClass)
                    .findFirst();
            opt = s;
        }
        if (opt.isEmpty()) {
            selection = null;
            dispose();
            return;
        }

        var cu = opt.get();
        var frag = new ContextFragment.CodeFragment(cm, cu);
        selection = new Result(Set.of(frag), summarizeCheck.isSelected());
        dispose();
    }

    private void confirmMethod(String input) {
        var analyzer = cm.getAnalyzerWrapper().getNonBlocking();
        if (analyzer == null) {
            selection = null;
            dispose();
            return;
        }

        Optional<CodeUnit> opt = analyzer.getDefinition(input).filter(CodeUnit::isFunction);
        if (opt.isEmpty()) {
            var s = analyzer.searchDefinitions(input).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst();
            opt = s;
        }
        if (opt.isEmpty()) {
            selection = null;
            dispose();
            return;
        }

        var cu = opt.get();
        var frag = new ContextFragment.CodeFragment(cm, cu);
        selection = new Result(Set.of(frag), summarizeCheck.isSelected());
        dispose();
    }

    private void confirmUsage(String input) {
        var analyzer = cm.getAnalyzerWrapper().getNonBlocking();
        if (analyzer == null) {
            selection = null;
            dispose();
            return;
        }

        // Find best matching symbol (class or method). Prefer method if exact.
        Optional<CodeUnit> exactMethod = analyzer.getDefinition(input).filter(CodeUnit::isFunction);
        Optional<CodeUnit> any = exactMethod.isPresent()
                ? exactMethod
                : analyzer.getDefinition(input)
                        .or(() -> analyzer.searchDefinitions(input).stream().findFirst());

        if (summarizeCheck.isSelected() && any.isPresent() && any.get().isFunction()) {
            var methodFqn = any.get().fqName();
            var frag = new ContextFragment.CallGraphFragment(cm, methodFqn, 1, false);
            selection = new Result(Set.of(frag), true);
            dispose();
            return;
        }

        var target = any.map(CodeUnit::fqName).orElse(input);
        var frag = new ContextFragment.UsageFragment(cm, target, includeTestFilesCheck.isSelected());
        selection = new Result(Set.of(frag), summarizeCheck.isSelected());
        dispose();
    }

    // ---------- Callbacks ----------

    private void registerAnalyzerCallback() {
        analyzerCallback = new ContextManager.AnalyzerCallback() {
            @Override
            public void onAnalyzerReady() {
                SwingUtilities.invokeLater(() -> gateTabs());
            }
        };
        cm.addAnalyzerCallback(analyzerCallback);
    }

    private void unregisterAnalyzerCallback() {
        cm.removeAnalyzerCallback(analyzerCallback);
    }

    // ---------- Providers ----------

    private class FilesProvider extends DefaultCompletionProvider {
        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            return comp.getText();
        }

        @Override
        public List<Completion> getCompletions(JTextComponent tc) {
            var pattern = getAlreadyEnteredText(tc).trim();
            if (pattern.isEmpty() || !cm.getProject().hasGit()) return List.of();

            Set<ProjectFile> candidates = cm.getProject().getAllFiles();
            var scored = Completions.scoreShortAndLong(
                    pattern,
                    candidates,
                    ProjectFile::getFileName,
                    pf -> pf.getRelPath().toString(),
                    pf -> 0,
                    this::create);

            AutoCompleteUtil.sizePopupWindows(ac, searchField, scored);
            return scored.stream().map(c -> (Completion) c).toList();
        }

        private ShorthandCompletion create(ProjectFile pf) {
            var path = pf.getRelPath().toString();
            return new ShorthandCompletion(this, pf.getFileName(), path, path);
        }
    }

    private class FoldersProvider extends DefaultCompletionProvider {
        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            return comp.getText();
        }

        @Override
        public List<Completion> getCompletions(JTextComponent tc) {
            var pattern = getAlreadyEnteredText(tc).trim();
            if (pattern.isEmpty() || !cm.getProject().hasGit()) return List.of();

            // Collect unique folder paths from all project files, including all ancestors up to project root.
            Set<ProjectFile> files = cm.getProject().getAllFiles();
            var folders = new LinkedHashSet<String>();
            for (var pf : files) {
                Path parent = pf.getRelPath().getParent();
                while (parent != null) {
                    String s = parent.toString().replace('\\', '/'); // normalize separators for consistency
                    folders.add(s);
                    parent = parent.getParent();
                }
            }

            var scored = Completions.scoreShortAndLong(
                    pattern,
                    folders,
                    s -> {
                        var p = Path.of(s);
                        var fn = p.getFileName();
                        return fn != null ? fn.toString() : s;
                    },
                    s -> s,
                    s -> 0,
                    s -> {
                        var p = Path.of(s);
                        var fn = p.getFileName();
                        var shortName = fn != null ? fn.toString() : s;
                        return new ShorthandCompletion(this, shortName, s, s);
                    });

            AutoCompleteUtil.sizePopupWindows(ac, searchField, scored);
            return scored.stream().map(c -> (Completion) c).toList();
        }
    }

    private class SymbolsProvider extends DefaultCompletionProvider {
        enum Mode {
            CLASSES,
            METHODS,
            ALL
        }

        private final Mode mode;

        SymbolsProvider(Mode mode) {
            this.mode = mode;
        }

        @Override
        public String getAlreadyEnteredText(JTextComponent comp) {
            var text = comp.getText();
            int caret = comp.getCaretPosition();
            if (caret < text.length()) text = text.substring(0, caret);
            return text;
        }

        @Override
        public List<Completion> getCompletions(JTextComponent tc) {
            var dialog = AttachContextDialog.this;
            var analyzer = cm.getAnalyzerWrapper().getNonBlocking();
            var pattern = getAlreadyEnteredText(tc).trim();
            if (analyzer == null || pattern.isEmpty()) return List.of();

            List<CodeUnit> cands = Completions.completeSymbols(pattern, analyzer);
            var filtered =
                    switch (mode) {
                        case CLASSES -> cands.stream().filter(CodeUnit::isClass).toList();
                        case METHODS ->
                            cands.stream().filter(CodeUnit::isFunction).toList();
                        case ALL -> cands;
                    };

            var scored = Completions.scoreShortAndLong(
                    pattern,
                    filtered,
                    CodeUnit::identifier,
                    CodeUnit::fqName,
                    cu -> 0,
                    cu -> new ShorthandCompletion(this, cu.shortName(), cu.fqName() + " ", cu.fqName()));

            AutoCompleteUtil.sizePopupWindows(dialog.ac, dialog.searchField, scored);
            return scored.stream().map(c -> (Completion) c).toList();
        }
    }
}
