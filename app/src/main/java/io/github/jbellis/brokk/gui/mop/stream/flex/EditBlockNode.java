package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import io.github.jbellis.brokk.git.GitStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Represents an EDIT BLOCK in the Flexmark AST.
 * These blocks have the format: &lt;<<<< SEARCH foo ===== REPLACE bar >>>>>.
 */
public class EditBlockNode extends Block {
    private BasedSequence openingMarker = BasedSequence.NULL;
    private BasedSequence searchKeyword = BasedSequence.NULL;
    private BasedSequence searchText = BasedSequence.NULL;
    private BasedSequence divider = BasedSequence.NULL;
    private BasedSequence replaceKeyword = BasedSequence.NULL;
    private BasedSequence replaceText = BasedSequence.NULL;
    private BasedSequence closingMarker = BasedSequence.NULL;
    private @Nullable String filename;
    private GitStatus status = GitStatus.UNKNOWN;
    
    /**
     * Get the filename if specified or empty sequence if not.
     */
    public BasedSequence getFilename() {
        return filename != null ? BasedSequence.of(filename) : BasedSequence.NULL;
    }
    
    /**
     * Set the filename for this block.
     */
    public void setFilename(@Nullable String filename) {
        this.filename = filename;
    }
    
    /**
     * Get the number of added lines in the edit block.
     */
    public int getAdds() {
        String content = replaceText.toString().trim();
        if (content.isEmpty()) return 0;
        return (int)content.lines().count();
    }
    
    /**
     * Get the number of deleted lines in the edit block.
     */
    public int getDels() {
        String content = searchText.toString().trim();
        if (content.isEmpty()) return 0;
        return (int)content.lines().count();
    }
    
    /**
     * Get the number of changed lines (lines modified in place).
     */
    public int getChangedLines() {
        return Math.min(getAdds(), getDels());
    }

    public BasedSequence getOpeningMarker() {
        return openingMarker;
    }

    /**
     * Get the git status of this edit block.
     */
    public GitStatus getStatus() {
        return status;
    }
    
    /**
     * Set the git status of this edit block.
     */
    public void setStatus(GitStatus status) {
        this.status = status;
    }

    /**
     * Set segments of this node based on parsed sequences.
     */
    public void setSegments(BasedSequence openingMarker, 
                           BasedSequence searchKeyword,
                           BasedSequence searchText,
                           BasedSequence divider,
                           BasedSequence replaceKeyword,
                           BasedSequence replaceText, 
                           BasedSequence closingMarker) {
        this.openingMarker = openingMarker;
        this.searchKeyword = searchKeyword;
        this.searchText = searchText;
        this.divider = divider;
        this.replaceKeyword = replaceKeyword;
        this.replaceText = replaceText;
        this.closingMarker = closingMarker;
    }
    
    @Override
    public BasedSequence[] getSegments() {
        return new BasedSequence[] {
            openingMarker,
            searchKeyword,
            searchText,
            divider,
            replaceKeyword,
            replaceText,
            closingMarker
        };
    }
}
