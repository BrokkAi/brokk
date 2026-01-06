package ai.brokk.gui;

import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.ReviewParser.DesignFeedback;
import ai.brokk.util.ReviewParser.GuidedReview;
import ai.brokk.util.ReviewParser.ReviewFeedback;
import ai.brokk.util.ReviewParser.TacticalFeedback;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
    private final Consumer<Object> onItemSelected;

    public ReviewListPanel(Runnable triggerCallback, Consumer<Object> onItemSelected) {
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
        contentPanel.setBorder(new EmptyBorder(0, 0, 10, 0));

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
                label.setForeground(javax.swing.UIManager.getColor("Label.foreground"));
            }
        }
        repaint();
    }

    public void displayReview(GuidedReview review) {
        contentPanel.removeAll();

        addItem("Overview", review.overview(), true);

        addHeader("Design");
        for (DesignFeedback design : review.designNotes()) {
            addItem(design.title(), design, false);
        }

        addHeader("Tactical");
        for (TacticalFeedback tactical : review.tacticalNotes()) {
            addItem(tactical.title(), tactical, false);
        }

        addHeader("Tests");
        for (ReviewFeedback test : review.additionalTests()) {
            addItem(test.title(), test, false);
        }

        // Auto-select Overview
        for (Component c : contentPanel.getComponents()) {
            if (c instanceof JLabel label && "Overview".equals(label.getText())) {
                selectItem(label, review.overview());
                break;
            }
        }

        revalidate();
        repaint();
    }

    private void addHeader(String title) {
        JLabel header = new JLabel(title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setBorder(new EmptyBorder(15, 15, 5, 15));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
        contentPanel.add(header);
    }

    private void addItem(String label, Object data, boolean isHeaderStyle) {
        logger.debug("addItem: label='{}', data={}", label, data.getClass().getSimpleName());
        JLabel item = new JLabel(label);
        if (isHeaderStyle) {
            item.setFont(item.getFont().deriveFont(Font.BOLD, 12f));
            item.setBorder(new EmptyBorder(15, 15, 5, 15));
        } else {
            item.setBorder(new EmptyBorder(4, 25, 4, 15));
        }
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        item.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, item.getPreferredSize().height));

        item.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                selectItem(item, data);
            }
        });
        contentPanel.add(item);
    }

    private void selectItem(JLabel item, Object data) {
        clearSelection();
        item.setOpaque(true);
        item.setBackground(javax.swing.UIManager.getColor("List.selectionBackground"));
        item.setForeground(javax.swing.UIManager.getColor("List.selectionForeground"));
        onItemSelected.accept(data);
        repaint();
    }

    public void selectNext() {
        Component[] components = contentPanel.getComponents();
        int currentIndex = -1;

        for (int i = 0; i < components.length; i++) {
            if (components[i] instanceof JLabel label && label.isOpaque()) {
                currentIndex = i;
                break;
            }
        }

        // Search for the next clickable item (JLabel that is not a header)
        for (int i = currentIndex + 1; i < components.length; i++) {
            if (components[i] instanceof JLabel label && label.getCursor().getType() == Cursor.HAND_CURSOR) {
                // We need the data associated with this label.
                // Since addItem doesn't store data on the label, we trigger a click.
                for (var ml : label.getMouseListeners()) {
                    ml.mouseClicked(new MouseEvent(
                            label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 0, 0, 1, false));
                }
                return;
            }
        }
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
