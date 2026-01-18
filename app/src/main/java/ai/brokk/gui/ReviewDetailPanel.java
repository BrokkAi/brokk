package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.ICodeReview.ReviewNavigationListener;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.MaterialChip;
import ai.brokk.gui.components.SplitButton;
import ai.brokk.gui.dialogs.AskHumanDialog;
import ai.brokk.gui.mop.MarkdownOutputPanel;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.project.MainProject;
import ai.brokk.tasks.TaskList;
import ai.brokk.util.ReviewParser;
import ai.brokk.util.ReviewParser.CodeExcerpt;
import ai.brokk.util.ReviewParser.DesignFeedback;
import ai.brokk.util.ReviewParser.KeyChanges;
import ai.brokk.util.ReviewParser.TacticalFeedback;
import ai.brokk.util.ReviewParser.TestFeedback;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.swing.Box;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class ReviewDetailPanel extends JPanel implements ThemeAware {
    private static final String CARD_PLACEHOLDER = "placeholder";
    private static final String CARD_CONTENT = "content";

    private final ContextManager contextManager;
    private final Runnable onNext;

    private final MarkdownOutputPanel markdownPanel;

    private final JPanel excerptsPanel;
    private final JPanel buttonPanel;

    private final JTextArea placeholderArea;
    private final CardLayout cardLayout;

    private final List<ReviewNavigationListener> listeners = new ArrayList<>();
    private final List<String> markdownChunks = new ArrayList<>();

    public ReviewDetailPanel(ContextManager contextManager, Runnable onNext) {
        this.contextManager = contextManager;
        this.onNext = onNext;

        cardLayout = new CardLayout();
        setLayout(cardLayout);

        placeholderArea = new JTextArea("Click Guided Review to get started");
        placeholderArea.setEditable(false);
        placeholderArea.setFocusable(false);
        placeholderArea.setOpaque(false);
        placeholderArea.setLineWrap(true);
        placeholderArea.setWrapStyleWord(true);
        placeholderArea.setFont(placeholderArea.getFont().deriveFont(Font.ITALIC, 14f));
        placeholderArea.setBorder(new EmptyBorder(40, 40, 40, 40));

        markdownPanel = new MarkdownOutputPanel();
        markdownPanel.updateTheme(MainProject.getTheme());

        excerptsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        excerptsPanel.setOpaque(false);
        excerptsPanel.setBorder(new EmptyBorder(10, 10, 0, 10));
        excerptsPanel.setVisible(false);

        buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5));
        buttonPanel.setOpaque(false);
        buttonPanel.setBorder(new EmptyBorder(0, 18, 10, 10));
        buttonPanel.setVisible(false);

        var contentCard = new JPanel(new BorderLayout());
        contentCard.setOpaque(true);
        contentCard.add(excerptsPanel, BorderLayout.NORTH);
        contentCard.add(markdownPanel, BorderLayout.CENTER);
        contentCard.add(buttonPanel, BorderLayout.SOUTH);

        add(placeholderArea, CARD_PLACEHOLDER);
        add(contentCard, CARD_CONTENT);

        showPlaceholder();
    }

    public void showPlaceholder() {
        cardLayout.show(this, CARD_PLACEHOLDER);
    }

    public void setBusy(boolean busy) {
        if (busy) {
            placeholderArea.setText("Generating review...");
        }
    }

    public void addReviewNavigationListener(ReviewNavigationListener listener) {
        listeners.add(listener);
    }

    public void showItem(Object item, List<CodeExcerpt> excerpts, boolean isLast) {
        cardLayout.show(this, CARD_CONTENT);
        clearContent();

        switch (item) {
            case String text -> {
                // This is the Overview
                markdownChunks.add(text);

                if (!isLast) {
                    buttonPanel.removeAll();
                    buttonPanel.setVisible(true);

                    var nextBtn = new MaterialButton("Next");
                    nextBtn.addActionListener(e -> onNext.run());
                    buttonPanel.add(nextBtn);
                }
            }
            case KeyChanges change -> {
                markdownChunks.add("### " + change.title());
                markdownChunks.add(change.description());
                if (!excerpts.isEmpty()) {
                    addExcerptsTable(excerpts);
                }
                addNavigationButtons(isLast);
            }
            case DesignFeedback design -> {
                markdownChunks.add("### " + design.title());
                markdownChunks.add(design.description());
                if (!excerpts.isEmpty()) {
                    addExcerptsTable(excerpts);
                }
                if (!design.recommendation().isBlank()) {
                    markdownChunks.add("**Recommendation:**\n" + design.recommendation());
                    String combinedText = design.description() + "\n\n" + design.recommendation();
                    addRecommendationButtons(design.title(), combinedText, isLast);
                }
            }
            case TacticalFeedback tactical -> {
                markdownChunks.add("### " + tactical.title());
                markdownChunks.add(tactical.description());
                if (!excerpts.isEmpty()) {
                    addExcerptsTable(excerpts);
                }
                if (!tactical.recommendation().isBlank()) {
                    markdownChunks.add("**Recommendation:**\n" + tactical.recommendation());
                    String combinedText = tactical.description() + "\n\n" + tactical.recommendation();
                    addRecommendationButtons(tactical.title(), combinedText, isLast);
                }
            }
            case TestFeedback feedback -> {
                markdownChunks.add("### " + feedback.title());
                if (!feedback.recommendation().isBlank()) {
                    markdownChunks.add("**Recommendation:**\n" + feedback.recommendation());
                    addRecommendationButtons(feedback.title(), feedback.recommendation(), isLast);
                } else {
                    addNavigationButtons(isLast);
                }
            }
            default -> throw new IllegalArgumentException("Unknown item type: " + item.getClass());
        }

        flushContent();

        revalidate();
        repaint();
    }

    private void clearContent() {
        markdownChunks.clear();
        excerptsPanel.removeAll();
        excerptsPanel.setVisible(false);
        buttonPanel.removeAll();
        buttonPanel.setVisible(false);
        markdownPanel.clear();
    }

    private void addNavigationButtons(boolean isLast) {
        if (isLast) return;

        buttonPanel.removeAll();
        buttonPanel.setVisible(true);

        var nextBtn = new MaterialButton("Next");
        nextBtn.addActionListener(e -> onNext.run());
        buttonPanel.add(nextBtn);
    }

    private void flushContent() {
        // (Chrome isn't ready when RDP is constructed)
        markdownPanel.setContextForLookups(contextManager, (Chrome) contextManager.getIo());

        // send the Markdown
        String combined = String.join("\n\n", markdownChunks);
        markdownPanel.setStaticDocument(combined);
    }

    private void addRecommendationButtons(String title, String recommendation, boolean isLast) {
        buttonPanel.removeAll();
        buttonPanel.setVisible(true);

        SplitButton splitBtn = new SplitButton("Enqueue Task");
        splitBtn.addActionListener(e -> {
            enqueueTask(title, recommendation);
            onNext.run();
        });

        splitBtn.setMenuSupplier(() -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem editItem = new JMenuItem("Edit + Enqueue");
            editItem.addActionListener(e -> {
                contextManager.getBackgroundTasks().submit(() -> {
                    String edited = AskHumanDialog.showEditDialog(
                            (Chrome) contextManager.getIo(), "Edit Recommendation", recommendation);
                    if (edited != null && !edited.isBlank()) {
                        SwingUtilities.invokeLater(() -> {
                            enqueueTask(title, edited);
                            onNext.run();
                        });
                    }
                });
            });
            menu.add(editItem);
            return menu;
        });

        buttonPanel.add(splitBtn);

        var copyBtn = new MaterialButton("Copy Markdown");
        copyBtn.addActionListener(e -> {
            String combined = String.join("\n\n", markdownChunks);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(combined), null);
            copyBtn.setText("Copied");
            Timer timer = new Timer(1000, evt -> copyBtn.setText("Copy Markdown"));
            timer.setRepeats(false);
            timer.start();
        });
        buttonPanel.add(Box.createHorizontalStrut(10));
        buttonPanel.add(copyBtn);

        if (!isLast) {
            var nextBtn = new MaterialButton("Next");
            nextBtn.addActionListener(e -> onNext.run());
            buttonPanel.add(Box.createHorizontalStrut(10));
            buttonPanel.add(nextBtn);
        }
    }

    private void enqueueTask(String title, String text) {
        var currentData = contextManager.liveContext().getTaskListDataOrEmpty();
        var currentTasks = currentData.tasks();

        if (currentTasks.stream().anyMatch(t -> text.equals(t.text()))) {
            return;
        }

        var newTasks = Stream.concat(currentTasks.stream(), Stream.of(new TaskList.TaskItem(title, text, false)))
                .toList();
        contextManager.setTaskListAsync(new TaskList.TaskListData(newTasks));
    }

    private void addExcerptsTable(List<CodeExcerpt> excerpts) {
        excerptsPanel.removeAll();
        excerptsPanel.setVisible(true);

        List<MaterialChip> chips = new ArrayList<>();
        AtomicInteger currentIndex = new AtomicInteger(0);

        Runnable updateSelection = () -> {
            for (int i = 0; i < chips.size(); i++) {
                chips.get(i).setSelected(i == currentIndex.get());
                chips.get(i).setAlpha(i == currentIndex.get() ? 1.0f : 0.7f);
            }
        };

        boolean isDark = UIManager.getBoolean("laf.dark");

        for (int i = 0; i < excerpts.size(); i++) {
            CodeExcerpt ce = excerpts.get(i);
            int idx = i;

            String labelText;
            if (ce.codeUnit() == null) {
                String fileName = ce.file().getRelPath().getFileName().toString();
                labelText = String.format("%s:%d", fileName, ce.line());
            } else {
                labelText = ce.codeUnit().shortName();
            }
            String sideSuffix = (ce.side() == ReviewParser.DiffSide.OLD) ? " (old)" : "";
            labelText = labelText + sideSuffix;

            MaterialChip chip = new MaterialChip(labelText);
            chip.setChipColors(
                    ChipColorUtils.getBackgroundColor(ChipColorUtils.ChipKind.OTHER, isDark),
                    ChipColorUtils.getForegroundColor(ChipColorUtils.ChipKind.OTHER, isDark),
                    ChipColorUtils.getBorderColor(ChipColorUtils.ChipKind.OTHER, isDark));

            chip.addChipClickListener(() -> {
                currentIndex.set(idx);
                updateSelection.run();
                notifyNavigate(ce);
            });

            chips.add(chip);
            excerptsPanel.add(chip);
        }

        updateSelection.run();
    }

    private void notifyNavigate(CodeExcerpt ce) {
        for (ReviewNavigationListener l : listeners) {
            l.onNavigate(ce);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension pref = super.getPreferredSize();
        var parent = getParent();
        if (parent instanceof JSplitPane split) {
            return new Dimension(pref.width, (int) (split.getHeight() * 0.4));
        }
        return pref;
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, 0);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        setBackground(
                guiTheme.isDarkTheme() ? ThemeColors.getPanelBackground() : UIManager.getColor("Panel.background"));

        markdownPanel.applyTheme(guiTheme);

        var isDark = UIManager.getBoolean("laf.dark");
        placeholderArea.setForeground(UIManager.getColor("Label.disabledForeground"));

        for (var c : excerptsPanel.getComponents()) {
            if (c instanceof MaterialChip chip) {
                chip.setChipColors(
                        ChipColorUtils.getBackgroundColor(ChipColorUtils.ChipKind.OTHER, isDark),
                        ChipColorUtils.getForegroundColor(ChipColorUtils.ChipKind.OTHER, isDark),
                        ChipColorUtils.getBorderColor(ChipColorUtils.ChipKind.OTHER, isDark));
            }
        }

        if (!markdownChunks.isEmpty()) {
            flushContent();
        }
    }
}
