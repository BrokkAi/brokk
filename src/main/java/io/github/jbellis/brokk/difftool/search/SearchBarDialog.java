
package io.github.jbellis.brokk.difftool.search;

import io.github.jbellis.brokk.difftool.ui.BrokkDiffPanel;
import io.github.jbellis.brokk.difftool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.difftool.ui.FilePanel;
import io.github.jbellis.brokk.gui.search.SearchBarPanel;
import io.github.jbellis.brokk.gui.search.SearchCallback;
import io.github.jbellis.brokk.gui.search.SearchCommand;
import io.github.jbellis.brokk.gui.search.SearchResults;

import javax.swing.*;

/**
 * Search bar dialog for the diff tool, now using the generic SearchBarPanel.
 */
public class SearchBarDialog extends JPanel implements SearchCallback {
    private final BufferDiffPanel bufferDiffPanel;
    private FilePanel filePanel;
    private final SearchBarPanel searchBarPanel;

    public SearchBarDialog(BrokkDiffPanel brokkDiffPanel, BufferDiffPanel bufferDiffPanel) {
        this.bufferDiffPanel = bufferDiffPanel;
        this.searchBarPanel = new SearchBarPanel(this, false); // Don't show case sensitive checkbox since it's in bufferDiffPanel
        init();
    }

    public void setFilePanel(FilePanel filePanel) {
        this.filePanel = filePanel;
    }

    private void init() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(searchBarPanel);
        
        // Connect the external case sensitive checkbox to trigger search
        bufferDiffPanel.getCaseSensitiveCheckBox().addActionListener(e -> performSearch(getCommand()));
    }

    public SearchCommand getCommand() {
        // Use the external case sensitive checkbox from bufferDiffPanel
        return new SearchCommand(searchBarPanel.getSearchText(), bufferDiffPanel.getCaseSensitiveCheckBox().isSelected());
    }

    // SearchCallback implementation
    @Override
    public SearchResults performSearch(SearchCommand command) {
        if (filePanel == null) {
            return SearchResults.noMatches();
        }
        
        SearchHits searchHits = filePanel.doSearch();
        if (searchHits == null || searchHits.getSearchHits().isEmpty()) {
            return SearchResults.noMatches();
        }
        
        // For now, we don't have detailed result navigation in the diff tool
        // Just return that we have matches
        return SearchResults.withMatches(searchHits.getSearchHits().size(), 1);
    }

    @Override
    public void goToPreviousResult() {
        if (filePanel != null) {
            filePanel.doPreviousSearch();
        }
    }

    @Override
    public void goToNextResult() {
        if (filePanel != null) {
            filePanel.doNextSearch();
        }
    }

    @Override
    public void stopSearch() {
        if (filePanel != null) {
            filePanel.doStopSearch();
        }
    }
}
