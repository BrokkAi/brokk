package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.util.data.DataHolder;

import java.util.HashSet;
import java.util.Set;

/**
 * Custom renderer for fenced code blocks that produces placeholder HTML elements
 * with data attributes instead of actual HTML code blocks.
 */
public class CodeFenceRenderer implements NodeRenderer {
    
    private final IdProvider idProvider;
    
    public CodeFenceRenderer(DataHolder options) {
        this.idProvider = options.get(IdProvider.ID_PROVIDER);
    }
    
    @Override
    public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
        Set<NodeRenderingHandler<?>> handlers = new HashSet<>();
        handlers.add(new NodeRenderingHandler<>(FencedCodeBlock.class, this::render));
        return handlers;
    }
    
    /**
     * Renders a fenced code block as a placeholder HTML element,
     * preserving internal whitespace.
     */
    private void render(FencedCodeBlock node, NodeRendererContext context, HtmlWriter html) {
        int id = idProvider.getId(node);
        String language = node.getInfo().toString();
        String content = node.getContentChars().toString();

        // logger.debug("Rendering code fence with id={}, language={}, content length={}", id, language, content.length());

        // Output the code-fence element with attributes, containing a pre block
        // for whitespace preservation in the browser.
        html.line();
        html.attr("data-id", String.valueOf(id))
            .attr("data-lang", language)
            .withAttr()
            .tag("code-fence") // Attributes are applied here
            .openPre()         // Enter preformatted mode for whitespace preservation
            .text(content)     // Render content, escaping HTML but preserving whitespace
            .closePre()        // Exit preformatted mode
            .closeTag("code-fence");
        html.line();
    }
    
    /**
     * Factory for creating CodeFenceRenderer instances.
     */
    public static class Factory implements com.vladsch.flexmark.html.renderer.NodeRendererFactory {
        @Override
        public NodeRenderer apply(DataHolder options) {
            return new CodeFenceRenderer(options);
        }
    }
}
