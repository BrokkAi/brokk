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
import io.github.jbellis.brokk.gui.search.SearchConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import javax.swing.*;
import java.awt.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.regex.Pattern.*;

/**
 * Renders markdown content incrementally, reusing existing components when possible to minimize flickering
 * and maintain scroll/caret positions during updates.
 */
public final class IncrementalBlockRenderer {
    private static final Logger logger = LogManager.getLogger(IncrementalBlockRenderer.class);
    
    // Performance optimization: cached compiled pattern
    private static final Pattern MARKER_ID_PATTERN = Pattern.compile("data-brokk-id\\s*=\\s*\"(\\d+)\"");
    
    // The root panel that will contain all our content blocks
    private final JPanel root;
    private final boolean isDarkTheme;
    
    // Flexmark parser components
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final IdProvider idProvider;

    // Component tracking
    private final Map<Integer, Reconciler.BlockEntry> registry = new LinkedHashMap<>();

    // Marker-id ► Swing component index, rebuilt after every reconcile
    private final Map<Integer, JComponent> markerIndex = new ConcurrentHashMap<>();
    
    // Content tracking
    private String lastHtmlFingerprint = "";
    private String currentMarkdown = "";  // The current markdown content (always markdown, never HTML)
    private boolean compacted = false;    // Whether content has been compacted for better text selection

    // Per-instance HTML customizer; defaults to NO_OP to avoid null checks
    private volatile HtmlCustomizer htmlCustomizer = HtmlCustomizer.DEFAULT;
    
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
        this(dark, true, true);
    }

    public IncrementalBlockRenderer(boolean dark, boolean enableEditBlocks) {
        this(dark, enableEditBlocks, true);
    }

    /**
     * Creates a new incremental renderer with the given theme, edit block, and HTML escaping settings.
     *
     * @param dark true for dark theme, false for light theme
     * @param enableEditBlocks true to enable edit block parsing and rendering, false to disable
     * @param escapeHtml true to escape HTML within markdown, false to allow raw HTML
     */
    public IncrementalBlockRenderer(boolean dark, boolean enableEditBlocks, boolean escapeHtml) {
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
            .set(HtmlRenderer.ESCAPE_HTML, escapeHtml)
            .set(TablesExtension.MIN_SEPARATOR_DASHES, 1);

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
     * Register or clear an HtmlCustomizer.
     */
    public void setHtmlCustomizer(HtmlCustomizer customizer) {
        this.htmlCustomizer = customizer == null ? HtmlCustomizer.DEFAULT : customizer;
    }

    /**
     * Re-runs the current HtmlCustomizer against the last rendered Markdown and
     * updates the Swing components. Safe to call from any thread.
     * Does nothing if no markdown has been rendered yet.
     */
    public void reprocessForCustomizer() {
        // Always use currentMarkdown for reprocessing
        if (currentMarkdown.isEmpty()) {
            return; // nothing rendered yet
        }
        
        // Quick optimisation: bail out if the new customizer would not change anything
        if (!wouldAffect(currentMarkdown)) {
            return;
        }

        Runnable task = () -> {
            // Always process from markdown for consistency
            var html = createHtml(currentMarkdown);
            lastHtmlFingerprint = Integer.toString(html.hashCode());
            List<ComponentData> components = buildComponentData(html);
            
            if (compacted) {
                // Re-apply compaction after processing
                components = mergeMarkdownBlocks(components, -1L);
                // Clear and rebuild UI for compacted state
                root.removeAll();
                registry.clear();
                markerIndex.clear();
            }
            
            updateUI(components);
        };

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    
    /**
     * Returns false when the current htmlCustomizer can be proven to have no
     * impact on the supplied markdown, allowing us to skip re-rendering.
     */
    private boolean wouldAffect(String text) {
        if (htmlCustomizer instanceof TextNodeMarkerCustomizer tnmc) {
            try {
                return tnmc.mightMatch(text);
            } catch (Exception e) {
                // fall through – be conservative
                logger.trace("wouldAffect: conservative fallback after exception", e);
            }
        }
        return true; // unknown customizer types => assume yes
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
        currentMarkdown = markdown;
        
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
        // After components are (re)built update marker index
        rebuildMarkerIndex();
    }

    public String createHtml(CharSequence md) {
        // Parse with Flexmark
        // Parser.parse expects a String or BasedSequence. Convert CharSequence to String.
        String markdownString = md.toString(); // Convert once
        this.currentMarkdown = markdownString;    // Store current markdown
        var document = parser.parse(markdownString); // Parse the stored string
        return renderer.render(document);
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

        // Allow in-place DOM customisation before component extraction
        try {
            htmlCustomizer.customize(body);
        } catch (Exception e) {
            logger.warn("HtmlCustomizer threw exception; proceeding with uncustomised DOM", e);
        }
        
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
        if (currentMarkdown.isEmpty()) {
            return null;
        }

        var html = createHtml(currentMarkdown);
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
        if (currentMarkdown.isEmpty()) {
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
        logger.trace("[COMPACTION][{}] Apply snapshot: Compacting. UI components before update: {}, New data component count: {}",
                     roundId, currentComponentCountBeforeUpdate, mergedComponents.size());
        updateUI(mergedComponents); 
        compacted = true;

        // Store the compacted HTML for future reference
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

    // ---------------------------------------------------------------------
    //  Marker-ID indexing helpers
    // ---------------------------------------------------------------------

    /**
     * Rebuilds the marker-id to component index by walking the component tree.
     * Must be called on EDT.
     */
    private void rebuildMarkerIndex() {
        assert SwingUtilities.isEventDispatchThread();
        markerIndex.clear();
        // Rebuild marker index
        walkAndIndex(root);
        logger.trace("Marker index rebuilt with {} entries", markerIndex.size());
    }

    private void walkAndIndex(Component c) {
        if (c instanceof JComponent jc) {
            var html = extractHtmlFromComponent(jc);
            if (html != null && !html.isEmpty()) {
                var matcher = MARKER_ID_PATTERN.matcher(html);
                boolean foundAny = false;
                while (matcher.find()) {
                    try {
                        int id = Integer.parseInt(matcher.group(1));
                        markerIndex.put(id, jc);
                        foundAny = true;
                        // Found marker in component
                    } catch (NumberFormatException ignore) {
                        // should never happen – regex enforces digits
                    }
                }
            }
        }
        if (c instanceof java.awt.Container container) {
            for (var child : container.getComponents()) {
                walkAndIndex(child);
            }
        }
    }

    /**
     * Best-effort extraction of inner HTML/text from known Swing components.
     */
    private static String extractHtmlFromComponent(JComponent jc) {
        if (jc instanceof JEditorPane jp) {
            return jp.getText();
        } else if (jc instanceof JLabel lbl) {
            return lbl.getText();
        }
        return null;
    }

    // ---------------------------------------------------------------------
    //  Public lookup API
    // ---------------------------------------------------------------------

    /**
     * Returns the Swing component that displays the given marker id, if any.
     */
    public java.util.Optional<JComponent> findByMarkerId(int id) {
        return Optional.ofNullable(markerIndex.get(id));
    }

    /**
     * Returns all marker ids currently known to this renderer.
     */
    public Set<Integer> getIndexedMarkerIds() {
        return Set.copyOf(markerIndex.keySet());
    }
    
    /**
     * Updates the style of a specific marker by its ID using CSS classes.
     * This method performs regex replacement to avoid regenerating all marker IDs.
     */
    public void updateMarkerStyle(int markerId, boolean isCurrent) {
        assert SwingUtilities.isEventDispatchThread();
        Optional<JComponent> component = findByMarkerId(markerId);
        
        if (component.isEmpty()) {
            return;
        }
        
        JComponent comp = component.get();
        String html = extractHtmlFromComponent(comp);
        if (html == null || html.isEmpty()) {
            return;
        }
        
        // Find and update the span with the specific marker ID - replace class attribute
        String pattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)(?:\\s+class=\"[^\"]*\")?([^>]*?>)";
        
        String newClass = isCurrent 
            ? " class=\"" + SearchConstants.SEARCH_CURRENT_CLASS + "\""
            : " class=\"" + SearchConstants.SEARCH_HIGHLIGHT_CLASS + "\"";
            
        String updatedHtml = html.replaceAll(pattern, "$1" + newClass + "$2");
        
        if (!updatedHtml.equals(html)) {
            // Update the component with the new HTML
            if (comp instanceof JEditorPane editorPane) {
                editorPane.setText(updatedHtml);
            } else if (comp instanceof JLabel label) {
                label.setText(updatedHtml);
            }
            comp.revalidate();
            comp.repaint();
        } else {
        }
    }
}
