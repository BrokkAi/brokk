package ai.brokk.gui;

import ai.brokk.ICodeReview.DesignFeedback;
import ai.brokk.ICodeReview.ParsedExcerpt;
import ai.brokk.ICodeReview.ReviewNavigationListener;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
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

    private final JPanel contentPanel;
    private final JTextArea placeholderArea;
    private final CardLayout cardLayout;
    private final List<ReviewNavigationListener> listeners = new ArrayList<>();

    public ReviewDetailPanel() {
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

    public void showItem(Object item, List<ParsedExcerpt> excerpts) {
        logger.info("showItem: item={}, excerpts={}", item.getClass().getSimpleName(), excerpts.size());
        for (int i = 0; i < excerpts.size(); i++) {
            var pe = excerpts.get(i);
            logger.debug("  excerpt[{}]: file='{}', line={}", i, pe.original().file(), pe.lineNumber());
        }

        cardLayout.show(this, CARD_CONTENT);
        contentPanel.removeAll();

        if (item instanceof String overview) {
            addMarkdownText(overview);
        } else if (item instanceof DesignFeedback design) {
            addMarkdownText("<b>" + design.title() + "</b>");
            addMarkdownText(design.description());
            if (!design.recommendation().isBlank()) {
                addMarkdownText("<b>Recommendation:</b> " + design.recommendation());
            }
        } else if (item instanceof ParsedExcerpt pe) {
            String commentary = pe.original().commentary();
            if (commentary != null && !commentary.isBlank()) {
                addMarkdownText(commentary);
            }
            addMarkdownText("<code>" + pe.original().excerpt().replace("\n", "<br>") + "</code>");
        }

        if (!excerpts.isEmpty()) {
            addExcerptsTable(excerpts);
        }

        revalidate();
        repaint();
    }

    private void addMarkdownText(String text) {
        JLabel label = new JLabel("<html>" + text.replace("\n", "<br>") + "</html>");
        label.setBorder(new EmptyBorder(5, 5, 5, 5));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.add(label);
    }

    private void addExcerptsTable(List<ParsedExcerpt> excerpts) {
        logger.debug("addExcerptsTable: adding {} excerpt links", excerpts.size());
        JPanel tablePanel = new JPanel();
        tablePanel.setLayout(new BoxLayout(tablePanel, BoxLayout.Y_AXIS));
        tablePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tablePanel.setOpaque(false);
        tablePanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        if (excerpts.size() > 1) {
            JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            navPanel.setOpaque(false);
            MaterialButton prevBtn = new MaterialButton("Prev");
            MaterialButton nextBtn = new MaterialButton("Next");

            AtomicInteger currentIndex = new AtomicInteger(0);
            prevBtn.addActionListener(e -> {
                currentIndex.set((currentIndex.get() - 1 + excerpts.size()) % excerpts.size());
                notifyNavigate(excerpts.get(currentIndex.get()));
            });
            nextBtn.addActionListener(e -> {
                currentIndex.set((currentIndex.get() + 1) % excerpts.size());
                notifyNavigate(excerpts.get(currentIndex.get()));
            });

            navPanel.add(prevBtn);
            navPanel.add(nextBtn);
            tablePanel.add(navPanel);
        }

        for (ParsedExcerpt pe : excerpts) {
            String filePath = pe.original().file();
            String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
            String sideSuffix = (pe.side() == ai.brokk.ICodeReview.DiffSide.OLD) ? " (old)" : "";
            String labelText = String.format("%s:%d%s", fileName, pe.lineNumber(), sideSuffix);
            JLabel label = new JLabel("<html><a href='#'>" + labelText + "</a></html>");
            label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            label.setBorder(new EmptyBorder(2, 5, 2, 5));
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    notifyNavigate(pe);
                }
            });
            tablePanel.add(label);
        }
        contentPanel.add(tablePanel);
    }

    private void notifyNavigate(ParsedExcerpt pe) {
        logger.debug(
                "notifyNavigate: file='{}', line={}, listeners={}",
                pe.original().file(),
                pe.lineNumber(),
                listeners.size());
        for (ReviewNavigationListener l : listeners) {
            l.onNavigate(pe);
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
