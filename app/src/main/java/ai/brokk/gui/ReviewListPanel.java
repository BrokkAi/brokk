package ai.brokk.gui;

import ai.brokk.ICodeReview.DesignFeedback;
import ai.brokk.ICodeReview.GuidedReview;
import ai.brokk.ICodeReview.ParsedExcerpt;
import ai.brokk.ICodeReview.TacticalFeedback;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class ReviewListPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(ReviewListPanel.class);

    private final MaterialButton generateButton;
    private final JPanel contentPanel;
    private final Runnable triggerCallback;
    private final Consumer<Object> onItemSelected;

    public ReviewListPanel(Runnable triggerCallback, Consumer<Object> onItemSelected) {
        this.triggerCallback = triggerCallback;
        this.onItemSelected = onItemSelected;
        setLayout(new BorderLayout());

        generateButton = new MaterialButton("Guided Review");
        SwingUtil.applyPrimaryButtonStyle(generateButton);
        generateButton.addActionListener(e -> triggerCallback.run());

        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        topPanel.add(generateButton, BorderLayout.CENTER);

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(new EmptyBorder(0, 15, 10, 15)); // Stylish wide margins

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    public void setBusy(boolean busy) {
        generateButton.setEnabled(!busy);
        generateButton.setText(busy ? "Generating..." : "Guided Review");
    }

    public void clearSelection() {
        for (Component c : contentPanel.getComponents()) {
            if (c instanceof JLabel label) {
                label.setOpaque(false);
                label.setBackground(null);
            }
        }
        repaint();
    }

    public void displayReview(
            GuidedReview review, List<List<ParsedExcerpt>> designExcerpts, List<ParsedExcerpt> tacticalExcerpts) {
        logger.info(
                "displayReview: overview present, designNotes={}, tacticalExcerpts={}",
                review.designNotes().size(),
                tacticalExcerpts.size());
        contentPanel.removeAll();

        addHeader("Overview");
        addItem("Overview", review.overview(), review.overview());

        addHeader("Design");
        for (int i = 0; i < review.designNotes().size(); i++) {
            DesignFeedback design = review.designNotes().get(i);
            addItem(design.title(), design, designExcerpts.get(i));
        }

        addHeader("Tactical");
        for (TacticalFeedback tactical : review.tacticalNotes()) {
            addItem(tactical.title(), tactical, tactical);
        }

        revalidate();
        repaint();
    }

    private void addHeader(String title) {
        JLabel header = new JLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setBorder(new EmptyBorder(15, 0, 5, 0));
        contentPanel.add(header);
    }

    private void addItem(String label, Object data, Object navigationContext) {
        logger.debug("addItem: label='{}', data={}", label, data.getClass().getSimpleName());
        JLabel item = new JLabel("• " + label);
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        item.setBorder(new EmptyBorder(4, 5, 4, 5));
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        item.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                clearSelection();
                item.setOpaque(true);
                item.setBackground(javax.swing.UIManager.getColor("List.selectionBackground"));
                onItemSelected.accept(data);
            }
        });
        contentPanel.add(item);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        setBackground(
                guiTheme.isDarkTheme()
                        ? ai.brokk.gui.mop.ThemeColors.getPanelBackground()
                        : javax.swing.UIManager.getColor("Panel.background"));
        contentPanel.setBackground(getBackground());
    }
}
