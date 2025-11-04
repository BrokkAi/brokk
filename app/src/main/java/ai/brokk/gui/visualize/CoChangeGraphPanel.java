package ai.brokk.gui.visualize;

import ai.brokk.analyzer.ProjectFile;
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
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
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

    // Progressive LOD thresholds and zoom presets (5 levels: 10%, 20%, 30%, 50%, 100%)
    public static final double MIN_SCALE = 0.2;
    public static final double MAX_SCALE = 6.0;

    // Preset zoom levels: each 1.5x the previous, starting at 0.4
    public static final double TARGET_SCALE_10 = 0.4;
    public static final double TARGET_SCALE_20 = 0.6; // 1.5x
    public static final double TARGET_SCALE_30 = 0.9; // 1.5x
    public static final double TARGET_SCALE_50 = 1.35; // 1.5x
    public static final double TARGET_SCALE_ALL = 2.025; // 1.5x

    // Boundaries between levels (midpoints)
    private static final double BOUND_10_20 = (TARGET_SCALE_10 + TARGET_SCALE_20) / 2.0; // 0.5
    private static final double BOUND_20_30 = (TARGET_SCALE_20 + TARGET_SCALE_30) / 2.0; // 0.75
    private static final double BOUND_30_50 = (TARGET_SCALE_30 + TARGET_SCALE_50) / 2.0; // 1.125
    private static final double BOUND_50_ALL = (TARGET_SCALE_50 + TARGET_SCALE_ALL) / 2.0; // 1.6875

    // File-size → radius mapping:
    // Target: 100 KiB -> diameter ~ 50 px -> radius ~ 25 px, with area proportional to bytes.
    private static final double REFERENCE_BYTES = 102400.0; // 100 KiB
    private static final double REFERENCE_RADIUS = 25.0; // px
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

    // One-time paint stats logging to avoid log spam
    private volatile boolean firstPaintLogged = false;

    // Adjacency cache for Top-K edge filtering (rebuilt when graph changes)
    private volatile @Nullable Graph adjacencyCacheGraphRef = null;
    private Map<ProjectFile, List<Edge>> adjacencySorted = Map.of();

    // Node importance cache: nodes ranked by total incident edge weight (rebuilt when graph changes)
    private volatile @Nullable Graph nodeRankCacheGraphRef = null;
    private List<ProjectFile> nodesByTotalWeight = List.of();
    private Map<ProjectFile, Integer> nodeRankIndex = Map.of();

    // Fast-mode flags and debounce timer for high-quality repaint after drag ends
    private volatile boolean fastMode = false;
    private @Nullable Timer slowRepaintTimer = null;
    private static final int FAST_REPAINT_DELAY_MS = 150; // ms
    private static final double FAST_MODE_MAX_PCT = 0.30; // clamp LOD to 30% while dragging

    // Stroke caches to avoid per-edge BasicStroke allocations
    private static final BasicStroke[] STROKES_FINE = new BasicStroke[] {
        makeStroke(1.0f), makeStroke(2.0f), makeStroke(3.0f),
        makeStroke(4.0f), makeStroke(5.0f), makeStroke(6.0f)
    };
    private static final BasicStroke[] STROKES_COARSE =
            new BasicStroke[] {makeStroke(1.0f), makeStroke(3.0f), makeStroke(6.0f)};

    public CoChangeGraphPanel() {
        setOpaque(true);
        setBackground(Color.WHITE);
        installInteractions();
        // Enable Swing tooltips for mouse-over hints
        ToolTipManager.sharedInstance().registerComponent(this);
        setToolTipText("");
        logger.debug("CoChangeGraphPanel initialized");
    }

    public void setGraph(Graph g) {
        assert SwingUtilities.isEventDispatchThread() : "setGraph must be called on EDT";
        this.graph = g;
        // Invalidate adjacency cache on graph change
        this.adjacencyCacheGraphRef = null;
        this.adjacencySorted = Map.of();
        // Invalidate node-importance cache
        this.nodeRankCacheGraphRef = null;
        this.nodesByTotalWeight = List.of();
        this.nodeRankIndex = Map.of();

        if (logger.isDebugEnabled()) {
            logger.debug("setGraph: nodes={}, edges={}", g.nodes.size(), g.edges.size());
        }
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

        // Quality hints (fast mode prioritizes speed during drag)
        if (fastMode) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_DEFAULT);
        } else {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        }

        // Apply pan/zoom transform
        var oldTx = g2.getTransform();
        g2.transform(viewTx);

        var localGraph = this.graph;

        // Determine current LOD based on zoom
        double scale = getScale();
        double topPct = determinePctForScale(scale);
        // In fast mode, clamp to a stricter LOD to reduce drawn content
        if (fastMode) {
            topPct = Math.min(topPct, FAST_MODE_MAX_PCT);
        }
        if (topPct < 1.0) {
            ensureAdjacencyCache(localGraph);
            ensureNodeWeightRanking(localGraph);
        }

        // One-time paint-time debug: stats about positions
        if (!firstPaintLogged && logger.isDebugEnabled()) {
            int n = localGraph.nodes.size();
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            int atOrigin = 0;
            for (var nd : localGraph.nodes.values()) {
                double xi = nd.x, yi = nd.y;
                if (xi < minX) minX = xi;
                if (xi > maxX) maxX = xi;
                if (yi < minY) minY = yi;
                if (yi > maxY) maxY = yi;
                if (xi == 0.0 && yi == 0.0) atOrigin++;
            }
            logger.debug(
                    "GraphPanel paint stats: nodes={}, bbox=[{}..{}]x[{}..{}], atOrigin={}",
                    n,
                    (minX == Double.POSITIVE_INFINITY ? 0.0 : minX),
                    (maxX == Double.NEGATIVE_INFINITY ? 0.0 : maxX),
                    (minY == Double.POSITIVE_INFINITY ? 0.0 : minY),
                    (maxY == Double.NEGATIVE_INFINITY ? 0.0 : maxY),
                    atOrigin);
            firstPaintLogged = true;
        }

        // Draw edges first (progressive LOD)
        g2.setColor(EDGE_COLOR);
        for (var e : localGraph.edges.values()) {
            if (topPct < 1.0) {
                // Node-importance filter: only show edges touching the top N% most "important" nodes by total incident
                // weight
                if (!isNodeInTopPct(e.a(), topPct) && !isNodeInTopPct(e.b(), topPct)) {
                    continue;
                }
                // Per-node adjacency filter: only keep edges in the top N% by weight for at least one endpoint
                if (!isEdgeInTopPct(e, topPct)) {
                    continue;
                }
            }
            var na = localGraph.nodes.get(e.a());
            var nb = localGraph.nodes.get(e.b());
            if (na == null || nb == null) {
                continue;
            }
            var stroke = strokeForWeight(e.weight(), fastMode);
            var old = g2.getStroke();
            g2.setStroke(stroke);
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
            if (!fastMode) {
                g2.setColor(NODE_OUTLINE);
                g2.draw(circle);
            }
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

    // Returns {cx, cy} of current graph nodes or null if empty
    private @Nullable double[] computeCentroid() {
        var g = this.graph;
        if (g.nodes.isEmpty()) {
            return null;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        int n = 0;
        for (var nd : g.nodes.values()) {
            sumX += nd.x;
            sumY += nd.y;
            n++;
        }
        if (n == 0) {
            return null;
        }
        return new double[] {sumX / n, sumY / n};
    }

    @Override
    public @Nullable String getToolTipText(MouseEvent e) {
        var g = this.graph;
        if (g.nodes.isEmpty()) {
            return null;
        }

        // Convert mouse position (screen space) to world space for hit testing
        Point2D pScreen = new Point2D.Double(e.getX(), e.getY());
        Point2D pWorld;
        try {
            var inv = viewTx.createInverse();
            pWorld = inv.transform(pScreen, null);
        } catch (NoninvertibleTransformException ex) {
            return null;
        }

        double wx = pWorld.getX();
        double wy = pWorld.getY();

        Node best = null;
        double bestDist2 = Double.POSITIVE_INFINITY;

        // Linear scan for now; fast enough for typical node counts
        for (var nd : g.nodes.values()) {
            double r = nodeRadius(nd);
            double dx = wx - nd.x;
            double dy = wy - nd.y;
            double d2 = dx * dx + dy * dy;
            if (d2 <= r * r && d2 < bestDist2) {
                best = nd;
                bestDist2 = d2;
            }
        }

        if (best == null) return null;

        // Show the file path; prefer absolute path string for clarity
        return best.file.absPath().toString();
    }

    private void installInteractions() {
        var mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastDragPoint = e.getPoint();
                enterFastMode();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                lastDragPoint = null;
                scheduleExitFastMode();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (lastDragPoint != null) {
                    enterFastMode();
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
        double current = getScale();
        double target = clamp(current * scale, MIN_SCALE, MAX_SCALE);
        double factor = target / (current == 0.0 ? 1.0 : current);

        viewTx.translate(anchorScreen.x, anchorScreen.y);
        viewTx.scale(factor, factor);
        viewTx.translate(-anchorScreen.x, -anchorScreen.y);
        if (logger.isDebugEnabled()) {
            logger.debug(
                    "zoomAt: anchor=({},{}), factor={}, scale=({},{}), translate=({}, {})",
                    anchorScreen.x,
                    anchorScreen.y,
                    factor,
                    viewTx.getScaleX(),
                    viewTx.getScaleY(),
                    viewTx.getTranslateX(),
                    viewTx.getTranslateY());
        }
        repaint();
    }

    // Optional API to reset view
    public void resetView() {
        assert SwingUtilities.isEventDispatchThread() : "resetView must be called on EDT";
        viewTx.setToIdentity();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            var pref = getPreferredSize();
            w = pref.width;
            h = pref.height;
        }
        var centroid = computeCentroid();
        // First move origin to screen center
        viewTx.translate(w / 2.0, h / 2.0);
        // Then offset by negative centroid so centroid maps to center
        if (centroid != null) {
            viewTx.translate(-centroid[0], -centroid[1]);
            logger.debug(
                    "resetView: centered centroid at screen center; centroid=({}, {}), panel=({}, {})",
                    String.format("%.2f", centroid[0]),
                    String.format("%.2f", centroid[1]),
                    w,
                    h);
        } else {
            logger.debug("resetView: centered origin at ({}, {})", (w / 2.0), (h / 2.0));
        }
        repaint();
    }

    public double getScale() {
        return viewTx.getScaleX();
    }

    public void zoomByCenter(double factor) {
        var center = new Point(getWidth() / 2, getHeight() / 2);
        zoomAt(center, factor);
    }

    public void zoomToScale(double targetScale) {
        double clamped = clamp(targetScale, MIN_SCALE, MAX_SCALE);
        double current = getScale();
        if (Math.abs(clamped - current) < 1e-6) {
            return;
        }
        double factor = clamped / (current == 0.0 ? 1.0 : current);
        zoomByCenter(factor);
    }

    private double determinePctForScale(double scale) {
        if (scale < BOUND_10_20) return 0.10;
        if (scale < BOUND_20_30) return 0.20;
        if (scale < BOUND_30_50) return 0.30;
        if (scale < BOUND_50_ALL) return 0.50;
        return 1.0; // all
    }

    private void ensureAdjacencyCache(Graph g) {
        if (adjacencyCacheGraphRef == g && !adjacencySorted.isEmpty()) {
            return;
        }
        var map = new HashMap<ProjectFile, List<Edge>>(g.nodes.size());
        // Initialize lists
        for (var pf : g.nodes.keySet()) {
            map.put(pf, new ArrayList<>());
        }
        // Populate adjacency with existing Edge instances
        for (var e : g.edges.values()) {
            map.computeIfAbsent(e.a(), k -> new ArrayList<>()).add(e);
            map.computeIfAbsent(e.b(), k -> new ArrayList<>()).add(e);
        }
        // Sort by descending weight
        for (var entry : map.entrySet()) {
            entry.getValue().sort((e1, e2) -> Integer.compare(e2.weight(), e1.weight()));
        }
        adjacencySorted = map;
        adjacencyCacheGraphRef = g;
        if (logger.isDebugEnabled()) {
            logger.debug("Adjacency cache built: nodes={}, edges={}", g.nodes.size(), g.edges.size());
        }
    }

    private void ensureNodeWeightRanking(Graph g) {
        if (nodeRankCacheGraphRef == g && !nodesByTotalWeight.isEmpty() && !nodeRankIndex.isEmpty()) {
            return;
        }
        var totals = new HashMap<ProjectFile, Integer>(g.nodes.size());
        for (var e : g.edges.values()) {
            totals.merge(e.a(), e.weight(), Integer::sum);
            totals.merge(e.b(), e.weight(), Integer::sum);
        }
        var ranked = new ArrayList<ProjectFile>(g.nodes.keySet());
        ranked.sort((p1, p2) -> Integer.compare(totals.getOrDefault(p2, 0), totals.getOrDefault(p1, 0)));
        var rankIdx = new HashMap<ProjectFile, Integer>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            rankIdx.put(ranked.get(i), i);
        }
        nodesByTotalWeight = ranked;
        nodeRankIndex = rankIdx;
        nodeRankCacheGraphRef = g;

        if (logger.isDebugEnabled()) {
            int n = ranked.size();
            int top1 = n > 0 ? totals.getOrDefault(ranked.get(0), 0) : 0;
            logger.debug("Node importance ranking built: nodes={}, topWeight={}", n, top1);
        }
    }

    private boolean isNodeInTopPct(ProjectFile pf, double pct) {
        if (pct >= 1.0) return true;
        if (nodeRankIndex.isEmpty()) return false;
        int n = nodesByTotalWeight.size();
        int k = Math.max(1, (int) Math.ceil(n * pct));
        Integer r = nodeRankIndex.get(pf);
        return r != null && r < k;
    }

    private boolean isEdgeInTopPct(Edge e, double pct) {
        if (pct >= 1.0) return true;
        var la = adjacencySorted.get(e.a());
        var lb = adjacencySorted.get(e.b());
        if (la == null && lb == null) return false;
        return inFirstPct(la, e, pct) || inFirstPct(lb, e, pct);
    }

    private static boolean inFirstPct(@Nullable List<Edge> list, Edge e, double pct) {
        if (list == null || list.isEmpty()) return false;
        int k = Math.max(1, (int) Math.ceil(list.size() * pct));
        int limit = Math.min(k, list.size());
        for (int i = 0; i < limit; i++) {
            if (e.equals(list.get(i))) return true;
        }
        return false;
    }

    private static BasicStroke makeStroke(float w) {
        return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    private BasicStroke strokeForWeight(int weight, boolean fast) {
        float width = edgeStrokeWidth(weight);
        if (fast) {
            if (width <= 2.0f) return STROKES_COARSE[0];
            if (width <= 4.0f) return STROKES_COARSE[1];
            return STROKES_COARSE[2];
        } else {
            int idx = Math.max(1, Math.min(6, Math.round(width))) - 1; // 0..5
            return STROKES_FINE[idx];
        }
    }

    private void enterFastMode() {
        if (!fastMode) {
            fastMode = true;
            if (logger.isDebugEnabled()) {
                logger.debug("Fast mode enabled");
            }
            repaint();
        }
        if (slowRepaintTimer != null && slowRepaintTimer.isRunning()) {
            slowRepaintTimer.stop();
        }
    }

    private void scheduleExitFastMode() {
        if (slowRepaintTimer == null) {
            slowRepaintTimer = new Timer(FAST_REPAINT_DELAY_MS, e -> {
                fastMode = false;
                if (logger.isDebugEnabled()) {
                    logger.debug("Fast mode disabled; repainting high quality");
                }
                repaint();
            });
            slowRepaintTimer.setRepeats(false);
        }
        if (slowRepaintTimer.isRunning()) {
            slowRepaintTimer.stop();
        }
        slowRepaintTimer.start();
    }
}
