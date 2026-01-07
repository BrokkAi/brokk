package ai.brokk.gui;

import ai.brokk.ICodeReview.ReviewNavigationListener;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.util.ReviewParser.CodeExcerpt;
import ai.brokk.util.ReviewParser.GuidedReview;
import java.awt.BorderLayout;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class CodeReviewPanel extends JPanel implements ThemeAware {
    private final ReviewListPanel listPanel;
    private final ReviewDetailPanel detailPanel;
    private final Map<Object, List<CodeExcerpt>> itemExcerpts = new HashMap<>();
    private final List<ReviewNavigationListener> navigationListeners = new ArrayList<>();

    public CodeReviewPanel(Runnable triggerCallback, ai.brokk.IContextManager contextManager) {
        setLayout(new BorderLayout());

        listPanel = new ReviewListPanel(triggerCallback, this::handleItemSelected);
        detailPanel = new ReviewDetailPanel(contextManager, this::selectNext);
    }

    public ReviewListPanel getListPanel() {
        return listPanel;
    }

    public ReviewDetailPanel getDetailPanel() {
        return detailPanel;
    }


    public void selectNext() {
        listPanel.selectNext();
    }

    private void handleItemSelected(Object item) {
        List<CodeExcerpt> excerpts = itemExcerpts.getOrDefault(item, List.of());
        detailPanel.showItem(item, excerpts);

        if (!excerpts.isEmpty()) {
            CodeExcerpt first = excerpts.getFirst();
            for (ReviewNavigationListener listener : navigationListeners) {
                listener.onNavigate(first);
            }
        }
    }

    public void addReviewNavigationListener(ReviewNavigationListener listener) {
        detailPanel.addReviewNavigationListener(listener);
        navigationListeners.add(listener);
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

    public void displayReview(GuidedReview review) {
        itemExcerpts.clear();
        itemExcerpts.put(review.overview(), List.of());

        for (var design : review.designNotes()) {
            itemExcerpts.put(design, design.excerpts());
        }

        for (var tactical : review.tacticalNotes()) {
            itemExcerpts.put(tactical, List.of(tactical.excerpt()));
        }

        listPanel.displayReview(review);
        handleItemSelected(review.overview());
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        listPanel.applyTheme(guiTheme);
        detailPanel.applyTheme(guiTheme);
    }
}
