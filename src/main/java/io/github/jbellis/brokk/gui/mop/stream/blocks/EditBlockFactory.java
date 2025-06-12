package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.git.GitStatus;
import org.jsoup.nodes.Element;

import java.util.Locale;

/**
 * Factory for creating EditBlockComponentData instances from HTML elements.
 */
public class EditBlockFactory implements ComponentDataFactory {
    @Override
    public String tagName() {
        return "edit-block";
    }
    
    @Override
    public ComponentData fromElement(Element element) {
        int id = Integer.parseInt(element.attr("data-id"));
        int adds = Integer.parseInt(element.attr("data-adds"));
          int dels = Integer.parseInt(element.attr("data-dels"));
          int changed = element.hasAttr("data-changed") 
                          ? Integer.parseInt(element.attr("data-changed")) 
                          : Math.min(adds, dels); // Fallback for old markup
          String file = element.attr("data-file");
          
          GitStatus status = GitStatus.UNKNOWN;
        if (element.hasAttr("data-status")) {
            try {
                status = GitStatus.valueOf(element.attr("data-status").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                // If status is invalid, default to UNKNOWN
            }
          }
          
          return new EditBlockComponentData(id, adds, dels, changed, file, status);
      }
}
