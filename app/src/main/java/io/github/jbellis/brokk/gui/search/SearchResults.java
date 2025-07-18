package io.github.jbellis.brokk.gui.search;

/**
 * Represents the results of a search operation.
 */
public class SearchResults {
    private final int totalMatches;
    private final int currentMatch;
    private final boolean hasMatches;
    
    public SearchResults(int totalMatches, int currentMatch) {
        this.totalMatches = totalMatches;
        this.currentMatch = currentMatch;
        this.hasMatches = totalMatches > 0;
    }
    
    public static SearchResults noMatches() {
        return new SearchResults(0, 0);
    }
    
    public static SearchResults withMatches(int totalMatches, int currentMatch) {
        return new SearchResults(totalMatches, currentMatch);
    }
    
    public int getTotalMatches() {
        return totalMatches;
    }
    
    public int getCurrentMatch() {
        return currentMatch;
    }
    
    public boolean hasMatches() {
        return hasMatches;
    }
    
    public boolean isEmpty() {
        return !hasMatches;
    }
}