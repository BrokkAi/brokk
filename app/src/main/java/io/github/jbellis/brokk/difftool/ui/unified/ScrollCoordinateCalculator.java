package io.github.jbellis.brokk.difftool.ui.unified;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * Utility class for calculating scroll-related coordinates in unified diff display. Contains pure mathematical
 * functions that can be tested headlessly without GUI components.
 *
 * <p>This class extracts the coordinate transformation logic from GUI painting code to make it testable and reusable.
 */
public final class ScrollCoordinateCalculator {

    private ScrollCoordinateCalculator() {} // Utility class

    /**
     * Calculate the text area coordinate range that corresponds to the visible region in a row header component.
     *
     * @param clipBounds The clipping bounds of the row header component
     * @param viewPosition The current viewport position
     * @return Point where x=startY and y=endY in text area coordinates
     */
    public static Point calculateTextAreaCoordinateRange(Rectangle clipBounds, Point viewPosition) {
        // For row header in JScrollPane, map our visible region to text area coordinates
        int textAreaStartY = clipBounds.y + viewPosition.y;
        int textAreaEndY = textAreaStartY + clipBounds.height;

        return new Point(textAreaStartY, textAreaEndY);
    }

    /**
     * Convert a text area line Y coordinate to row header coordinate space.
     *
     * @param textAreaLineY The Y coordinate of a line in text area space
     * @param viewPosition The current viewport position
     * @return The Y coordinate in row header space
     */
    public static int convertToRowHeaderCoordinate(int textAreaLineY, Point viewPosition) {
        return textAreaLineY - viewPosition.y;
    }

    /**
     * Determine if a line is visible within the current paint region.
     *
     * @param lineY The Y coordinate of the line start
     * @param lineHeight The height of the line
     * @param clipBounds The clipping bounds to check against
     * @return true if the line is at least partially visible
     */
    public static boolean isLineVisible(int lineY, int lineHeight, Rectangle clipBounds) {
        // Line is visible if it overlaps with the clip bounds vertically
        int lineEndY = lineY + lineHeight;
        int clipEndY = clipBounds.y + clipBounds.height;

        // Check for vertical overlap: line overlaps if it's not completely above or below
        return !(lineEndY <= clipBounds.y || lineY >= clipEndY);
    }

    /**
     * Validate that coordinates are within reasonable bounds to avoid painting errors.
     *
     * @param lineY The line Y coordinate
     * @param lineHeight The line height
     * @param componentHeight The total height of the component
     * @return true if coordinates are valid for painting
     */
    public static boolean areCoordinatesValid(int lineY, int lineHeight, int componentHeight) {
        // Basic sanity checks
        if (lineHeight <= 0) {
            return false;
        }

        // Allow some tolerance for lines slightly outside component bounds
        // as they might be partially visible
        int tolerance = lineHeight * 2;
        return lineY > -tolerance && lineY < componentHeight + tolerance;
    }

    /**
     * Calculate the effective paint region considering viewport constraints. This can be used to optimize painting by
     * skipping lines that are definitely not visible.
     *
     * @param clipBounds The component's clip bounds
     * @param viewPosition The viewport position
     * @param componentHeight The total height of the component
     * @return Rectangle representing the effective paint region
     */
    public static Rectangle calculateEffectivePaintRegion(
            Rectangle clipBounds, Point viewPosition, int componentHeight) {
        // The effective region is the intersection of clip bounds and component bounds
        int startY = Math.max(0, clipBounds.y);
        int endY = Math.min(componentHeight, clipBounds.y + clipBounds.height);
        int height = Math.max(0, endY - startY);

        return new Rectangle(clipBounds.x, startY, clipBounds.width, height);
    }

    /** Data class representing a range of visible lines. */
    public static class VisibleLineRange {
        private final int startLine;
        private final int endLine;
        private final boolean isValid;

        public VisibleLineRange(int startLine, int endLine, boolean isValid) {
            this.startLine = startLine;
            this.endLine = endLine;
            this.isValid = isValid;
        }

        public int getStartLine() {
            return startLine;
        }

        public int getEndLine() {
            return endLine;
        }

        public boolean isValid() {
            return isValid;
        }

        public int getLineCount() {
            return isValid ? Math.max(0, endLine - startLine + 1) : 0;
        }

        @Override
        public String toString() {
            return String.format(
                    "VisibleLineRange{lines=%d-%d, count=%d, valid=%s}", startLine, endLine, getLineCount(), isValid);
        }
    }

    /**
     * Create a VisibleLineRange with validation.
     *
     * @param startLine The first visible line
     * @param endLine The last visible line
     * @return VisibleLineRange with validation
     */
    public static VisibleLineRange createVisibleLineRange(int startLine, int endLine) {
        boolean isValid = startLine >= 0 && endLine >= startLine;
        return new VisibleLineRange(startLine, endLine, isValid);
    }
}
