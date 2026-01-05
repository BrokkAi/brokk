package ai.brokk.gui;

import ai.brokk.ContextManager;
import ai.brokk.ICodeReview;
import ai.brokk.ICodeReview.CodeExcerpt;
import ai.brokk.ICodeReview.DesignFeedback;
import ai.brokk.ICodeReview.GuidedReview;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class CodeReviewPanel extends JPanel implements ThemeAware {

    public record ParsedExcerpt(CodeExcerpt original, int lineNumber, int fileIndex) {}

    public interface ReviewNavigationListener {
        void onNavigate(ParsedExcerpt excerpt);
    }

    private final ContextManager contextManager;
    private final MaterialButton generateButton;
    private final JPanel contentPanel;
    private final List<ReviewNavigationListener> listeners = new ArrayList<>();

    public CodeReviewPanel(ContextManager contextManager) {
        this.contextManager = contextManager;
        setLayout(new BorderLayout());

        generateButton = new MaterialButton("Guided Review");
        SwingUtil.applyPrimaryButtonStyle(generateButton);
        generateButton.addActionListener(e -> triggerReviewGeneration());

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        topPanel.add(generateButton, BorderLayout.CENTER);

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(new EmptyBorder(0, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    public void addReviewNavigationListener(ReviewNavigationListener listener) {
        listeners.add(listener);
    }

    public void setBusy(boolean busy) {
        generateButton.setEnabled(!busy);
        generateButton.setText(busy ? "Generating..." : "Guided Review");
    }

    private void triggerReviewGeneration() {
        for (ReviewNavigationListener l : listeners) {
            if (l instanceof ReviewTriggerListener rtl) {
                rtl.onTriggerReview();
            }
        }
    }

    public interface ReviewTriggerListener extends ReviewNavigationListener {
        void onTriggerReview();
    }

    public void displayReview(
            GuidedReview review,
            List<List<ParsedExcerpt>> designExcerpts,
            List<ParsedExcerpt> tacticalExcerpts) {
        contentPanel.removeAll();

        addHeader("Overview");
        addMarkdownText(review.overview());

        addHeader("Design");
        for (int i = 0; i < review.designNotes().size(); i++) {
            DesignFeedback design = review.designNotes().get(i);
            List<ParsedExcerpt> parsed = designExcerpts.get(i);
            if (!parsed.isEmpty()) {
                addExpandableItem(design.description(), parsed);
            }
        }

        addHeader("Tactical");
        for (ParsedExcerpt tactical : tacticalExcerpts) {
            addExpandableItem(tactical.original().excerpt(), List.of(tactical));
        }

        addHeader("Tests");
        for (String test : review.additionalTests()) {
            addMarkdownText("- " + test);
        }

        revalidate();
        repaint();
    }

    private void addHeader(String title) {
        JLabel header = new JLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));
        header.setBorder(new EmptyBorder(15, 0, 5, 0));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(header);
    }

    private void addMarkdownText(String text) {
        JLabel label = new JLabel("<html>" + text.replace("\n", "<br>") + "</html>");
        label.setBorder(new EmptyBorder(2, 5, 2, 5));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(label);
    }

    private void addExpandableItem(String description, List<ParsedExcerpt> excerpts) {
        JPanel itemPanel = new JPanel();
        itemPanel.setLayout(new BoxLayout(itemPanel, BoxLayout.Y_AXIS));
        itemPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        itemPanel.setOpaque(false);

        JLabel summaryLabel = new JLabel("• ...");
        summaryLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        summaryLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
        summaryLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        contextManager.summarize(description, 12).thenAccept(summary -> {
            SwingUtil.runOnEdt(() -> summaryLabel.setText("• " + summary));
        });

        JPanel detailsPanel = new JPanel();
        detailsPanel.setLayout(new BoxLayout(detailsPanel, BoxLayout.Y_AXIS));
        detailsPanel.setBorder(new EmptyBorder(0, 20, 5, 0));
        detailsPanel.setOpaque(false);
        detailsPanel.setVisible(false);

        summaryLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                detailsPanel.setVisible(!detailsPanel.isVisible());
                itemPanel.revalidate();
                itemPanel.repaint();
            }
        });

        if (excerpts.size() > 1) {
            JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            navPanel.setOpaque(false);
            MaterialButton prevBtn = new MaterialButton("Prev");
            MaterialButton nextBtn = new MaterialButton("Next");
            
            final int[] currentIndex = {0};
            java.lang.Runnable updateNav = () -> {
                for (ReviewNavigationListener l : listeners) {
                    l.onNavigate(excerpts.get(currentIndex[0]));
                }
            };

            prevBtn.addActionListener(e -> {
                currentIndex[0] = (currentIndex[0] - 1 + excerpts.size()) % excerpts.size();
                updateNav.run();
            });
            nextBtn.addActionListener(e -> {
                currentIndex[0] = (currentIndex[0] + 1) % excerpts.size();
                updateNav.run();
            });

            navPanel.add(prevBtn);
            navPanel.add(nextBtn);
            detailsPanel.add(navPanel);
        }

        for (ParsedExcerpt pe : excerpts) {
            String fileName = pe.original().file().getRelPath().getFileName().toString();
            String labelText = String.format("%s:%d", fileName, pe.lineNumber());
            JLabel excerptLabel = new JLabel("<html><a href='#'>" + labelText + "</a></html>");
            excerptLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            excerptLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
            excerptLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    for (ReviewNavigationListener l : listeners) {
                        l.onNavigate(pe);
                    }
                }
            });
            detailsPanel.add(excerptLabel);
        }

        itemPanel.add(summaryLabel);
        itemPanel.add(detailsPanel);
        contentPanel.add(itemPanel);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        setBackground(guiTheme.isDarkTheme() ? 
            ai.brokk.gui.mop.ThemeColors.getPanelBackground() : 
            javax.swing.UIManager.getColor("Panel.background"));
        contentPanel.setBackground(getBackground());
    }
}
