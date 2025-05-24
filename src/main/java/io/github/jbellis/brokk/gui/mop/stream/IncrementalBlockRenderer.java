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
        } else {
            activeFactories = FACTORIES.entrySet().stream()
                    .filter(e -> !"edit-block".equals(e.getKey()))
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }

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
        // This method is typically called for initial non-streaming updates or full replacements.
        // Ensure UI updates happen on EDT.
        if (SwingUtilities.isEventDispatchThread()) {
            updateUI(components);
        } else {
            SwingUtilities.invokeLater(() -> updateUI(components));
        }
    }
    
    /**
     * Thread-safe version of update that separates parsing from UI updates.
     * This method can be called from any thread, with only the UI update
     * happening on the EDT.
     * 
     * @param components The component data to apply to the UI
     */
    public void applyUI(List<ComponentData> components) {
        if (compacted) {
            logger.warn("[COMPACTION] applyUI skipped - renderer already compacted. Incoming size: {}",
                        components == null ? "null" : components.size());
            return;
        }
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
        String markdownString = md.toString(); // Convert once
        this.lastMarkdown = markdownString;    // Store it for compaction
        var document = parser.parse(markdownString); // Parse the stored string
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
     * Builds a snapshot of what the component data would look like if compacted.
     * This method performs CPU-intensive work and should be called off the EDT.
     * It does not modify the renderer's state.
     *
     * @return A list of {@link ComponentData} representing the compacted state,
     *         or {@code null} if compaction is not needed (e.g., already compacted or no content).
     */
    public List<ComponentData> buildCompactedSnapshot(long roundId) {
        // This check is a hint; the authoritative 'compacted' flag is checked on EDT in applyCompactedSnapshot.
        if (compacted) {
            return null;
        }
        if (lastMarkdown.isEmpty()) {
            return null;
        }

        var html = createHtml(lastMarkdown);
        var originalComponents = buildComponentData(html);
        var merged = mergeMarkdownBlocks(originalComponents, roundId);
        return merged;
    }

    /**
     * Applies a previously built compacted snapshot to the UI.
     * This method must be called on the EDT. It updates the renderer's state.
     *
     * @param mergedComponents The list of {@link ComponentData} from {@link #buildCompactedSnapshot(long)}.
     *                         If {@code null}, it typically means compaction was skipped or not needed.
     */
    public void applyCompactedSnapshot(List<ComponentData> mergedComponents, long roundId) {
        assert SwingUtilities.isEventDispatchThread() : "applyCompactedSnapshot must be called on EDT";

        if (compacted) { // Authoritative check on EDT
            return;
        }

        // Case 1: No initial markdown content. Mark as compacted and do nothing else.
        if (lastMarkdown.isEmpty()) {
            compacted = true;
            return;
        }

        // Case 2: buildCompactedSnapshot decided not to produce components (e.g., it thought it was already compacted, or content was empty).
        // Mark as compacted.
        if (mergedComponents == null) {
            compacted = true;
            return;
        }
        
        // Case 3: Actual compaction and UI update.
        int currentComponentCountBeforeUpdate = root.getComponentCount();
        logger.debug("[COMPACTION][{}] Apply snapshot: Compacting. UI components before update: {}, New data component count: {}",
                     roundId, currentComponentCountBeforeUpdate, mergedComponents.size());
        updateUI(mergedComponents); 
        compacted = true;

        // Update lastMarkdown and lastHtmlFingerprint to reflect the merged state.
        this.lastMarkdown = mergedComponents.stream()
                                         .filter(cd -> cd instanceof MarkdownComponentData)
                                         .map(cd -> ((MarkdownComponentData) cd).html())
                                         .collect(Collectors.joining("\n"));
        this.lastHtmlFingerprint = mergedComponents.stream().map(ComponentData::fp).collect(Collectors.joining("-"));
    }

    /**
     * Merges consecutive MarkdownComponentData blocks into a single block.
     * 
     * @param src The source list of ComponentData objects
     * @return A new list with consecutive MarkdownComponentData blocks merged
     */
    private List<ComponentData> mergeMarkdownBlocks(List<ComponentData> src, long roundId) {
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
                flush(out, acc, htmlBuf, roundId);
                out.add(cd);
                acc = null;
                htmlBuf = null;
            }
        }
        flush(out, acc, htmlBuf, roundId);
        if (out.size() > 1 && src.stream().allMatch(c -> c instanceof MarkdownComponentData)) {
             logger.warn("[COMPACTION][{}] mergeMarkdownBlocks: Multiple MarkdownComponentData blocks in source did not merge into one. Output size: {}. Source types: {}",
                         roundId, out.size(), src.stream().map(c -> c.getClass().getSimpleName()).collect(Collectors.joining(", ")));
        }
        return out;
    }

    /**
     * Flushes accumulated Markdown content into the output list.
     * 
     * @param out The output list to add the merged component to
     * @param acc The accumulated MarkdownComponentData
     * @param htmlBuf The StringBuilder containing the merged HTML content
     */
    private void flush(List<ComponentData> out, MarkdownComponentData acc, StringBuilder htmlBuf, long roundId) {
        if (acc == null || htmlBuf == null) return;
        var merged = markdownFactory.fromText(acc.id(), htmlBuf.toString());
        out.add(merged);
    }
}
