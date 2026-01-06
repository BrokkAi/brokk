package ai.brokk.gui;

import ai.brokk.ICodeReview.GuidedReview;
import ai.brokk.ICodeReview.ParsedExcerpt;
import ai.brokk.ICodeReview.ReviewNavigationListener;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.BorderLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class CodeReviewPanel extends JPanel implements ThemeAware {
    private static final Logger logger = LogManager.getLogger(CodeReviewPanel.class);

    private final ReviewListPanel listPanel;
    private final ReviewDetailPanel detailPanel;
    private final Map<Object, List<ParsedExcerpt>> itemExcerpts = new HashMap<>();

    public CodeReviewPanel(Runnable triggerCallback) {
        setLayout(new BorderLayout());

        listPanel = new ReviewListPanel(triggerCallback, this::handleItemSelected);
        detailPanel = new ReviewDetailPanel();
    }

    public ReviewListPanel getListPanel() {
        return listPanel;
    }

    public ReviewDetailPanel getDetailPanel() {
        return detailPanel;
    }

    private void handleItemSelected(Object item) {
        List<ParsedExcerpt> excerpts = itemExcerpts.getOrDefault(item, List.of());
        logger.debug(
                "handleItemSelected: item={}, excerpts found={}",
                item.getClass().getSimpleName(),
                excerpts.size());
        detailPanel.showItem(item, excerpts);
    }

    public void addReviewNavigationListener(ReviewNavigationListener listener) {
        detailPanel.addReviewNavigationListener(listener);
    }

    public void setNavigationTarget(ai.brokk.difftool.ui.DiffProjectFileNavigationTarget target) {
        // detailPanel handles navigation through listeners now
    }

    public void setBusy(boolean busy) {
        listPanel.setBusy(busy);
    }

    public void clearSelection() {
        listPanel.clearSelection();
    }

    public void displayReview(
            GuidedReview review, List<List<ParsedExcerpt>> designExcerpts, List<ParsedExcerpt> tacticalExcerpts) {
        logger.info(
                "displayReview: overview={} chars, designNotes={}, tacticalExcerpts={}",
                review.overview().length(),
                review.designNotes().size(),
                tacticalExcerpts.size());

        itemExcerpts.clear();
        itemExcerpts.put(review.overview(), List.of());

        for (int i = 0; i < review.designNotes().size(); i++) {
            itemExcerpts.put(review.designNotes().get(i), designExcerpts.get(i));
        }

        for (ParsedExcerpt excerpt : tacticalExcerpts) {
            itemExcerpts.put(excerpt, List.of(excerpt));
        }

        logger.info("displayReview: itemExcerpts map size after population: {}", itemExcerpts.size());
        for (var entry : itemExcerpts.entrySet()) {
            logger.debug(
                    "  itemExcerpts key={}, excerpts={}",
                    entry.getKey().getClass().getSimpleName(),
                    entry.getValue().size());
        }

        listPanel.displayReview(review, designExcerpts, tacticalExcerpts);

        // Auto-select overview
        handleItemSelected(review.overview());
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        listPanel.applyTheme(guiTheme);
        detailPanel.applyTheme(guiTheme);
    }
}
