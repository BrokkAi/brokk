package ai.brokk.gui;

import ai.brokk.IContextManager;
import ai.brokk.ICodeReview.ReviewNavigationListener;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.MaterialChip;
import ai.brokk.gui.components.SimpleHtmlPanel;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.ReviewParser;
import ai.brokk.util.ReviewParser.CodeExcerpt;
import ai.brokk.util.ReviewParser.DesignFeedback;
import ai.brokk.util.ReviewParser.TacticalFeedback;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BoxLayout;
import javax.swing.UIManager;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class ReviewDetailPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(ReviewDetailPanel.class);

    private static final String CARD_PLACEHOLDER = "placeholder";
    private static final String CARD_CONTENT = "content";

    private final IContextManager contextManager;
    private final JPanel contentPanel;
    private final JTextArea placeholderArea;
    private final CardLayout cardLayout;
    private final List<ReviewNavigationListener> listeners = new ArrayList<>();
    private final List<SimpleHtmlPanel> htmlPanels = new ArrayList<>();

    public ReviewDetailPanel(IContextManager contextManager) {
        this.contextManager = contextManager;
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

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);

        add(placeholderArea, CARD_PLACEHOLDER);
        add(scrollPane, CARD_CONTENT);

        showPlaceholder();
    }

    public void showPlaceholder() {
        cardLayout.show(this, CARD_PLACEHOLDER);
    }

    public void addReviewNavigationListener(ReviewNavigationListener listener) {
        listeners.add(listener);
    }

    public void showItem(Object item, List<CodeExcerpt> excerpts) {
        cardLayout.show(this, CARD_CONTENT);
        clearContent();

        if (item instanceof String overview) {
            addMarkdownPanel(overview);
        } else if (item instanceof DesignFeedback design) {
            addMarkdownPanel("### " + design.title());
            addMarkdownPanel(design.description());
            if (!design.recommendation().isBlank()) {
                addMarkdownPanel("**Recommendation:**\n" + design.recommendation());
            }
        } else if (item instanceof TacticalFeedback tactical) {
            addMarkdownPanel("### " + tactical.title());
            addMarkdownPanel(tactical.description());
            if (!tactical.recommendation().isBlank()) {
                addMarkdownPanel("**Recommendation:**\n" + tactical.recommendation());
            }
        } else if (item instanceof CodeExcerpt ce) {
            addMarkdownPanel("```\n" + ce.excerpt() + "\n```");
        }

        if (!excerpts.isEmpty()) {
            addExcerptsTable(excerpts);
        }

        revalidate();
        repaint();
    }

    private void addMarkdownPanel(String markdown) {
        var panel = new SimpleHtmlPanel();
        panel.setMarkdown(markdown);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        htmlPanels.add(panel);
        contentPanel.add(panel);
    }

    private void clearContent() {
        contentPanel.removeAll();
        htmlPanels.clear();
    }

    private void addExcerptsTable(List<CodeExcerpt> excerpts) {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setAlignmentX(Component.LEFT_ALIGNMENT);
        container.setOpaque(false);
        container.setBorder(new EmptyBorder(10, 0, 0, 0));

        JPanel chipPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        chipPanel.setOpaque(false);
        chipPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        List<MaterialChip> chips = new ArrayList<>();
        AtomicInteger currentIndex = new AtomicInteger(0);

        Runnable updateSelection = () -> {
            for (int i = 0; i < chips.size(); i++) {
                chips.get(i).setSelected(i == currentIndex.get());
                chips.get(i).setAlpha(i == currentIndex.get() ? 1.0f : 0.7f);
            }
        };

        for (int i = 0; i < excerpts.size(); i++) {
            CodeExcerpt ce = excerpts.get(i);
            int idx = i;

            String labelText;
            var analyzer = contextManager.getAnalyzerUninterrupted();
            var codeUnitOpt = analyzer.enclosingCodeUnit(ce.file(), ce.line(), ce.line());
            if (codeUnitOpt.isPresent()) {
                labelText = codeUnitOpt.get().shortName();
            } else {
                // Fallback to filename:line format
                String fileName = ce.file().getRelPath().getFileName().toString();
                labelText = String.format("%s:%d", fileName, ce.line());
            }
            String sideSuffix = (ce.side() == ReviewParser.DiffSide.OLD) ? " (old)" : "";
            labelText = labelText + sideSuffix;

            MaterialChip chip = new MaterialChip(labelText);
            boolean isDark = UIManager.getBoolean("laf.dark");
            chip.setChipColors(
                    ChipColorUtils.getBackgroundColor(ChipColorUtils.ChipKind.OTHER, isDark),
                    ChipColorUtils.getForegroundColor(ChipColorUtils.ChipKind.OTHER, isDark),
                    ChipColorUtils.getBorderColor(ChipColorUtils.ChipKind.OTHER, isDark)
            );

            chip.addChipClickListener(() -> {
                currentIndex.set(idx);
                updateSelection.run();
                notifyNavigate(ce);
            });

            chips.add(chip);
            chipPanel.add(chip);
        }

        updateSelection.run();

        if (excerpts.size() > 1) {
            JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            headerPanel.setOpaque(false);
            headerPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel countLabel = new JLabel(String.format("Locations (%d):", excerpts.size()));
            countLabel.setFont(countLabel.getFont().deriveFont(Font.BOLD));
            headerPanel.add(countLabel);

            container.add(headerPanel);
        }

        container.add(chipPanel);
        contentPanel.add(container);
    }

    private void notifyNavigate(CodeExcerpt ce) {
        for (ReviewNavigationListener l : listeners) {
            l.onNavigate(ce);
        }
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        setBackground(
                guiTheme.isDarkTheme()
                        ? ai.brokk.gui.mop.ThemeColors.getPanelBackground()
                        : javax.swing.UIManager.getColor("Panel.background"));
        contentPanel.setBackground(getBackground());
        placeholderArea.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
    }
}
