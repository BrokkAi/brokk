package io.github.jbellis.brokk.difftool.scroll;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import io.github.jbellis.brokk.difftool.doc.BufferDocumentIF;
import io.github.jbellis.brokk.difftool.ui.BufferDiffPanel;
import io.github.jbellis.brokk.difftool.ui.FilePanel;
import io.github.jbellis.brokk.difftool.utils.Colors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import io.github.jbellis.brokk.gui.SwingUtil;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.GeneralPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * DiffScrollComponent is responsible for painting the \"curved lines\" or \"connectors\"
 * between the left (original) and right (revised) texts. It also hooks mouse
 * and wheel events to navigate diffs or apply merges.
 *
 * This version now uses AbstractDelta<String> from java-diff-utils
 * instead of the old JMDelta references.
 */
public class DiffScrollComponent extends JComponent implements ChangeListener
{
    private static final Logger logger = LogManager.getLogger(DiffScrollComponent.class);
    private final BufferDiffPanel diffPanel;
    private final int fromPanelIndex;
    private final int toPanelIndex;

    // Holds clickable shapes on the screen, each mapped to a \"command\"
    private List<Command> commands = new ArrayList<>();

    // We keep track of antialias settings for painting
    @Nullable private Object antiAlias = RenderingHints.VALUE_ANTIALIAS_DEFAULT;

    // For controlling how the curves are drawn
    private int curveType;
    private boolean drawCurves;

    // SHIFT is sometimes used to append or do a different style of change
    // when the user clicks a triangle. (Optional usage)
    boolean shift; // TODO: Consider making this private and controlled via methods

    // Tracks which delta the mouse is currently over
    @Nullable private AbstractDelta<?> currentlyHoveredDelta = null;

    /**
     * Constructs the DiffScrollComponent that draws connections between
     * diffPanel.getFilePanel(fromPanelIndex) and diffPanel.getFilePanel(toPanelIndex).
     */
    public DiffScrollComponent(BufferDiffPanel diffPanel, int fromPanelIndex, int toPanelIndex)
    {
        this.diffPanel = diffPanel;
        this.fromPanelIndex = fromPanelIndex;
        this.toPanelIndex = toPanelIndex;

        // Listen to viewport changes so we can repaint if user scrolls
        FilePanel fromP = requireNonNull(getFromPanel(), "FromPanel must be available in DiffScrollComponent constructor");
        FilePanel toP = requireNonNull(getToPanel(), "ToPanel must be available in DiffScrollComponent constructor");
        fromP.getScrollPane().getViewport().addChangeListener(this);
        toP.getScrollPane().getViewport().addChangeListener(this);

        // Mouse interactions
        addMouseListener(getMouseListener());
        addMouseMotionListener(getMouseMotionListener());
        addMouseWheelListener(getMouseWheelListener());

        initSettings();
    }

    /**
     * Allow user to pick which \"style\" of curve or line is drawn.
     */
    public void setCurveType(int curveType)
    {
        this.curveType = curveType;
    }

    public int getCurveType()
    {
        return curveType;
    }

    public boolean isDrawCurves()
    {
        return drawCurves;
    }

    public void setDrawCurves(boolean drawCurves)
    {
        this.drawCurves = drawCurves;
    }

    private void initSettings()
    {
        setDrawCurves(true);
        setCurveType(1); // Default to cubic curves
    }

    /**
     * Called when either viewport changes (vertical or horizontal scroll).
     */
    @Override
    public void stateChanged(ChangeEvent event)
    {
        repaint();
    }

    /**
     * MouseListener for click events. We let the user click on shapes to do merges, etc.
     */
    private MouseListener getMouseListener()
    {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent me)
            {
                // Check for shift key press (consider moving this to Command execution)
                shift = (me.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
                requestFocus();
                // Execute a command if the user clicked inside a shape
                executeCommand(me.getX(), me.getY());
            }
        };
    }

    /**
     * If user clicks somewhere, see if it hits any \"command\" shape and run it.
     */
    public void executeCommand(double x, double y)
    {
        // Iterate in reverse order so topmost shapes are checked first
        for (int i = commands.size() - 1; i >= 0; i--) {
            var command = commands.get(i);
            if (command.contains(x, y)) {
                command.execute();
                return;
            }
        }
    }

    @Override
    public void paintComponent(Graphics g)
    {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        Rectangle clipBounds = g2.getClipBounds(); // Use the actual clip bounds

        // Store anti-alias state
        antiAlias = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        setAntiAlias(g2);

        // Fill background
        g2.setColor(UIManager.getColor("Panel.background")); // Use theme background
        g2.fill(clipBounds);

        paintDiffs(g2);

        // Restore anti-alias state
        resetAntiAlias(g2);
    }

    /**
     * The main method that draws the connecting curves/shapes for each delta from left to right.
     * Adapts the old logic to work with AbstractDelta<String>.
     */
    private void paintDiffs(Graphics2D g2)
    {
        var bounds = getBounds(); // Use component bounds for drawing calculations
        g2.setClip(null); // Work with full component area, clipping handled internally

        // Retrieve the patch from BufferDiffPanel
        var patch = diffPanel.getPatch();
        if (patch == null || patch.getDeltas().isEmpty()) {
            return;
        }
        // We'll store clickable shapes in commands
        commands = new ArrayList<>();

        // --- Get necessary info for both panels ---
        FilePanel fromPanel = getFromPanel();
        FilePanel toPanel = getToPanel();

        // If either panel is null (should not happen if constructor assertions hold), abort painting.
        if (fromPanel == null || toPanel == null) {
            logger.warn("paintDiffs called but FilePanel(s) are null. FromPanel: {}, ToPanel: {}", fromPanel, toPanel);
            return;
        }

        var viewportFrom = fromPanel.getScrollPane().getViewport();
        var editorFrom = fromPanel.getEditor();
        var bdFrom = fromPanel.getBufferDocument();

        var viewportTo = toPanel.getScrollPane().getViewport();
        var editorTo = toPanel.getEditor();
        var bdTo = toPanel.getBufferDocument();

        if (bdFrom == null || bdTo == null) {
            return; // Need both documents
        }

        Rectangle viewRectFrom = viewportFrom.getViewRect();
        Rectangle viewRectTo = viewportTo.getViewRect();

        // Calculate first/last visible lines (0-based for consistency with Chunk)
        int firstLineFrom = getVisibleLine(editorFrom, bdFrom, viewRectFrom.y);
        int lastLineFrom = getVisibleLine(editorFrom, bdFrom, viewRectFrom.y + viewRectFrom.height);
        int firstLineTo = getVisibleLine(editorTo, bdTo, viewRectTo.y);
        int lastLineTo = getVisibleLine(editorTo, bdTo, viewRectTo.y + viewRectTo.height);

        try {
            // For each delta, draw a shape connecting original chunk (on the left) to revised chunk (on the right).
            for (var delta : patch.getDeltas()) {
                var original = delta.getSource();
                var revised = delta.getTarget();

                // --- Basic Visibility Check ---
                // Skip if delta is entirely *above* the visible area of *both* panes
                if (original.getPosition() + original.size() < firstLineFrom
                        && revised.getPosition() + revised.size() < firstLineTo) {
                    continue;
                }
                // Stop if delta is entirely *below* the visible area of *both* panes
                if (original.getPosition() > lastLineFrom
                        && revised.getPosition() > lastLineTo) {
                    break;
                }

                var selected = Objects.equals(delta, diffPanel.getSelectedDelta());
                var hovered = Objects.equals(delta, currentlyHoveredDelta); // Check against the component's state

                // --- Determine Color ---
                Color color = switch (delta.getType()) {
                    case INSERT -> Colors.getAdded(diffPanel.isDarkTheme());
                    case DELETE -> Colors.getDeleted(diffPanel.isDarkTheme());
                    case CHANGE -> Colors.getChanged(diffPanel.isDarkTheme());
                    case EQUAL -> throw new IllegalStateException("Equal delta should not be painted here");
                };
                Color darkerColor = color.darker(); // Use a darker shade for borders

                // --- Calculate Screen Coordinates for Chunks ---
                // Calculate the screen rectangle for the original chunk (left panel)
                RectInfo leftInfo = computeRectInfo(editorFrom, bdFrom, viewRectFrom, original);
                // Calculate the screen rectangle for the revised chunk (right panel)
                RectInfo rightInfo = computeRectInfo(editorTo, bdTo, viewRectTo, revised);

                // Width of the colored sidebar area
                final int sideBarWidth = 10;
                // X coordinate for the left sidebar's right edge
                int xLeftEdge = sideBarWidth;
                // X coordinate for the right sidebar's left edge
                int xRightEdge = bounds.width - sideBarWidth;

                // Y coordinates and heights adjusted relative to the viewport
                int yLeft = leftInfo.y();
                int hLeft = leftInfo.height();
                int yRight = rightInfo.y();
                int hRight = rightInfo.height();

                // Ensure minimum height for drawing connections, even for zero-size chunks
                hLeft = Math.max(hLeft, 1);
                hRight = Math.max(hRight, 1);

                // --- Draw Connecting Shape ---
                GeneralPath curveShape = null;
                Polygon simpleShape = null;

                if (isDrawCurves()) {
                    // Define points for curve drawing
                    int curveX1 = xLeftEdge; // Left edge end
                    int curveY1 = yLeft;     // Left top y
                    int curveX4 = xLeftEdge; // Left edge end
                    int curveY4 = yLeft + hLeft; // Left bottom y

                    int curveX2 = xRightEdge; // Right edge start
                    int curveY2 = yRight;     // Right top y
                    int curveX3 = xRightEdge; // Right edge start
                    int curveY3 = yRight + hRight; // Right bottom y

                    curveShape = new GeneralPath();
                    if (getCurveType() == 0) { // Simple polygon lines
                        curveShape.moveTo(curveX1, curveY1);
                        curveShape.lineTo(curveX2, curveY2);
                        curveShape.lineTo(curveX3, curveY3);
                        curveShape.lineTo(curveX4, curveY4);
                        curveShape.closePath();
                    } else if (getCurveType() == 1) { // Cubic curves (original style 1)
                        int posyOrg = original.size() > 0 ? 0 : 1; // Small offset for 0-size
                        int posyRev = revised.size() > 0 ? 0 : 1;
                        float midX = curveX1 + ((float) (curveX2 - curveX1) / 2);
                        curveShape.moveTo(curveX1, curveY1 - posyOrg);
                        curveShape.curveTo(midX + 5, curveY1, midX + 5, curveY2, curveX2, curveY2 - posyRev);
                        curveShape.lineTo(curveX3, curveY3 + posyRev);
                        curveShape.curveTo(midX - 5, curveY3, midX - 5, curveY4, curveX4, curveY4 + posyOrg);
                        curveShape.closePath();
                    } else { // Cubic curves (original style 2)
                        curveShape.moveTo(curveX1, curveY1 - 2);
                        curveShape.curveTo(curveX2 + 10, curveY1, curveX1 + 10, curveY2, curveX2, curveY2 - 2);
                        curveShape.lineTo(curveX3, curveY3 + 2);
                        curveShape.curveTo(curveX4 - 10, curveY3, curveX3 - 10, curveY4, curveX4, curveY4 + 2);
                        curveShape.closePath();
                    }

                    g2.setColor(color);
                    g2.fill(curveShape);
                    g2.setColor(darkerColor);
                    g2.draw(curveShape);
                } else { // Draw simple connecting lines (no curves)
                    // Draw rectangles on sides first
                    g2.setColor(color);
                    g2.fillRect(0, yLeft, sideBarWidth, hLeft); // Left sidebar rect
                    g2.fillRect(xRightEdge, yRight, sideBarWidth, hRight); // Right sidebar rect

                    // Draw border lines for sidebars
                    g2.setColor(darkerColor);
                    g2.drawRect(0, yLeft, sideBarWidth, hLeft);
                    g2.drawRect(xRightEdge, yRight, sideBarWidth, hRight);

                    // Draw connecting lines between midpoints (approx)
                    int midYLeft = yLeft + hLeft / 2;
                    int midYRight = yRight + hRight / 2;

                    // Create a simple polygon for clicking
                    simpleShape = new Polygon();
                    simpleShape.addPoint(xLeftEdge, midYLeft - 2); // Top left
                    simpleShape.addPoint(xRightEdge, midYRight - 2); // Top right
                    simpleShape.addPoint(xRightEdge, midYRight + 2); // Bottom right
                    simpleShape.addPoint(xLeftEdge, midYLeft + 2);  // Bottom left

                    // Draw the line connecting the two sidebar rects
                    setAntiAlias(g2); // Make lines look nicer
                    g2.setColor(darkerColor);
                    g2.drawLine(xLeftEdge, midYLeft, xRightEdge, midYRight);
                    resetAntiAlias(g2);
                }

                // --- Draw Selection Highlight ---
                final int selectionWidth = 5; // Width of the yellow selection indicator
                if (selected) {
                    g2.setColor(Color.yellow);
                    // Draw highlight on the left side
                    if (hLeft > 0) {
                        g2.fillRect(0, yLeft, selectionWidth, hLeft);
                    }
                    // Draw highlight on the right side
                    if (hRight > 0) {
                        g2.fillRect(bounds.width - selectionWidth, yRight, selectionWidth, hRight);
                    }
                    // Optionally draw a border around the selection highlight
                    g2.setColor(Color.yellow.darker());
                     if (hLeft > 0) {
                        g2.drawRect(0, yLeft, selectionWidth, hLeft);
                    }
                     if (hRight > 0) {
                         g2.drawRect(bounds.width - selectionWidth, yRight, selectionWidth, hRight);
                     }
                }

                // Merging is possible if the target document is not read-only.
                // The existence of a source is guaranteed by the BufferSource model.
                var canMergeRight = !bdTo.isReadonly();
                var canMergeLeft = !bdFrom.isReadonly();

                // Draw merge right command (triangle pointing right on left edge of right bar)
                if (canMergeRight) {
                    if (original.size() > 0 || revised.size() > 0) { // Show if there's something to merge
                        int triangleX = xRightEdge;
                        int triangleY = yRight + hRight / 2; // Center vertically
                        int scale = hovered ? 2 : 1; // Scale up on hover
                        Polygon mergeRightTriangle = createTriangle(triangleX, triangleY, scale, true); // Pointing right

                        setAntiAlias(g2);
                        g2.setColor(hovered ? Color.gray.brighter() : color);
                        g2.fillPolygon(mergeRightTriangle);
                        g2.setColor(hovered ? Color.darkGray : darkerColor);
                        g2.drawPolygon(mergeRightTriangle);
                        resetAntiAlias(g2);

                        // Add command for merging left-to-right (apply change to right panel)
                        commands.add(new DiffChangeCommand(mergeRightTriangle, delta, fromPanelIndex, toPanelIndex));
                    }
                }

                // Draw merge left command (triangle pointing left on right edge of left bar) - Symmetric logic
                 if (canMergeLeft) {
                     if (original.size() > 0 || revised.size() > 0) {
                        int triangleX = xLeftEdge;
                        int triangleY = yLeft + hLeft / 2;
                        int scale = hovered ? 2 : 1;
                        Polygon mergeLeftTriangle = createTriangle(triangleX, triangleY, scale, false); // Pointing left

                        setAntiAlias(g2);
                        g2.setColor(hovered ? Color.gray.brighter() : color);
                        g2.fillPolygon(mergeLeftTriangle);
                        g2.setColor(hovered ? Color.darkGray : darkerColor);
                        g2.drawPolygon(mergeLeftTriangle);
                        resetAntiAlias(g2);

                        // Add command for merging right-to-left (apply change to left panel)
                        commands.add(new DiffChangeCommand(mergeLeftTriangle, delta, toPanelIndex, fromPanelIndex));
                    }
                 }
            }
        } catch (BadLocationException ex) {
            // Log error or handle gracefully
            System.err.println("Error calculating view coordinates: " + ex.getMessage());
            // Consider painting an error message or indicator
        }
    }

    /** Helper to get 0-based line number for a given Y coordinate */
    private int getVisibleLine(JTextComponent editor, BufferDocumentIF doc, int y) {
        Point p = new Point(0, y); // X doesn't matter for line number
        int offset = editor.viewToModel2D(p);
        return (offset >= 0) ? doc.getLineForOffset(offset) : -1;
    }

    /** Record to hold calculated rectangle info */
    private record RectInfo(int y, int height) {}

    /**
     * Compute the bounding rectangle info (Y coordinate and height) of the lines
     * in 'chunk' for the given editor and document, relative to the viewport's origin.
     * Uses 0-based line numbers internally.
     */
    private RectInfo computeRectInfo(JTextComponent editor, BufferDocumentIF bd,
                                     Rectangle visibleRect, Chunk<String> chunk)
            throws BadLocationException
    {
        int startLine = chunk.getPosition(); // 0-based start line index
        int endLine = startLine + chunk.size(); // 0-based line *after* the chunk

        int startOffset = bd.getOffsetForLine(startLine);
        int endOffset = (chunk.size() == 0)
                ? startOffset // For zero-size chunks, use the start offset for both
                : bd.getOffsetForLine(endLine);

        // Handle potential invalid offsets (e.g., end of file)
        // If offset is invalid, use the offset at the end of the document
        int docEndOffset = editor.getDocument().getLength(); // Get length from the editor's document
        if (startOffset < 0) startOffset = docEndOffset;
        if (endOffset < 0) endOffset = docEndOffset;
        // Ensure endOffset is not before startOffset
        endOffset = Math.max(startOffset, endOffset);

        Rectangle startViewRect = SwingUtil.modelToView(editor, startOffset);
        Rectangle endViewRect = SwingUtil.modelToView(editor, endOffset);

        // Handle cases where modelToView might fail or return null
        if (startViewRect == null) {
            // Try getting view rect for the line before if possible
            if (startLine > 0) {
                startViewRect = SwingUtil.modelToView(editor, bd.getOffsetForLine(startLine - 1));
                if (startViewRect != null) {
                    // Estimate position based on previous line
                    startViewRect.y += startViewRect.height;
                }
            }
            // If still null, fallback to top of viewport
            if (startViewRect == null) startViewRect = new Rectangle(visibleRect.x, visibleRect.y, 1, 1);
        }
        if (endViewRect == null) {
             // If end is invalid, use start position + estimate or bottom of viewport
             if (startViewRect != null) {
                 // Estimate based on number of lines and start rect height
                 int estimatedHeight = chunk.size() * startViewRect.height;
                 endViewRect = new Rectangle(startViewRect.x, startViewRect.y + estimatedHeight, 1, 1);
             } else {
                 // Fallback if start was also null
                endViewRect = new Rectangle(visibleRect.x, visibleRect.y + visibleRect.height, 1, 1);
             }
        }


        // Calculate Y relative to the component's top (not viewport's)
        int y = startViewRect.y - visibleRect.y;
        int bottomY = endViewRect.y - visibleRect.y;

        // Calculate height based on start and end view rectangles
        int height = bottomY - y;

        // --- Clip coordinates to the visible viewport ---
        int viewTop = 0; // Relative top of the viewport is 0
        int viewBottom = visibleRect.height;

        // Adjust Y and Height if the chunk starts above the viewport
        if (y < viewTop) {
            height -= (viewTop - y); // Reduce height by the amount clipped above
            y = viewTop;            // Start drawing at the top edge
        }

        // Adjust Height if the chunk ends below the viewport
        if (y + height > viewBottom) {
            height = viewBottom - y; // Cap height at the bottom edge
        }

        // Ensure height is not negative after clipping
        height = Math.max(0, height);

        return new RectInfo(y, height);
    }


    /**
     * Creates a triangle shape for merge indicators.
     *
     * @param x X coordinate of the anchor point (tip for pointing left, base center for pointing right)
     * @param y Y coordinate of the anchor point (vertical center)
     * @param scale Scaling factor (e.g., 1 for normal, 2 for hover)
     * @param pointRight True if the triangle should point right, false to point left.
     * @return Polygon representing the triangle.
     */
    private Polygon createTriangle(int x, int y, int scale, boolean pointRight) {
        Polygon shape = new Polygon();
        int halfHeight = 4 * scale;
        int width = 6 * scale;

        if (pointRight) {
            // Pointing Right -> Anchor X is the left base center
            shape.addPoint(x, y - halfHeight);        // Top-left corner of base
            shape.addPoint(x + width, y);             // Tip pointing right
            shape.addPoint(x, y + halfHeight);        // Bottom-left corner of base
        } else {
            // Pointing Left -> Anchor X is the right base center
            shape.addPoint(x, y - halfHeight);        // Top-right corner of base
            shape.addPoint(x - width, y);             // Tip pointing left
            shape.addPoint(x, y + halfHeight);        // Bottom-right corner of base
        }

        return shape;
    }


    /**
     * Provide mouse-based selection or hover.
     */
    private MouseMotionListener getMouseMotionListener()
    {
        return new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e)
            {
                AbstractDelta<?> deltaUnderMouse = null;
                // Check commands to find which delta shape is under the mouse
                for (int i = commands.size() - 1; i >= 0; i--) { // Check topmost first
                    var cmd = commands.get(i);
                    if (cmd.contains(e.getX(), e.getY())) {
                        deltaUnderMouse = cmd.delta;
                        break; // Found the shape under the mouse
                    }
                }

                // If the hovered delta changed, update the state and repaint
                if (!Objects.equals(currentlyHoveredDelta, deltaUnderMouse)) {
                    currentlyHoveredDelta = deltaUnderMouse;
                    repaint();
                }
            }
        };
    }

    /**
     * Support mouse wheel to go to next or previous delta.
     */
    private MouseWheelListener getMouseWheelListener()
    {
        return me -> {
            // If wheel moves down, next delta; if up, previous delta
            diffPanel.toNextDelta(me.getWheelRotation() > 0);
            // No need to repaint here, toNextDelta usually triggers selection change -> repaint
        };
    }

    // --- Command Classes ---

    /**
     * Base class for clickable shapes. Holds the shape and the associated delta.
     */
    abstract static class Command
    {
        final Shape shape;
        final Rectangle bounds;
        final AbstractDelta<String> delta; // Link command to its delta

        Command(Shape shape, AbstractDelta<String> delta)
        {
            this.shape = shape;
            this.bounds = shape.getBounds();
            this.delta = delta;
        }

        boolean contains(double x, double y)
        {
            // Use shape.contains for accurate hit testing, especially for non-rectangular shapes
            return shape.contains(x, y);
        }

        public abstract void execute();
    }


    /**
     * A click command that merges changes between panels.
     */
    class DiffChangeCommand extends Command
    {
        private final int sourcePanelIndex;
        private final int targetPanelIndex;

        /**
         * @param shape Shape to click
         * @param delta Associated delta
         * @param sourcePanelIndex Index of the panel to copy *from*
         * @param targetPanelIndex Index of the panel to copy *to*
         */
        DiffChangeCommand(Shape shape, AbstractDelta<String> delta, int sourcePanelIndex, int targetPanelIndex)
        {
            super(shape, delta);
            this.sourcePanelIndex = sourcePanelIndex;
            this.targetPanelIndex = targetPanelIndex;
        }

        @Override
        public void execute()
        {
            diffPanel.setSelectedDelta(delta);
            // Use the source and target indexes provided during construction
            diffPanel.runChange(sourcePanelIndex, targetPanelIndex, shift);
            diffPanel.doSave(); // Consider if save should always happen here
        }
    }

    /**
     * A click command that deletes a chunk from one panel.
     */
    class DiffDeleteCommand extends Command
    {
        private final int panelIndexToDeleteFrom;

        /**
         * @param shape Shape to click (e.g., the 'X' cross)
         * @param delta Associated delta
         * @param panelIndexToDeleteFrom Index of the panel where deletion should occur
         */
        DiffDeleteCommand(Shape shape, AbstractDelta<String> delta, int panelIndexToDeleteFrom)
        {
            super(shape, delta);
            this.panelIndexToDeleteFrom = panelIndexToDeleteFrom;
        }

        @Override
        public void execute()
        {
            diffPanel.setSelectedDelta(delta);
            // Determine the 'other' panel index (assuming only two panels 0 and 1)
            int otherPanelIndex = (panelIndexToDeleteFrom == 0) ? 1 : 0;
            // Run delete operation targeting the specified panel
            diffPanel.runDelete(panelIndexToDeleteFrom, otherPanelIndex); // runDelete might need adjustment
            diffPanel.doSave(); // Consider if save should always happen here
        }
    }

    // --- Graphics Utilities ---

    private void setAntiAlias(Graphics2D g2)
    {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private void resetAntiAlias(Graphics2D g2)
    {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antiAlias); // Restore previous state
    }

    // --- Panel Accessors ---

    @Nullable
    private FilePanel getFromPanel()
    {
        // diffPanel.getFilePanel can return null if index is bad or panels not fully set up
        return diffPanel.getFilePanel(fromPanelIndex);
    }

    @Nullable
    private FilePanel getToPanel()
    {
        return diffPanel.getFilePanel(toPanelIndex);
    }
}
