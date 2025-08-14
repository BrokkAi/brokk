package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import java.util.List;
import java.util.Objects;
import javax.swing.*;
import org.jetbrains.annotations.Nullable;

public class MarkdownSearchableComponent implements SearchableComponent {
    private final MarkdownOutputPanel panel;
    private SearchCompleteCallback callback = SearchCompleteCallback.NONE;

    private String currentQuery = "";
    private boolean currentCaseSensitive = false;
    private int lastTotal = 0;

    public MarkdownSearchableComponent(List<MarkdownOutputPanel> panels) {
        assert !panels.isEmpty();
        this.panel = panels.getFirst();
        panel.addSearchStateListener(state -> {
            lastTotal = state.totalMatches();
            callback.onSearchComplete(state.totalMatches(), state.currentDisplayIndex());
        });
    }

    /** Creates an adapter for a single MarkdownOutputPanel. */
    public static MarkdownSearchableComponent wrap(MarkdownOutputPanel panel) {
        return new MarkdownSearchableComponent(List.of(panel));
    }

    @Override
    public String getText() {
        return panel.getText();
    }

    @Override
    public @Nullable String getSelectedText() {
        var sel = panel.getSelectedText();
        return sel.isEmpty() ? null : sel;
    }

    @Override
    public int getCaretPosition() {
        return 0;
    }

    @Override
    public void setCaretPosition(int position) {
        // no-op for WebView
    }

    @Override
    public void requestFocusInWindow() {
        panel.requestFocusInWindow();
    }

    @Override
    public void setSearchCompleteCallback(@Nullable SearchCompleteCallback cb) {
        this.callback = (cb != null) ? cb : SearchCompleteCallback.NONE;
    }

    @Override
    public @Nullable SearchCompleteCallback getSearchCompleteCallback() {
        return callback;
    }

    private boolean hasSearchChanged(String q, boolean cs) {
        return !Objects.equals(currentQuery, q) || currentCaseSensitive != cs;
    }

    @Override
    public void highlightAll(String searchText, boolean caseSensitive) {
        var q = searchText.trim();
        if (q.isEmpty()) {
            clearHighlights();
            callback.onSearchComplete(0, 0);
            return;
        }
        callback.onSearchStart();
        currentQuery = q;
        currentCaseSensitive = caseSensitive;
        panel.setSearch(q, caseSensitive);
    }

    @Override
    public void clearHighlights() {
        currentQuery = "";
        currentCaseSensitive = false;
        lastTotal = 0;
        panel.clearSearch();
        callback.onSearchComplete(0, 0);
    }

    @Override
    public boolean findNext(String searchText, boolean caseSensitive, boolean forward) {
        if (searchText.trim().isEmpty()) {
            return false;
        }
        if (hasSearchChanged(searchText.trim(), caseSensitive)) {
            highlightAll(searchText, caseSensitive);
            return false;
        }
        if (lastTotal <= 0) {
            return false;
        }
        if (forward) {
            panel.nextMatch();
        } else {
            panel.prevMatch();
        }
        return true;
    }

    @Override
    public void centerCaretInView() {
        panel.scrollSearchCurrent();
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }
}
