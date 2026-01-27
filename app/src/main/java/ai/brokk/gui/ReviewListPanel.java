package ai.brokk.gui;

import ai.brokk.gui.components.NoticeBanner;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.ReviewParser.DesignFeedback;
import ai.brokk.util.ReviewParser.GuidedReview;
import ai.brokk.util.ReviewParser.KeyChanges;
import ai.brokk.util.ReviewParser.TacticalFeedback;
import ai.brokk.util.ReviewParser.TestFeedback;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class ReviewListPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(ReviewListPanel.class);

    private final JPanel headerContainer;
    private final JPanel contentPanel;
    private final NoticeBanner stalenessNotice;
    private final Consumer<Object> onItemSelected;

    public ReviewListPanel(Runnable triggerCallback, Consumer<Object> onItemSelected) {
        this.onItemSelected = onItemSelected;
        setLayout(new BorderLayout());

        headerContainer = new JPanel();
        headerContainer.setLayout(new BoxLayout(headerContainer, BoxLayout.Y_AXIS));
        headerContainer.setOpaque(false);

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(new EmptyBorder(0, 0, 10, 0));

        stalenessNotice = new NoticeBanner();
        stalenessNotice.setIcon(new FlatSVGIcon("ai/brokk/gui/icons/warning.svg", 16, 16));

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBorder(null);

        headerContainer.add(stalenessNotice);

        add(headerContainer, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    public void addHeaderControl(Component component) {
        headerContainer.add(component, 0); // Add at the very top
        headerContainer.revalidate();
    }

    public void setStalenessNotice(@Nullable String message) {
        stalenessNotice.setMessage(message);
        revalidate();
        repaint();
    }

    public void setBusy(boolean busy) {}

    public void clearSelection() {
        for (Component c : contentPanel.getComponents()) {
            if (c instanceof JLabel label) {
                label.setOpaque(false);
                label.setBackground(null);
                label.setForeground(UIManager.getColor("Label.foreground"));
            }
        }
        repaint();
    }

    public void displayReview(GuidedReview review) {
        contentPanel.removeAll();

        addItem("Overview", review.overview(), true);

        addSectionIfNotEmpty("Key Changes", review.keyChanges(), KeyChanges::title);
        addSectionIfNotEmpty("Design", review.designNotes(), DesignFeedback::title);
        addSectionIfNotEmpty("Tactical", review.tacticalNotes(), TacticalFeedback::title);
        addSectionIfNotEmpty("Tests", review.additionalTests(), TestFeedback::title);

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
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
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
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, item.getPreferredSize().height));

        item.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                selectItem(item, data);
            }
        });
        contentPanel.add(item);
    }

    private <T> void addSectionIfNotEmpty(String header, List<T> items, Function<T, String> titleProvider) {
        if (!items.isEmpty()) {
            addHeader(header);
            for (T item : items) {
                addItem(titleProvider.apply(item), item, false);
            }
        }
    }

    private void selectItem(JLabel item, Object data) {
        clearSelection();
        item.setOpaque(true);
        item.setBackground(UIManager.getColor("List.selectionBackground"));
        item.setForeground(UIManager.getColor("List.selectionForeground"));
        onItemSelected.accept(data);
        repaint();
    }

    public boolean isLastItemSelected() {
        Component[] components = contentPanel.getComponents();
        int currentIndex = -1;

        for (int i = 0; i < components.length; i++) {
            if (components[i] instanceof JLabel label && label.isOpaque()) {
                currentIndex = i;
                break;
            }
        }
        assert currentIndex >= 0 : "isLastItemSelected called with no item selected";

        for (int i = currentIndex + 1; i < components.length; i++) {
            if (components[i] instanceof JLabel label && label.getCursor().getType() == Cursor.HAND_CURSOR) {
                return false;
            }
        }
        return true;
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
                guiTheme.isDarkTheme() ? ThemeColors.getPanelBackground() : UIManager.getColor("Panel.background"));
        contentPanel.setBackground(getBackground());
        stalenessNotice.applyTheme(guiTheme);
    }
}
