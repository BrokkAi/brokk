package io.github.jbellis.brokk.difftool.ui;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.utils.Colors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.text.BadLocationException;

/**
 * Unified highlighter for diff deltas, replacing the duplicate HighlightOriginal
 * and HighlightRevised inner classes in FilePanel.
 */
public final class DeltaHighlighter {
    private static final Logger logger = LogManager.getLogger(DeltaHighlighter.class);

    private DeltaHighlighter() {} // Utility class

    public static void highlight(FilePanel panel, AbstractDelta<String> delta, boolean originalSide) {
        @Nullable BufferDocumentIF bufferDocument = panel.getBufferDocument();
        if (bufferDocument == null) {
            logger.trace("Skipping highlight: bufferDocument is null for {} side",
                         originalSide ? "original" : "revised");
            return;
        }

        // Use the utility to get the appropriate chunk with fallback logic
        @Nullable Chunk<String> chunk = DiffHighlightUtil.getChunkForHighlight(delta, originalSide);
        if (chunk == null) {
            logger.debug("Skipping highlight: chunk is null for {} side, delta type {}",
                        originalSide ? "original" : "revised", delta.getType());
            return;
        }

        var fromOffset = bufferDocument.getOffsetForLine(chunk.getPosition());
        if (fromOffset < 0) {
            logger.warn("Invalid fromOffset {} for line {} on {} side",
                       fromOffset, chunk.getPosition(), originalSide ? "original" : "revised");
            return;
        }

        // Clamp the end line to document bounds to prevent highlighting to document end
        int endLineNumber = chunk.getPosition() + chunk.size();
        int maxLines = bufferDocument.getNumberOfLines();

        // For DELETE fallback on revised side, validate that position is reasonable
        if (!originalSide && chunk.size() == 0) {
            // This is a DELETE fallback - ensure position doesn't exceed revised document
            if (chunk.getPosition() >= maxLines) {
                logger.debug("Skipping DELETE fallback highlight: position {} >= maxLines {} on revised side",
                           chunk.getPosition(), maxLines);
                return; // Skip highlighting that would be way out of bounds
            }
            // For DELETE fallback, highlight just the position line, not beyond document
            endLineNumber = Math.min(chunk.getPosition() + 1, maxLines);
        } else if (endLineNumber > maxLines) {
            logger.debug("Clamping highlight end line {} to document max {} on {} side",
                        endLineNumber, maxLines, originalSide ? "original" : "revised");
            endLineNumber = maxLines;
        }

        var toOffset = bufferDocument.getOffsetForLine(endLineNumber);
        if (toOffset < 0) {
            logger.warn("Invalid toOffset {} for line {} on {} side",
                       toOffset, endLineNumber, originalSide ? "original" : "revised");
            return;
        }

        // Check if chunk is effectively empty (zero size)
        boolean isEmpty = (chunk.size() == 0);

        // Check if we're at document end with a newline
        boolean isEndAndNewline = isEndAndLastNewline(bufferDocument, toOffset);

        // Get the appropriate painter
        var isDark = panel.getDiffPanel().isDarkTheme();
        var painter = switch (delta.getType()) {
            case INSERT -> {
                var color = Colors.getAdded(isDark);
                yield isEmpty ? new JMHighlightPainter.JMHighlightLinePainter(color)
                    : isEndAndNewline ? new JMHighlightPainter.JMHighlightNewLinePainter(color)
                    : new JMHighlightPainter(color);
            }
            case DELETE -> {
                var color = Colors.getDeleted(isDark);
                yield isEmpty ? new JMHighlightPainter.JMHighlightLinePainter(color)
                    : isEndAndNewline ? new JMHighlightPainter.JMHighlightNewLinePainter(color)
                    : new JMHighlightPainter(color);
            }
            case CHANGE -> {
                var color = Colors.getChanged(isDark);
                yield isEndAndNewline ? new JMHighlightPainter.JMHighlightNewLinePainter(color)
                    : new JMHighlightPainter(color);
            }
            case EQUAL -> throw new IllegalArgumentException("EQUAL deltas should not be highlighted");
        };

        // Apply the highlight
        try {
            logger.debug("Adding highlight: chunk pos={}, size={}, fromOffset={}, toOffset={}, side={}",
                        chunk.getPosition(), chunk.size(), fromOffset, toOffset,
                        originalSide ? "original" : "revised");
            panel.getHighlighter().addHighlight(JMHighlighter.LAYER0, fromOffset, toOffset, painter);
        } catch (BadLocationException ex) {
            throw new RuntimeException("Error adding highlight at offset " + fromOffset +
                                     " to " + toOffset + " on " +
                                     (originalSide ? "original" : "revised") + " side", ex);
        }
    }

    /**
     * Check if the last character is a newline and if offset is at document end.
     */
    private static boolean isEndAndLastNewline(BufferDocumentIF bufferDocument, int toOffset) {
        try {
            var doc = bufferDocument.getDocument();
            var docLen = doc.getLength();
            int endOffset = toOffset - 1;
            if (endOffset < 0 || endOffset >= docLen) {
                return false;
            }
            // If the final character is a newline & chunk touches doc-end
            boolean lastCharIsNL = "\n".equals(doc.getText(endOffset, 1));
            return (endOffset == docLen - 1) && lastCharIsNL;
        } catch (BadLocationException e) {
            throw new RuntimeException("Bad location accessing document text", e);
        }
    }
}
