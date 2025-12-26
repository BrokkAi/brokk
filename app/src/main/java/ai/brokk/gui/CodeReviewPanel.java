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

    public interface ReviewNavigationListener {
        void onSelect(String explanation, List<CodeExcerpt> excerpts);
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

    private void triggerReviewGeneration() {
        // This would typically involve an agent call. 
        // For now, we assume the review is triggered via contextManager 
        // and updated via a separate data binding or callback.
    }

    public void displayReview(GuidedReview review) {
        contentPanel.removeAll();

        addHeader("Overview");
        addMarkdownText(review.overview());

        addHeader("Design");
        for (DesignFeedback design : review.designNotes()) {
            addClickableItem(design.description(), design.description(), design.excerpts());
        }

        addHeader("Tactical");
        for (CodeExcerpt tactical : review.tacticalNotes()) {
            addClickableItem(tactical.excerpt(), tactical.excerpt(), List.of(tactical));
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

    private void addClickableItem(String fullText, String description, List<CodeExcerpt> excerpts) {
        JLabel label = new JLabel("..."); // Placeholder
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setBorder(new EmptyBorder(2, 5, 2, 5));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        contextManager.summarize(description, 12).thenAccept(summary -> {
            SwingUtil.runOnEdt(() -> label.setText("• " + summary));
        });

        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                for (ReviewNavigationListener l : listeners) {
                    l.onSelect(fullText, excerpts);
                }
            }
        });

        contentPanel.add(label);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        setBackground(guiTheme.isDarkTheme() ? 
            ai.brokk.gui.mop.ThemeColors.getPanelBackground() : 
            javax.swing.UIManager.getColor("Panel.background"));
        contentPanel.setBackground(getBackground());
    }
}
