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
import io.github.jbellis.brokk.util.HtmlUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import javax.swing.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

/**
 * Renders markdown content incrementally, reusing existing components when possible to minimize flickering
 * and maintain scroll/caret positions during updates.
 */
public final class IncrementalBlockRenderer {
    // Compilation flag to enable/disable badge-based click detection
    private static final boolean ENABLE_BADGE_CLICK_DETECTION = true;
    private static final Logger logger = LogManager.getLogger(IncrementalBlockRenderer.class);

    // Performance optimization: cached compiled pattern
    private static final Pattern MARKER_ID_PATTERN = Pattern.compile("data-brokk-id\\s*=\\s*\"(\\d+)\"");

    // The root panel that will contain all our content blocks
    private final JPanel root;
    private final boolean isDarkTheme;

    /** Callback fired on EDT after each successful rendering pass. */
    public interface RenderListener { void onRenderFinished(); }

    @Nullable
    private volatile RenderListener renderListener = null;

    /**
     * Register (or clear) a listener that will be invoked exactly once
     * for every completed rendering pass.  Passing {@code null} removes
     * any previously registered listener.
     */
    public void setRenderListener(RenderListener listener) {
        this.renderListener = listener;
    }

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

    // Badge click handler for interactive badges
    @Nullable
    private volatile BadgeClickHandler badgeClickHandler = null;

    // Track active mouse listeners for cleanup
    private final java.util.Set<BadgeMouseListener> activeListeners = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
     * Register or clear a BadgeClickHandler for handling badge interactions.
     */
    public void setBadgeClickHandler(BadgeClickHandler handler) {
        logger.debug("Setting badgeClickHandler: {}", handler != null ? "non-null" : "null");
        this.badgeClickHandler = handler;
    }

    /**
     * Cleanup method to remove all mouse listeners and prevent memory leaks.
     * Should be called when the renderer is no longer needed.
     */
    public void cleanup() {
        logger.debug("Cleaning up IncrementalBlockRenderer, removing {} listeners", activeListeners.size());
        // Remove all active listeners
        for (BadgeMouseListener listener : activeListeners) {
            listener.cleanup();
        }
        activeListeners.clear();

        // Clear handler reference
        badgeClickHandler = null;

        // Clear marker index
        synchronized (markerIndex) {
            markerIndex.clear();
        }
    }

    /**
     * Removes listeners from components that are no longer in the UI tree.
     */
    private void cleanupOrphanedListeners() {
        var orphanedListeners = new java.util.ArrayList<BadgeMouseListener>();

        for (BadgeMouseListener listener : activeListeners) {
            if (!isComponentInTree(listener.editor, root)) {
                orphanedListeners.add(listener);
            }
        }

        for (BadgeMouseListener listener : orphanedListeners) {
            listener.cleanup();
            activeListeners.remove(listener);
            //logger.debug("Removed orphaned listener for component no longer in tree");
        }
    }

    /**
     * Checks if a component is still part of the component tree.
     */
    private boolean isComponentInTree(Component target, Container root) {
        if (target == root) return true;

        Container parent = target.getParent();
        while (parent != null) {
            if (parent == root) return true;
            parent = parent.getParent();
        }
        return false;
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
                components = mergeMarkdownBlocks(components);
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
        // Clean up listeners from components that may have been removed
        cleanupOrphanedListeners();

        // After components are (re)built update marker index
        rebuildMarkerIndex();

        // Add badge click handlers to newly created components
        var handler = badgeClickHandler; // capture volatile reference once
        if (handler != null) {
            addBadgeClickHandlers();
        }

        // Write HTML debug output after first render
        writeInitialHtmlDebugOutput();

        // Notify listener that rendering has finished
        if (renderListener != null) {
            try {
                renderListener.onRenderFinished();
            } catch (Exception e) {
                logger.warn("RenderListener threw exception", e);
            }
        }
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
        var miniParser = new MiniParser(idProvider);

        // Process each top-level node in the body (including TextNodes)
        for (Node child : body.childNodes()) {
            if (child instanceof Element element) {
                // Parse the element tree to find nested custom tags
                var parsedElements = miniParser.parse(element, markdownFactory, activeFactories);

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
    public @Nullable List<ComponentData> buildCompactedSnapshot(long roundId) {
        // This check is a hint; the authoritative 'compacted' flag is checked on EDT in applyCompactedSnapshot.
        if (compacted) {
            return null;
        }
        if (currentMarkdown.isEmpty()) {
            return null;
        }

        var html = createHtml(currentMarkdown);
        var originalComponents = buildComponentData(html);
        var merged = mergeMarkdownBlocks(originalComponents);
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
    private List<ComponentData> mergeMarkdownBlocks(List<ComponentData> src) {
        var out = new ArrayList<ComponentData>();
        MarkdownComponentData acc = null;
        StringBuilder htmlBuf = null;

        for (ComponentData cd : src) {
            if (cd instanceof MarkdownComponentData md) {
                if (acc == null) {
                    acc = md;
                    htmlBuf = new StringBuilder(castNonNull(md.html()));
                } else {
                    castNonNull(htmlBuf).append('\n').append(castNonNull(md.html()));
                }
            } else {
                flush(out, acc, htmlBuf);
                out.add(cd);
                acc = null;
                htmlBuf = null;
            }
        }
        flush(out, acc, htmlBuf);
        if (out.size() > 1 && src.stream().allMatch(c -> c instanceof MarkdownComponentData)) {
             logger.warn("[COMPACTION] mergeMarkdownBlocks: Multiple MarkdownComponentData blocks in source did not merge into one. Output size: {}. Source types: {}",
                         out.size(), src.stream().map(c -> c.getClass().getSimpleName()).collect(Collectors.joining(", ")));
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
    private void flush(List<ComponentData> out, @Nullable MarkdownComponentData acc, @Nullable StringBuilder htmlBuf) {
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
        synchronized (markerIndex) {
            markerIndex.clear();
            // Rebuild marker index
            walkAndIndex(root);
            logger.trace("Marker index rebuilt with {} entries", markerIndex.size());
        }
    }

    private void walkAndIndex(Component c) {
        if (c instanceof JComponent jc) {
            var html = extractHtmlFromComponent(jc);
            if (html != null && !html.isEmpty()) {
                var matcher = MARKER_ID_PATTERN.matcher(html);
                while (matcher.find()) {
                    try {
                        int id = Integer.parseInt(matcher.group(1));
                        synchronized (markerIndex) {
                            markerIndex.put(id, jc);
                        }
                        // Found marker in component
                    } catch (NumberFormatException ignore) {
                        // should never happen – regex enforces digits
                    }
                }
            }
        }
        if (c instanceof Container container) {
            for (var child : container.getComponents()) {
                walkAndIndex(child);
            }
        }
    }

    /**
     * Best-effort extraction of inner HTML/text from known Swing components.
     */
    private static @Nullable String extractHtmlFromComponent(JComponent jc) {
        if (jc instanceof JEditorPane jp) {
            return jp.getText();
        } else if (jc instanceof JLabel lbl) {
            return lbl.getText();
        }
        return null;
    }

    /**
     * Writes HTML debug output after initial render to help debug file badge structure.
     */
    private void writeInitialHtmlDebugOutput() {
        StringBuilder content = new StringBuilder();
        extractHtmlFromComponents(root, content);
        HtmlUtil.writeInitialRenderHtml(content.toString());
    }

    /**
     * Recursively extracts HTML content from components.
     */
    private void extractHtmlFromComponents(Container container, StringBuilder html) {
        for (Component comp : container.getComponents()) {
            if (comp instanceof JEditorPane editor) {
                html.append("<div style='border: 1px solid blue; margin: 10px; padding: 10px;'>\n");
                html.append("<h3>JEditorPane Content:</h3>\n");
                String editorHtml = editor.getText();
                html.append(editorHtml);
                html.append("\n</div>\n");
            } else if (comp instanceof Container subContainer) {
                extractHtmlFromComponents(subContainer, html);
            }
        }
    }

    // ---------------------------------------------------------------------
    //  Public lookup API
    // ---------------------------------------------------------------------

    /**
     * Returns the Swing component that displays the given marker id, if any.
     */
    public Optional<JComponent> findByMarkerId(int id) {
        synchronized (markerIndex) {
            return Optional.ofNullable(markerIndex.get(id));
        }
    }

    /**
     * Returns all marker ids currently known to this renderer.
     */
    public Set<Integer> getIndexedMarkerIds() {
        synchronized (markerIndex) {
            return Set.copyOf(markerIndex.keySet());
        }
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
        @Nullable String html = extractHtmlFromComponent(comp); // html can be null
        if (html == null || html.isEmpty()) {
            return;
        }

        // Find and update the span with the specific marker ID - replace class attribute
        // We need to handle both single and multiple classes in the class attribute
        String targetClass = isCurrent ? SearchConstants.SEARCH_CURRENT_CLASS : SearchConstants.SEARCH_HIGHLIGHT_CLASS;

        // Pattern to match the specific span and capture everything before and after the class attribute
        String pattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)\\s+class=\"[^\"]*\"([^>]*?>)";
        String replacement = "$1 class=\"" + targetClass + "\"$2";

        String updatedHtml = html.replaceAll(pattern, replacement);

        // If the pattern didn't match (no class attribute), add one
        if (updatedHtml.equals(html)) {
            String fallbackPattern = "(<span[^>]*?data-brokk-id=\"" + markerId + "\"[^>]*?)([^>]*?>)";
            String fallbackReplacement = "$1 class=\"" + targetClass + "\"$2";
            updatedHtml = html.replaceAll(fallbackPattern, fallbackReplacement);
        }


        if (!updatedHtml.equals(html)) {

            // Update the component with the new HTML
            if (comp instanceof JEditorPane editorPane) {
                editorPane.setText(updatedHtml);
            } else if (comp instanceof JLabel label) {
                label.setText(updatedHtml);
            }
            comp.revalidate();
            comp.repaint();

            // Signal that this render pass (including marker-style changes) is finished
            if (renderListener != null) {
                try {
                    renderListener.onRenderFinished();
                } catch (Exception e) {
                    logger.warn("RenderListener threw exception during updateMarkerStyle", e);
                }
            }
        } else {
        }
    }

    /**
     * Adds badge click handlers to all JEditorPane components in the root panel.
     */
    private void addBadgeClickHandlers() {
        logger.debug("Adding badge click handlers to root panel");
        addBadgeClickHandlersToComponent(root);
    }

    /**
     * Recursively adds badge click handlers to JEditorPane components.
     */
    private void addBadgeClickHandlersToComponent(Component comp) {
        if (comp instanceof JEditorPane editor) {
            // Check if we already added a handler (to avoid duplicates)
            boolean hasHandler = false;
            for (var listener : editor.getMouseListeners()) {
                if (listener instanceof BadgeMouseListener) {
                    hasHandler = true;
                    break;
                }
            }

            if (!hasHandler) {
                BadgeMouseListener listener = new BadgeMouseListener(editor);
                editor.addMouseListener(listener);
                activeListeners.add(listener);
            }
        }

        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                addBadgeClickHandlersToComponent(child);
            }
        }
    }

    /**
     * Mouse listener that detects clicks on badge elements.
     */
    private class BadgeMouseListener extends MouseAdapter {
        final JEditorPane editor; // package-private for cleanup access

        BadgeMouseListener(JEditorPane editor) {
            this.editor = editor;
            // Also register as motion listener for hover effects on badges
            editor.addMouseMotionListener(this);
        }

        /**
         * Cleanup method to remove this listener from the editor.
         */
        void cleanup() {
            editor.removeMouseListener(this);
            editor.removeMouseMotionListener(this);
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            // Update cursor when hovering over clickable filenames
            if (ENABLE_BADGE_CLICK_DETECTION) {
                try {
                    // Check if mouse is over any file badge or symbol badge element using DOM traversal
                    boolean isOverFileBadge = getFileBadgeFileNameAtMousePoint(e).isPresent();
                    boolean isOverSymbolBadge = getSymbolBadgeDataAtMousePoint(e).isPresent();

                    // Debug: Log mouse position and detection result
                    logger.trace("Mouse at {}, over file badge: {}, over symbol badge: {}", e.getPoint(), isOverFileBadge, isOverSymbolBadge);

                    // Update cursor based on badge detection
                    var cursor = (isOverFileBadge || isOverSymbolBadge) ?
                                 Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) :
                                 Cursor.getDefaultCursor();

                    // Only update if cursor actually changed to avoid unnecessary repaints
                    if (editor.getCursor().getType() != cursor.getType()) {
                        editor.setCursor(cursor);
                    }

                } catch (Exception ex) {
                    logger.warn("Error during hover detection: {}", ex.getMessage());
                }
            }
        }

        @Override
        public void mouseExited(MouseEvent e) {
            // Reset cursor when leaving the editor
            editor.setCursor(java.awt.Cursor.getDefaultCursor());
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            // Only handle right clicks for context menu
            if (!SwingUtilities.isRightMouseButton(e)) {
                return;
            }

            logger.trace("Right-click detected at point: {}", e.getPoint());

            var handler = badgeClickHandler; // capture volatile reference
            if (handler == null) {
                return;
            }

            // Check if click is over any file badge element using DOM traversal
            getFileBadgeFileNameAtMousePoint(e).ifPresent(fileName -> {
                logger.debug("Click over file badge with name: {}", fileName);
                handler.onBadgeClick("file", fileName, e, editor);
            });

            // Check if click is over any symbol badge element using DOM traversal
            getSymbolBadgeDataAtMousePoint(e).ifPresent(symbolData -> {
                logger.debug("Click over symbol badge with data: {}", symbolData);
                handler.onBadgeClick("symbol", symbolData, e, editor);
            });
        }

        /**
         * Gets the filename of the file badge at the given mouse point using clickable-file-badge class detection.
         * This approach only looks for the clickable-file-badge CSS class to determine if mouse is over a file badge.
         */
        private Optional<String> getFileBadgeFileNameAtMousePoint(MouseEvent e) {
            try {
                // Get the HTML document from the editor
                var doc = editor.getDocument();
                if (!(doc instanceof HTMLDocument htmlDoc)) {
                    return Optional.empty();
                }

                // Convert mouse point to document position
                int pos = editor.viewToModel2D(e.getPoint());
                if (pos < 0 || pos >= doc.getLength()) {
                    return Optional.empty();
                }

                // Get the element at this position and traverse up the hierarchy
                var element = htmlDoc.getCharacterElement(pos);
                return hasClass(element, BadgeConstants.CLASS_CLICKABLE_FILE_BADGE);

            } catch (Exception ex) {
                logger.trace("Error getting file badge at mouse point: {}", ex.getMessage());
                return Optional.empty();
            }
        }

        /**
         * Gets the symbol data of the symbol badge at the given mouse point using badge-symbol class detection.
         */
        private Optional<String> getSymbolBadgeDataAtMousePoint(MouseEvent e) {
            try {
                // Get the HTML document from the editor
                var doc = editor.getDocument();
                if (!(doc instanceof HTMLDocument htmlDoc)) {
                    return Optional.empty();
                }

                // Convert mouse point to document position
                int pos = editor.viewToModel2D(e.getPoint());
                if (pos < 0 || pos >= doc.getLength()) {
                    return Optional.empty();
                }

                // Get the element at this position and traverse up the hierarchy
                var element = htmlDoc.getCharacterElement(pos);
                return getSymbolIdFromElement(element);

            } catch (Exception ex) {
                logger.trace("Error getting symbol badge at mouse point: {}", ex.getMessage());
                return Optional.empty();
            }
        }

    }

    /**
     * Debug method to examine HTMLDocument element structure for badge detection.
     *
     * @param element The element to examine
     * @return true if element is valid for badge detection, false otherwise
     */
    private Optional<String> hasClass(javax.swing.text.Element element, String classId) {
        if (element instanceof AbstractDocument.AbstractElement el) {
            var u = el.getAttribute(HTML.Tag.CODE);
            if (u instanceof SimpleAttributeSet t) {
                var clazz = t.getAttribute(HTML.Attribute.CLASS);
                var title = t.getAttribute(HTML.Attribute.TITLE);
                if (Objects.requireNonNull(clazz) instanceof String s && s.contains(classId)) {
                    // For backward compatibility, check if title contains encoded format and parse it
                    String titleStr = title.toString();
                    if (titleStr.startsWith("file:") && titleStr.contains(":id:")) {
                        // Parse the encoded format "file:filename:id:123" to extract just "filename"
                        int fileStart = 5; // After "file:"
                        int idStart = titleStr.indexOf(":id:");
                        if (idStart > fileStart) {
                            return Optional.of(titleStr.substring(fileStart, idStart)); // Return just the filename part
                        }
                    }
                    // Otherwise, return the title as-is (should be just the filename now)
                    return Optional.of(titleStr);
                }
            }
            // Valid element types for badges
            return Optional.empty();
        }
        return Optional.empty(); // Not a valid badge element
    }

    /**
     * Gets symbol ID from HTML element if it contains a symbol badge.
     *
     * @param element The element to examine
     * @return Optional containing symbol ID if element is a symbol badge, empty otherwise
     */
    private Optional<String> getSymbolIdFromElement(javax.swing.text.Element element) {
        if (element instanceof AbstractDocument.AbstractElement el) {
            var u = el.getAttribute(HTML.Tag.CODE);
            if (u instanceof SimpleAttributeSet t) {
                var clazz = t.getAttribute(HTML.Attribute.CLASS);
                var title = t.getAttribute(HTML.Attribute.TITLE);

                if (clazz instanceof String s && s.contains("badge-symbol") && s.contains("clickable-badge") && title != null) {
                    // Extract symbol name from title attribute
                    // Title format: "class io.github.jbellis.brokk.gui.mop.stream.SymbolBadgeCustomizer (src/main/java/...)"
                    String titleStr = title.toString();

                    // Extract the fully qualified symbol name - it's after the symbol type and before the opening parenthesis
                    if (titleStr.contains(" ") && titleStr.contains(" (")) {
                        int spaceIndex = titleStr.indexOf(' ');
                        int parenIndex = titleStr.indexOf(" (");
                        if (spaceIndex > 0 && parenIndex > spaceIndex) {
                            String fullName = titleStr.substring(spaceIndex + 1, parenIndex);
                            // Return the full qualified name instead of just the simple name
                            return Optional.of(fullName);
                        }
                    }
                }
            }
            return Optional.empty();
        }
        return Optional.empty(); // Not a valid badge element
    }
}
