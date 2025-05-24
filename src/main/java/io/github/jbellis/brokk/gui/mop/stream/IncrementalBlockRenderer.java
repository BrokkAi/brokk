package io.github.jbellis.brokk.gui.mop.stream;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.ComponentDataFactory;
import io.github.jbellis.brokk.gui.mop.stream.blocks.CompositeComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownComponentData;
import io.github.jbellis.brokk.gui.mop.stream.blocks.MarkdownFactory;
import io.github.jbellis.brokk.gui.mop.stream.flex.BrokkMarkdownExtension;
import io.github.jbellis.brokk.gui.mop.stream.flex.IdProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import javax.swing.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Renders markdown content incrementally, reusing existing components when possible to minimize flickering
 * and maintain scroll/caret positions during updates.
 */
public final class IncrementalBlockRenderer {
    private static final Logger logger = LogManager.getLogger(IncrementalBlockRenderer.class);
    
    // The root panel that will contain all our content blocks
    private final JPanel root;
    private final boolean isDarkTheme;
    
    // Flexmark parser components
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final IdProvider idProvider;

    // Component tracking
    private final Map<Integer, Reconciler.BlockEntry> registry = new LinkedHashMap<>();
    private String lastHtmlFingerprint = "";
    private String lastMarkdown = "";
    private boolean compacted = false;
    
    // Component factories
    private static final Map<String, ComponentDataFactory> FACTORIES = 
            ServiceLoader.load(ComponentDataFactory.class)
                         .stream()
                         .map(ServiceLoader.Provider::get)
                         .collect(Collectors.toMap(ComponentDataFactory::tagName, f -> f));
    
    // Per-instance filtered factories
    private final Map<String, ComponentDataFactory> activeFactories;
    
    // Fallback factory for markdown content
    private final MarkdownFactory markdownFactory = new MarkdownFactory();

    /**
     * Creates a new incremental renderer with the given theme.
     * 
     * @param dark true for dark theme, false for light theme
     */
    public IncrementalBlockRenderer(boolean dark) {
        this(dark, true);
    }
    
    /**
     * Creates a new incremental renderer with the given theme and edit block setting.
     * 
     * @param dark true for dark theme, false for light theme
     * @param enableEditBlocks true to enable edit block parsing and rendering, false to disable
     */
    public IncrementalBlockRenderer(boolean dark, boolean enableEditBlocks) {
        this.isDarkTheme = dark;
        
        // Create root panel with vertical BoxLayout
        root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setOpaque(false);
        root.setBackground(ThemeColors.getColor(dark, "chat_background"));
        
        // Initialize Flexmark with our extensions
        idProvider = new IdProvider();
        MutableDataSet options = new MutableDataSet()
            .set(Parser.EXTENSIONS, Arrays.asList(
                    TablesExtension.create(),
                    BrokkMarkdownExtension.create()
            ))
            .set(BrokkMarkdownExtension.ENABLE_EDIT_BLOCK, enableEditBlocks)
            .set(IdProvider.ID_PROVIDER, idProvider)
            .set(HtmlRenderer.SOFT_BREAK, "<br />\n")
            .set(HtmlRenderer.ESCAPE_HTML, true);
            
        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();
        
        // Filter out edit blocks if disabled
        if (enableEditBlocks) {
            activeFactories = FACTORIES;
            logger.debug("Edit blocks enabled");
        } else {
            activeFactories = FACTORIES.entrySet().stream()
                    .filter(e -> !"edit-block".equals(e.getKey()))
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
            logger.debug("Edit blocks disabled");
        }
        
        logger.debug("Initialized IncrementalBlockRenderer with Flexmark parser and custom extensions");
    }
    
    /**
     * Returns the root component that should be added to a container.
     * 
     * @return the root panel containing all rendered content
     */
    public JComponent getRoot() {
        return root;
    }
    
    /**
     * Updates the content with the given markdown text.
     * Parses the markdown, extracts components, and updates the UI incrementally.
     * 
     * @param markdown the markdown text to display
     * @throws IllegalStateException if called after compactMarkdown() has been invoked
     */
    public void update(String markdown) {
        if (compacted) {
            throw new IllegalStateException("Cannot update content after compaction. Call compactMarkdown() only after streaming is complete.");
        }
        
        var html = createHtml(markdown);
        
        // Skip if nothing changed
        String htmlFp = html.hashCode() + "";
        if (htmlFp.equals(lastHtmlFingerprint)) {
            // logger.debug("Skipping update - content unchanged");
            return;
        }
        lastHtmlFingerprint = htmlFp;
        lastMarkdown = markdown;
        
        // Extract component data from HTML
        List<ComponentData> components = buildComponentData(html);
        
        // Update the UI with the reconciled components
        updateUI(components);
    }
    
    /**
     * Thread-safe version of update that separates parsing from UI updates.
     * This method can be called from any thread, with only the UI update
     * happening on the EDT.
     * 
     * @param components The component data to apply to the UI
     */
    public void applyUI(List<ComponentData> components) {
        assert SwingUtilities.isEventDispatchThread() : "applyUI must be called on EDT";
        updateUI(components);
    }
    
    /**
     * Updates the UI with the given component data, reusing existing components when possible.
     * 
     * @param components The list of component data to render
     */
    private void updateUI(List<ComponentData> components) {
        Reconciler.reconcile(root, components, registry, isDarkTheme);
    }

    public String createHtml(CharSequence md) {
        // Parse with Flexmark
        // Parser.parse expects a String or BasedSequence. Convert CharSequence to String.
        var document = parser.parse(md.toString());
        return renderer.render(document);  // Don't sanitize yet - let MarkdownComponentData handle it
    }
    
    /**
     * Swing's HTMLEditorKit predates HTML5 and does not recognize &apos;.
     * Convert to the numeric entity &#39; that it does understand.
     */
    public static String sanitizeForSwing(String html) {
        return html
            .replace("&amp;apos;", "&#39;")  // Fix double-escaped apos
            .replace("&amp;#39;", "&#39;")   // Fix double-escaped numeric
            .replace("&apos;", "&#39;");     // Convert any remaining apos
    }
    
    /**
     * Builds a list of component data by parsing the HTML and extracting all placeholders
     * and intervening prose segments.
     * 
     * @param html The HTML string to parse
     * @return A list of ComponentData objects in document order
     */
    public List<ComponentData> buildComponentData(String html) {
        List<ComponentData> result = new ArrayList<>();
        
        Document doc = Jsoup.parse(html);
        var body = doc.body();
        
        // Initialize the MiniParser
        var miniParser = new MiniParser();
        
        // Process each top-level node in the body (including TextNodes)
        for (Node child : body.childNodes()) {
            if (child instanceof Element element) {
                // Parse the element tree to find nested custom tags
                var parsedElements = miniParser.parse(element, markdownFactory, activeFactories, idProvider);
                
                // For stability of IDs, ensure composites get a deterministic ID
                // derived from the source element's position via IdProvider
                parsedElements = normalizeCompositeId(element, parsedElements);
                
                // Add all parsed components to our result list
                result.addAll(parsedElements);
            } else if (child instanceof org.jsoup.nodes.TextNode textNode && !textNode.isBlank()) {
                // For plain text nodes, create a markdown component directly.
                // Let Swing's HTMLEditorKit handle basic escaping - it knows what it needs.
                int id = idProvider.getId(body); // Use body as anchor for stability
                result.add(markdownFactory.fromText(id, textNode.getWholeText()));
            }
        }
        
        return result;
    }
    
    /**
     * Ensures that a single CompositeComponentData produced for a top-level
     * element gets a stable, deterministic id derived from the element's
     * source offset (via IdProvider). If the input contains anything other
     * than one composite, it is returned unchanged.
     * 
     * @param topLevelElement The source HTML element
     * @param parsed The list of components parsed from the element
     * @return The same list with any composite's ID normalized
     */
    private List<ComponentData> normalizeCompositeId(Element topLevelElement, 
                                                    List<ComponentData> parsed) {
        if (parsed.size() != 1 || !(parsed.getFirst() instanceof CompositeComponentData composite)) {
            return parsed;  // No work to do
        }
        
        // Use IdProvider to get a stable ID based on the element's position in the source
        int stableId = idProvider.getId(topLevelElement);
        
        if (composite.id() == stableId) {
            return parsed;  // Already has the correct ID
        }
        
        // Create a new composite with the stable ID but same children
        return List.of(new CompositeComponentData(stableId, composite.children()));
    }
    
    /**
     * Merges consecutive MarkdownComponentData blocks that have no intervening special blocks
     * (code-fence, edit-block, etc.). Safe to call multiple times; subsequent invocations are no-ops.
     * This method should be called only after streaming is complete to ensure a consistent user
     * experience for text selection.
     */
    public void compactMarkdown() {
        if (compacted) {
            logger.debug("Renderer already compacted - skipping");
            return;
        }

        // Rebuild components from the last known markdown content
        List<ComponentData> originalComponents;
        if (!lastMarkdown.isEmpty()) {
            var html = createHtml(lastMarkdown);
            originalComponents = buildComponentData(html);
        } else {
            logger.debug("No markdown content to compact - skipping");
            compacted = true;
            return;
        }

        var merged = mergeMarkdownBlocks(originalComponents);
        logger.debug("Compacting markdown blocks: {} -> {}", originalComponents.size(), merged.size());
        updateUI(merged);
        compacted = true;
        // Update the last markdown to reflect the merged state for future builds
        // Reconstruct markdown content from merged components to ensure consistency
        lastMarkdown = merged.stream()
                             .filter(cd -> cd instanceof MarkdownComponentData)
                             .map(cd -> ((MarkdownComponentData) cd).html())
                             .collect(Collectors.joining("\n"));
        lastHtmlFingerprint = merged.stream().map(ComponentData::fp).collect(Collectors.joining("-"));
    }

    /**
     * Merges consecutive MarkdownComponentData blocks into a single block.
     * 
     * @param src The source list of ComponentData objects
     * @return A new list with consecutive MarkdownComponentData blocks merged
     */
    private List<ComponentData> mergeMarkdownBlocks(List<ComponentData> src) {
        var out = new ArrayList<ComponentData>();
        MarkdownComponentData acc = null;
        StringBuilder htmlBuf = null;

        for (ComponentData cd : src) {
            if (cd instanceof MarkdownComponentData md) {
                if (acc == null) {
                    acc = md;
                    htmlBuf = new StringBuilder(md.html());
                } else {
                    htmlBuf.append('\n').append(md.html());
                }
            } else {
                flush(out, acc, htmlBuf);
                out.add(cd);
                acc = null;
                htmlBuf = null;
            }
        }
        flush(out, acc, htmlBuf);
        return out;
    }

    /**
     * Flushes accumulated Markdown content into the output list.
     * 
     * @param out The output list to add the merged component to
     * @param acc The accumulated MarkdownComponentData
     * @param htmlBuf The StringBuilder containing the merged HTML content
     */
    private void flush(List<ComponentData> out, MarkdownComponentData acc, StringBuilder htmlBuf) {
        if (acc == null || htmlBuf == null) return;
        var merged = markdownFactory.fromText(acc.id(), htmlBuf.toString());
        out.add(merged);
    }
}
