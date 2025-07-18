package io.github.jbellis.brokk.difftool.search;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class SearchHits {
    private final List<SearchHit> searchHits;
    @Nullable
    private SearchHit current;

    public SearchHits() {
        searchHits = new ArrayList<SearchHit>();
    }

    public void add(SearchHit sh) {
        searchHits.add(sh);
        if (getCurrent() == null) {
            setCurrent(sh);
        }
    }

    public List<SearchHit> getSearchHits() {
        return searchHits;
    }

    public boolean isCurrent(SearchHit sh) {
        var currentHit = getCurrent();
        return currentHit != null && currentHit.equals(sh);
    }

    @Nullable
    public SearchHit getCurrent() {
        return current;
    }

    private void setCurrent(SearchHit sh) {
        current = sh;
    }

    public void next() {
        int index;

        index = searchHits.indexOf(getCurrent());
        index++;

        if (index >= searchHits.size()) {
            index = 0;
        }

        if (index >= 0 && index < searchHits.size()) {
            setCurrent(searchHits.get(index));
        }
    }

    public void previous() {
        int index;

        index = searchHits.indexOf(getCurrent());
        index--;

        if (index < 0) {
            index = searchHits.size() - 1;
        }

        if (index >= 0 && index < searchHits.size()) {
            setCurrent(searchHits.get(index));
        }
    }
}
