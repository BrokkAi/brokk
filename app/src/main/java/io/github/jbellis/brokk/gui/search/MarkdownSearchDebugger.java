package io.github.jbellis.brokk.gui.search;

import io.github.jbellis.brokk.gui.mop.MarkdownOutputPanel;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles all debugging functionality for MarkdownSearchableComponent.
 * Separated from main class to reduce complexity.
 */
public class MarkdownSearchDebugger {
    private static final Logger logger = LogManager.getLogger(MarkdownSearchDebugger.class);

    private final boolean debugEnabled;

    public MarkdownSearchDebugger(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    /**
     * Logs navigation debug information.
     */
    public void logNavigation(boolean forward, int oldIndex, int newIndex, List<SearchMatch> allMatches) {
        if (!debugEnabled) return;

        logger.debug("NAVIGATION: {} from index {} to {}",
                   forward ? "NEXT" : "PREV", oldIndex, newIndex);
        if (newIndex < allMatches.size()) {
            SearchMatch match = allMatches.get(newIndex);
            logger.debug("  Moving to: {} at [P:{},R:{},C:{},S:{}]",
                       match.getClass().getSimpleName(), match.panelIndex(), match.rendererIndex(),
                       match.componentVisualOrderInRenderer(),
                       switch (match) {
                           case CodeSearchMatch codeMatch -> codeMatch.subComponentIndex();
                           case MarkdownSearchMatch ignored -> 0;
                       });
            switch (match) {
                case CodeSearchMatch codeMatch -> {
                    logger.debug("  Code match: offsets {}-{}", codeMatch.startOffset(), codeMatch.endOffset());
                }
                case MarkdownSearchMatch ignored -> {
                    // No additional logging for markdown matches
                }
            }
        }
    }

    /**
     * Logs initial scroll debug information.
     */
    public void logInitialScroll(SearchMatch firstMatch) {
        if (!debugEnabled) return;

        logger.debug("INITIAL SCROLL: First match is {} at [P:{},R:{},C:{},S:{}] bounds={}",
                   firstMatch.getClass().getSimpleName(), firstMatch.panelIndex(), firstMatch.rendererIndex(),
                   firstMatch.componentVisualOrderInRenderer(),
                   switch (firstMatch) {
                       case CodeSearchMatch codeMatch -> codeMatch.subComponentIndex();
                       case MarkdownSearchMatch ignored -> 0;
                   },
                   firstMatch.actualUiComponent().getBounds());
    }

    /**
     * Logs renderer debug information.
     */
    public void logRendererDebug(int panelIdx, int rendererIdx, IncrementalBlockRenderer renderer) {
        if (!debugEnabled) return;

        var allMarkerIds = renderer.getIndexedMarkerIds();
        logger.debug("RENDERER DEBUG: [P:{},R:{}] has {} indexed markers: {}",
                   panelIdx, rendererIdx, allMarkerIds.size(),
                   allMarkerIds.stream()
                       .sorted()
                       .map(String::valueOf)
                       .collect(java.util.stream.Collectors.joining(", ")));
    }

    /**
     * Logs markdown match collection debug information.
     */
    public void logMarkdownMatches(JComponent component, int panelIdx, int rendererIdx, int compVisOrder,
                                 java.util.List<Integer> foundIndexedMarkers, 
                                 java.util.List<Integer> foundDirectMarkers,
                                 java.util.List<Integer> componentMarkers) {
        if (!debugEnabled) return;

        int indexedMatches = foundIndexedMarkers.size();
        int directMatches = foundDirectMarkers.size();

        if (indexedMatches > 0 || directMatches > 0) {
            logger.debug("MARKDOWN MATCHES: {} at [P:{},R:{},C:{}]",
                       component.getClass().getSimpleName(), panelIdx, rendererIdx, compVisOrder);
            if (indexedMatches > 0) {
                logger.debug("  Indexed markers ({}): {}", indexedMatches,
                           foundIndexedMarkers.stream()
                               .sorted()
                               .map(String::valueOf)
                               .collect(java.util.stream.Collectors.joining(", ")));
            }
            if (directMatches > 0) {
                logger.debug("  Direct markers ({}): {}", directMatches,
                           foundDirectMarkers.stream()
                               .sorted()
                               .map(String::valueOf)
                               .collect(java.util.stream.Collectors.joining(", ")));
            }
            logger.debug("  Final navigation order: {}",
                       componentMarkers.stream()
                           .map(String::valueOf)
                           .collect(java.util.stream.Collectors.joining(", ")));
            logger.debug("  Total: {} matches", indexedMatches + directMatches);
        }
    }

    /**
     * Logs detailed marker context information.
     */
    public void logDetailedMarkerContext(JComponent component, 
                                       java.util.List<Integer> foundIndexedMarkers,
                                       java.util.List<Integer> foundDirectMarkers,
                                       java.util.List<Integer> componentMarkers,
                                       java.util.List<MarkerInfo> detailedMarkers) {
        if (!debugEnabled || detailedMarkers.isEmpty()) return;

        logger.debug("  Marker contexts (in document order):");
        for (var marker : detailedMarkers) {
            String status = "";
            if (foundIndexedMarkers.contains(marker.markerId())) {
                status = " [INDEXED]";
            } else if (foundDirectMarkers.contains(marker.markerId())) {
                status = " [DIRECT]";
            } else {
                status = " [NOT_COLLECTED]";
            }
            // Show the subComponentIndex that will be assigned
            int subIdx = componentMarkers.indexOf(marker.markerId());
            if (subIdx >= 0) {
                status += " [S:" + subIdx + "]";
            }
            logger.debug("    {}{}", marker, status);
        }
    }

    /**
     * Prints comprehensive search results.
     */
    public void printSearchResults(List<SearchMatch> allMatches, int currentMatchIndex, String currentSearchTerm) {
        if (!debugEnabled) return;

        logger.debug("\n=== Search Results for '{}' ===", currentSearchTerm);
        logger.debug("Total matches: {}", allMatches.size());
        if (currentMatchIndex >= 0 && currentMatchIndex < allMatches.size()) {
            logger.debug("Current match: {} of {}", currentMatchIndex + 1, allMatches.size());
        }
    }

    /**
     * Prints blocks with matches debug information.
     */
    public void printBlocksWithMatches(List<SearchMatch> allMatches, int currentMatchIndex) {
        if (!debugEnabled) return;

        logger.debug("\n--- Blocks with matches ---");

        // Group matches by block (including sub-component index for code blocks)
        int currentPanelIdx = -1;
        int currentRendererIdx = -1;
        int currentComponentIdx = -1;
        int currentSubComponentIdx = -1;
        int blockMatchCount = 0;
        int blockFirstMatchIdx = -1;
        String blockType = "";
        String blockPreview = "";
        int totalBlocksWithMatches = 0;

        for (int i = 0; i < allMatches.size(); i++) {
            SearchMatch match = allMatches.get(i);

            // Check if we've moved to a new block (including sub-component for code)
            boolean newBlock = match.panelIndex() != currentPanelIdx ||
                             match.rendererIndex() != currentRendererIdx ||
                             match.componentVisualOrderInRenderer() != currentComponentIdx ||
                             switch (match) {
                                 case CodeSearchMatch codeMatch -> codeMatch.subComponentIndex() != currentSubComponentIdx;
                                 case MarkdownSearchMatch ignored -> false;
                             };

            if (newBlock && i > 0) {
                // Print previous block info
                boolean isCurrentBlock = currentMatchIndex >= blockFirstMatchIdx &&
                                       currentMatchIndex < blockFirstMatchIdx + blockMatchCount;
                String marker = isCurrentBlock ? " <<<< CURRENT" : "";
                String blockId = currentSubComponentIdx >= 0 && blockType.equals("CODE")
                    ? String.format("[P:%d,R:%d,C:%d,S:%d]", currentPanelIdx, currentRendererIdx, currentComponentIdx, currentSubComponentIdx)
                    : String.format("[P:%d,R:%d,C:%d]", currentPanelIdx, currentRendererIdx, currentComponentIdx);
                logger.debug("Block {} ({}): {} hit(s) - {}{}",
                    blockId, blockType, blockMatchCount, blockPreview, marker);
                blockMatchCount = 0;
            }

            if (newBlock) {
                currentPanelIdx = match.panelIndex();
                currentRendererIdx = match.rendererIndex();
                currentComponentIdx = match.componentVisualOrderInRenderer();
                currentSubComponentIdx = switch (match) {
                    case CodeSearchMatch codeMatch -> codeMatch.subComponentIndex();
                    case MarkdownSearchMatch ignored -> -1;
                };
                blockType = switch (match) {
                    case CodeSearchMatch ignored -> "CODE";
                    case MarkdownSearchMatch ignoredMD -> "MARKDOWN";
                };
                blockFirstMatchIdx = i;
                totalBlocksWithMatches++;

                // Get block preview
                blockPreview = switch (match) {
                    case MarkdownSearchMatch ignoredMD when match.actualUiComponent() instanceof JEditorPane editor -> {
                        String text = editor.getText();
                        // Strip HTML tags for preview
                        text = text.replaceAll("<[^>]+>", "").trim();
                        yield text.substring(0, Math.min(50, text.length())) + (text.length() > 50 ? "..." : "");
                    }
                    case CodeSearchMatch ignoredCode when match.actualUiComponent() instanceof RSyntaxTextArea textArea -> {
                        String text = textArea.getText();
                        yield text.substring(0, Math.min(50, text.length())).replace("\n", " ") + (text.length() > 50 ? "..." : "");
                    }
                    default -> "";
                };
            }

            blockMatchCount++;
        }

        // Don't forget the last block
        if (!allMatches.isEmpty()) {
            boolean isCurrentBlock = currentMatchIndex >= blockFirstMatchIdx &&
                                   currentMatchIndex < blockFirstMatchIdx + blockMatchCount;
            String marker = isCurrentBlock ? " <<<< CURRENT" : "";
            String blockId = currentSubComponentIdx >= 0
                ? String.format("[P:%d,R:%d,C:%d,S:%d]", currentPanelIdx, currentRendererIdx, currentComponentIdx, currentSubComponentIdx)
                : String.format("[P:%d,R:%d,C:%d]", currentPanelIdx, currentRendererIdx, currentComponentIdx);
            logger.debug("Block {} ({}): {} hit(s) - {}{}",
                blockId, blockType, blockMatchCount, blockPreview, marker);
        }

        logger.debug("\nTotal blocks with matches: {}", totalBlocksWithMatches);
        logger.debug("=== End Search Results ===\n");
    }

    /**
     * Prints all blocks in the document.
     */
    public void printAllBlocks(List<MarkdownOutputPanel> panels) {
        if (!debugEnabled) return;

        logger.debug("\n--- All blocks in document ---");
        // var blockCounter = new AtomicInteger(0);

//        for (int panelIdx = 0; panelIdx < panels.size(); panelIdx++) {
//            MarkdownOutputPanel panel = panels.get(panelIdx);
//            List<IncrementalBlockRenderer> renderers = panel.renderers().toList();
//
//            for (int rendererIdx = 0; rendererIdx < renderers.size(); rendererIdx++) {
//                IncrementalBlockRenderer renderer = renderers.get(rendererIdx);
//                JComponent rendererRoot = renderer.getRoot();
//                Component[] componentsInRenderer = rendererRoot.getComponents();
//
//                // Recursively traverse all components
//                for (int compIdx = 0; compIdx < componentsInRenderer.length; compIdx++) {
//                    Component comp = componentsInRenderer[compIdx];
//                    printComponentHierarchy(comp, panelIdx, rendererIdx, compIdx, 0, blockCounter);
//                }
//            }
//        }

        // logger.debug("Total blocks in document: {}", blockCounter.get());
    }

    private void printComponentHierarchy(Component comp, int panelIdx, int rendererIdx, int compIdx, int depth, AtomicInteger blockCounter) {
        String indent = "  ".repeat(depth);
        String blockType = "";
        String blockPreview = "";
        boolean isContentBlock = false;

        if (comp instanceof JEditorPane editor) {
            blockType = "MARKDOWN";
            String text = editor.getText();
            text = text.replaceAll("<[^>]+>", "").trim();
            if (!text.isEmpty()) {
                blockPreview = text.substring(0, Math.min(50, text.length())) + (text.length() > 50 ? "..." : "");
                isContentBlock = true;
            }
        } else if (comp instanceof JLabel jLabel) {
            blockType = "MARKDOWN(Label)";
            String text = jLabel.getText();
            if (text != null) {
                text = text.replaceAll("<[^>]+>", "").trim();
                if (!text.isEmpty()) {
                    blockPreview = text.substring(0, Math.min(50, text.length())) + (text.length() > 50 ? "..." : "");
                    isContentBlock = true;
                }
            }
        } else if (comp instanceof RSyntaxTextArea textArea) {
            blockType = "CODE";
            String text = textArea.getText();
            if (text != null && !text.trim().isEmpty()) {
                blockPreview = text.substring(0, Math.min(50, text.length())).replace("\n", " ") +
                             (text.length() > 50 ? "..." : "");
                isContentBlock = true;
            }
        }

        if (isContentBlock) {
            int blockNum = blockCounter.incrementAndGet();
            if (depth == 0) {
                logger.debug("{}Block {} [P:{},R:{},C:{}] ({}): {}",
                    indent, blockNum, panelIdx, rendererIdx, compIdx, blockType, blockPreview);
            } else {
                logger.debug("{}Block {} [P:{},R:{},C:{}] ({}) [nested-depth:{}]: {}",
                    indent, blockNum, panelIdx, rendererIdx, compIdx, blockType, depth, blockPreview);
            }
        }

        // Recursively check children
        if (comp instanceof Container container) {
            Component[] children = container.getComponents();
            if (children.length > 0) {
                if (!isContentBlock && depth == 0) {
                    // Show container info only for top-level containers that don't have content
                    logger.debug("{}[P:{},R:{},C:{}] CONTAINER ({}) with {} children:",
                        indent, panelIdx, rendererIdx, compIdx, comp.getClass().getSimpleName(), children.length);
                }

                for (Component child : children) {
                    printComponentHierarchy(child, panelIdx, rendererIdx, compIdx, depth + 1, blockCounter);
                }
            }
        }
    }

    /**
     * Marker information record for detailed debugging.
     */
    public record MarkerInfo(
        int markerId,
        String beforeContext,
        String content,
        String afterContext,
        int position
    ) {
        public MarkerInfo(int markerId, String beforeContext, String content, String afterContext, int position) {
            this.markerId = markerId;
            this.beforeContext = beforeContext.trim();
            this.content = content.trim();
            this.afterContext = afterContext.trim();
            this.position = position;
        }

        @Override
        public String toString() {
            return String.format("[%d] ...%s{%s}%s...",
                               markerId, beforeContext, content, afterContext);
        }
    }
}