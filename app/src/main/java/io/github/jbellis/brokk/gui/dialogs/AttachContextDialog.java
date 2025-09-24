package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.SkeletonProvider;
import io.github.jbellis.brokk.analyzer.SourceCodeProvider;
import io.github.jbellis.brokk.analyzer.UsagesProvider;
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
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
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
 * AttachContextDialog (ACD) - Segmented control: Files | Classes | Methods | Usages, placed above the search bar -
 * Single text input with autocomplete and a light-grey "Search" overlay hint - Checkbox "Summarize" placed below the
 * search bar - Returns a Result(fragment, summarize) - Enter confirms current input; Escape cancels. No OK/Cancel
 * buttons. - If analyzer is not ready: disable all segments except Files. - Segments gated by analyzer capabilities
 * when ready: SkeletonProvider / SourceCodeProvider / UsagesProvider. - Registers an AnalyzerCallback to update gating
 * as analyzer state changes.
 */
public class AttachContextDialog extends JDialog {

    public enum TabType {
        FILES,
        CLASSES,
        METHODS,
        USAGES
    }

    public record Result(ContextFragment fragment, boolean summarize) {}

    private final ContextManager cm;

    // Segmented control
    private final JPanel tabBar = new JPanel();
    private final JToggleButton filesBtn = new JToggleButton("Files");
    private final JToggleButton classesBtn = new JToggleButton("Classes");
    private final JToggleButton methodsBtn = new JToggleButton("Methods");
    private final JToggleButton usagesBtn = new JToggleButton("Usages");
    private final ButtonGroup tabGroup = new ButtonGroup();

    private final JTextField searchField = new JTextField(30);
    private final JCheckBox summarizeCheck = new JCheckBox("Summarize");
    private final JLabel hint = new JLabel("Use glob patterns (e.g., src/**/*.java). Press Enter to attach.");
    private final OverlayPanel searchOverlay;
    private final ClosingAutoCompletion ac;
    private final String hotkeyModifierString;

    // Providers bound to the single text field
    private final FilesProvider filesProvider = new FilesProvider();
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

        // Segmented control (replaces JTabbedPane)
        tabBar.setLayout(new BoxLayout(tabBar, BoxLayout.X_AXIS));
        tabBar.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        tabBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        tabGroup.add(filesBtn);
        tabGroup.add(classesBtn);
        tabGroup.add(methodsBtn);
        tabGroup.add(usagesBtn);

        // Default selection: Files
        filesBtn.setSelected(true);

        // Wire up selection changes
        var tabListener = (java.awt.event.ActionListener) e -> onTabChanged();
        filesBtn.addActionListener(tabListener);
        classesBtn.addActionListener(tabListener);
        methodsBtn.addActionListener(tabListener);
        usagesBtn.addActionListener(tabListener);

        tabBar.add(filesBtn);
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
        searchField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                searchOverlay.hideOverlay();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                if (searchField.getText().isEmpty()) searchOverlay.showOverlay();
            }
        });

        var layered = searchOverlay.createLayeredPane(searchField);
        inputPanel.add(layered, BorderLayout.CENTER);

        hint.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        inputPanel.add(hint, BorderLayout.SOUTH);

        // Summarize checkbox below the search bar
        summarizeCheck.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        summarizeCheck.setMargin(new Insets(0, 0, 0, 0));

        // Stack segmented control (top), search+summarize in a single padded panel
        var top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        var searchAndSummarize = new JPanel(new BorderLayout());
        searchAndSummarize.setBorder(BorderFactory.createEmptyBorder(0, Constants.H_GAP, 0, Constants.H_GAP));
        searchAndSummarize.add(inputPanel, BorderLayout.NORTH);

        var summarizePanel = new JPanel(new BorderLayout());
        summarizePanel.add(summarizeCheck, BorderLayout.WEST);
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
                        javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Hotkeys for tabs (1-4) - use platform menu shortcut (Cmd on macOS, Ctrl elsewhere)
        var menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getRootPane()
                .registerKeyboardAction(
                        ev -> filesBtn.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_1, menuMask),
                        javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane()
                .registerKeyboardAction(
                        ev -> classesBtn.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_2, menuMask),
                        javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane()
                .registerKeyboardAction(
                        ev -> methodsBtn.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_3, menuMask),
                        javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane()
                .registerKeyboardAction(
                        ev -> usagesBtn.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_4, menuMask),
                        javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Hotkey for summarize checkbox
        getRootPane()
                .registerKeyboardAction(
                        ev -> summarizeCheck.doClick(),
                        KeyStroke.getKeyStroke(KeyEvent.VK_I, menuMask),
                        javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Register analyzer callback to manage gating lifecycle
        registerAnalyzerCallback();

        // Initial gating + apply selected tab behavior
        gateTabs();
        onTabChanged();

        setPreferredSize(new Dimension(700, 160));
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
        if (classesBtn.isSelected()) return TabType.CLASSES;
        if (methodsBtn.isSelected()) return TabType.METHODS;
        if (usagesBtn.isSelected()) return TabType.USAGES;
        return TabType.FILES;
    }

    private void onTabChanged() {
        DefaultCompletionProvider p =
                switch (getActiveTab()) {
                    case FILES -> filesProvider;
                    case CLASSES -> classesProvider;
                    case METHODS -> methodsProvider;
                    case USAGES -> usagesProvider;
                };
        p.setAutoActivationRules(true, "._-$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
        ac.setCompletionProvider(p);

        // Refresh popup sizing on tab change
        AutoCompleteUtil.sizePopupWindows(ac, searchField, List.of());

        // Update hint text for each tab
        switch (getActiveTab()) {
            case FILES -> hint.setText("Use glob patterns (e.g., src/**/*.java). Press Enter to attach.");
            case CLASSES -> hint.setText("Type the class name to attach. Press Enter to attach.");
            case METHODS -> hint.setText("Type the method name to attach. Press Enter to attach.");
            case USAGES -> hint.setText("Type a symbol to attach usages. Press Enter to attach.");
        }

        searchField.requestFocusInWindow();
    }

    private void gateTabs() {
        boolean analyzerReady = cm.getAnalyzerWrapper().isReady();

        // Manage summarize checkbox
        summarizeCheck.setEnabled(analyzerReady);
        summarizeCheck.setToolTipText(
                analyzerReady ? hotkeyModifierString + "-I" : "Summarize" + ANALYZER_NOT_READY_TOOLTIP);

        // Files is always enabled
        filesBtn.setEnabled(true);
        filesBtn.setToolTipText(hotkeyModifierString + "-1");

        if (!analyzerReady) {
            classesBtn.setEnabled(false);
            methodsBtn.setEnabled(false);
            usagesBtn.setEnabled(false);

            classesBtn.setToolTipText("Classes" + ANALYZER_NOT_READY_TOOLTIP);
            methodsBtn.setToolTipText("Methods" + ANALYZER_NOT_READY_TOOLTIP);
            usagesBtn.setToolTipText("Usages" + ANALYZER_NOT_READY_TOOLTIP);

            // Ensure a valid selection when gating
            if (!filesBtn.isSelected()) {
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
        boolean hasUsages =
                analyzer != null && analyzer.as(UsagesProvider.class).isPresent();

        // Classes segment
        boolean classesEnabled = hasSkeleton || hasSource;
        classesBtn.setEnabled(classesEnabled);
        classesBtn.setToolTipText(
                classesEnabled ? hotkeyModifierString + "-2" : "Classes" + ANALYZER_NOT_READY_TOOLTIP);

        // Methods segment
        boolean methodsEnabled = hasSource;
        methodsBtn.setEnabled(methodsEnabled);
        methodsBtn.setToolTipText(
                methodsEnabled ? hotkeyModifierString + "-3" : "Methods" + ANALYZER_NOT_READY_TOOLTIP);

        // Usages segment
        boolean usagesEnabled = hasUsages;
        usagesBtn.setEnabled(usagesEnabled);
        usagesBtn.setToolTipText(usagesEnabled ? hotkeyModifierString + "-4" : "Usages" + ANALYZER_NOT_READY_TOOLTIP);

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
            case CLASSES -> confirmClass(text);
            case METHODS -> confirmMethod(text);
            case USAGES -> confirmUsage(text);
        }
    }

    private void confirmFile(String input) {
        var tracked = cm.getProject().getRepo().getTrackedFiles();
        ProjectFile chosen = cm.toFile(input);
        if (!tracked.contains(chosen)) {
            selection = null;
            dispose();
            return;
        }

        var frag = new ContextFragment.ProjectPathFragment(chosen, cm);
        selection = new Result(frag, summarizeCheck.isSelected());
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
        selection = new Result(frag, summarizeCheck.isSelected());
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
        // Summarize is a no-op for methods per spec; caller will treat summarize flag as no special behavior
        selection = new Result(frag, summarizeCheck.isSelected());
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
            // For Usages with Summarize: create callers call graph with depth=1
            var methodFqn = any.get().fqName();
            var frag = new ContextFragment.CallGraphFragment(cm, methodFqn, 1, false);
            selection = new Result(frag, true);
            dispose();
            return;
        }

        // Default: plain usage
        var target = any.map(CodeUnit::fqName).orElse(input);
        var frag = new ContextFragment.UsageFragment(cm, target);
        selection = new Result(frag, summarizeCheck.isSelected());
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

            Set<ProjectFile> candidates = cm.getProject().getRepo().getTrackedFiles();
            var scored = io.github.jbellis.brokk.Completions.scoreShortAndLong(
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

            List<CodeUnit> cands = io.github.jbellis.brokk.Completions.completeSymbols(pattern, analyzer);
            var filtered =
                    switch (mode) {
                        case CLASSES -> cands.stream().filter(CodeUnit::isClass).toList();
                        case METHODS ->
                            cands.stream().filter(CodeUnit::isFunction).toList();
                        case ALL -> cands;
                    };

            var scored = io.github.jbellis.brokk.Completions.scoreShortAndLong(
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
