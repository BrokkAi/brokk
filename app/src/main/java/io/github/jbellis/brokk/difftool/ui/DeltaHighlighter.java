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
            logger.warn("Skipping highlight: chunk is null for {} side, delta type {}",
                       originalSide ? "original" : "revised", delta.getType());
            return;
        }

        var fromOffset = bufferDocument.getOffsetForLine(chunk.getPosition());
        if (fromOffset < 0) {
            logger.warn("Invalid fromOffset {} for line {} on {} side",
                       fromOffset, chunk.getPosition(), originalSide ? "original" : "revised");
            return;
        }

        var toOffset = bufferDocument.getOffsetForLine(chunk.getPosition() + chunk.size());
        if (toOffset < 0) {
            logger.warn("Invalid toOffset {} for line {} on {} side",
                       toOffset, chunk.getPosition() + chunk.size(), originalSide ? "original" : "revised");
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
            panel.getHighlighter().addHighlight(JMHighlighter.LAYER0, fromOffset, toOffset, painter);
        } catch (BadLocationException ex) {
            throw new RuntimeException("Error adding highlight at offset " + fromOffset +
                                     " size " + toOffset + " on " +
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
