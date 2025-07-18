package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.git.GitStatus;
import org.jsoup.nodes.Entities;

import java.util.Locale;

/** One canonical place to turn a Search/Replace diff into inline-HTML. */
public final class EditBlockHtml {
    private EditBlockHtml() {}

    /**
     * Generates standard HTML for an edit block.
     * 
     * @param id The unique identifier for this edit block
     * @param file The filename associated with the edit
     * @param adds Count of added lines
     * @param dels Count of deleted lines
     * @param changed Count of changed lines
     * @param status The Git status of the file
     * @return HTML string with the edit-block element
     */
    public static String toHtml(int id,
                               String file,
                               int adds,
                               int dels,
                               int changed,
                               GitStatus status)
    {
        return """
               <edit-block data-id="%d" data-file="%s"
                           data-adds="%d" data-dels="%d" data-changed="%d"
                           data-status="%s"/>"""
            .formatted(id,
                       Entities.escape(file),
                       adds, dels, changed,
                       status.name().toLowerCase(Locale.ROOT));
    }
}
