package ai.brokk.gui;

import ai.brokk.ICodeReview.GuidedReview;
import ai.brokk.gui.CodeReviewCommon.ParsedExcerpt;
import ai.brokk.gui.CodeReviewCommon.ReviewNavigationListener;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import java.awt.BorderLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class CodeReviewPanel extends JPanel implements ThemeAware {

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
        itemExcerpts.clear();
        itemExcerpts.put(review.overview(), List.of());
        
        for (int i = 0; i < review.designNotes().size(); i++) {
            itemExcerpts.put(review.designNotes().get(i), designExcerpts.get(i));
        }
        
        for (int i = 0; i < review.tacticalNotes().size(); i++) {
            itemExcerpts.put(tacticalExcerpts.get(i), List.of(tacticalExcerpts.get(i)));
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
