package io.github.jbellis.brokk.gui.visualize;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Renders the co-change Graph with pan-and-zoom.
 * - Edges drawn first; stroke width scales with edge weight.
 * - Nodes drawn after; radius scales with file size (area proportional to bytes; 100 KiB -> 50 px diameter).
 * - Mouse drag to pan, mouse wheel to zoom centered at cursor.
 */
public class CoChangeGraphPanel extends JPanel {
    private static final Logger logger = LogManager.getLogger(CoChangeGraphPanel.class);

    // Render configuration
    private static final Color EDGE_COLOR = new Color(80, 120, 200, 180);
    private static final Color NODE_FILL = new Color(60, 60, 60);
    private static final Color NODE_OUTLINE = new Color(240, 240, 240);
    private static final float EDGE_MIN_WIDTH = 1.0f;
    private static final float EDGE_MAX_WIDTH = 6.0f;

    // File-size → radius mapping:
    // Target: 100 KiB -> diameter ~ 50 px -> radius ~ 25 px, with area proportional to bytes.
    private static final double REFERENCE_BYTES = 102400.0; // 100 KiB
    private static final double REFERENCE_RADIUS = 25.0;    // px
    private static final double RADIUS_SCALE = REFERENCE_RADIUS / Math.sqrt(REFERENCE_BYTES);
    private static final double MIN_RADIUS = 4.0;
    private static final double MAX_RADIUS = 60.0;

    // View transform for pan/zoom
    private final AffineTransform viewTx = new AffineTransform();

    // Graph data; never null
    private volatile Graph graph = new Graph(Map.of(), Map.of());

    // Mouse state for panning
    private @Nullable Point lastDragPoint;

    // File size cache (avoid repeated IO during painting)
    private final Map<ProjectFile, Long> fileSizeCache = new ConcurrentHashMap<>();

    public CoChangeGraphPanel() {
        setOpaque(true);
        setBackground(Color.WHITE);
        installInteractions();
    }

    public void setGraph(Graph g) {
        assert SwingUtilities.isEventDispatchThread() : "setGraph must be called on EDT";
        this.graph = g;
        repaint();
    }

    public Graph getGraph() {
        return graph;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(800, 600);
    }

    @Override
    protected void paintComponent(Graphics gRaw) {
        super.paintComponent(gRaw);
        var g2 = (Graphics2D) gRaw;

        // Quality hints
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // Apply pan/zoom transform
        var oldTx = g2.getTransform();
        g2.transform(viewTx);

        var localGraph = this.graph;

        // Draw edges first
        g2.setColor(EDGE_COLOR);
        for (var e : localGraph.edges.values()) {
            var na = localGraph.nodes.get(e.a());
            var nb = localGraph.nodes.get(e.b());
            if (na == null || nb == null) {
                continue;
            }
            float width = edgeStrokeWidth(e.weight());
            var old = g2.getStroke();
            g2.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int) Math.round(na.x), (int) Math.round(na.y), (int) Math.round(nb.x), (int) Math.round(nb.y));
            g2.setStroke(old);
        }

        // Draw nodes
        for (var n : localGraph.nodes.values()) {
            double r = nodeRadius(n);
            double d = r * 2.0;
            double x = n.x - r;
            double y = n.y - r;

            var circle = new Ellipse2D.Double(x, y, d, d);
            g2.setColor(NODE_FILL);
            g2.fill(circle);
            g2.setColor(NODE_OUTLINE);
            g2.draw(circle);
        }

        // Reset transform to draw UI overlays in screen space
        g2.setTransform(oldTx);

        // Show placeholder if empty
        if (localGraph.nodes.isEmpty()) {
            drawCenteredMessage(g2, "No co-change data to display");
        }
    }

    private void drawCenteredMessage(Graphics2D g2, String msg) {
        var fm = g2.getFontMetrics();
        int tw = fm.stringWidth(msg);
        int th = fm.getAscent();
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        g2.setColor(new Color(0, 0, 0, 120));
        g2.drawString(msg, cx - tw / 2, cy + th / 2);
    }

    private float edgeStrokeWidth(int weight) {
        // Use logarithmic scaling to prevent overly thick edges
        float w = (float) (EDGE_MIN_WIDTH + Math.log1p(Math.max(0, weight)));
        return Math.max(EDGE_MIN_WIDTH, Math.min(EDGE_MAX_WIDTH, w));
    }

    private double nodeRadius(Node n) {
        if (n.radiusPx > 0.0) {
            return clamp(n.radiusPx, MIN_RADIUS, MAX_RADIUS);
        }
        // Compute from file size if available
        var pf = n.file;
        long bytes = fileSizeCache.computeIfAbsent(pf, this::safeFileSize);
        if (bytes <= 0) {
            return MIN_RADIUS;
        }
        double r = RADIUS_SCALE * Math.sqrt((double) bytes);
        return clamp(r, MIN_RADIUS, MAX_RADIUS);
    }

    private long safeFileSize(ProjectFile pf) {
        try {
            return Files.size(pf.absPath());
        } catch (IOException e) {
            logger.debug("Failed to read size for {}: {}", pf, e.getMessage());
            return 0L;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void installInteractions() {
        var mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastDragPoint = e.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                lastDragPoint = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastDragPoint != null) {
                    int dx = e.getX() - lastDragPoint.x;
                    int dy = e.getY() - lastDragPoint.y;
                    pan(dx, dy);
                    lastDragPoint = e.getPoint();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown()) {
                    // Allow Ctrl+Wheel to be handled by other handlers (e.g., text zoom). Skip here.
                    return;
                }
                double wheel = e.getPreciseWheelRotation();
                // Zoom factor per notch
                double factor = Math.pow(1.1, -wheel);
                zoomAt(e.getPoint(), factor);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    private void pan(double dx, double dy) {
        // Translate in screen space
        viewTx.translate(dx, dy);
        repaint();
    }

    private void zoomAt(Point anchorScreen, double scale) {
        // Keep the point under the cursor fixed during zoom
        viewTx.translate(anchorScreen.x, anchorScreen.y);
        viewTx.scale(scale, scale);
        viewTx.translate(-anchorScreen.x, -anchorScreen.y);
        repaint();
    }

    // Optional API to reset view
    public void resetView() {
        assert SwingUtilities.isEventDispatchThread() : "resetView must be called on EDT";
        viewTx.setToIdentity();
        repaint();
    }
}
