package ai.brokk.gui.visualize;

import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Dialog for the File Co-Change visualization.
 * Phase 1: build graph from commits (progress bar 1).
 * Phase 2: physics layout (progress bar 2).
 * On completion, shows an interactive CoChangeGraphPanel.
 */
public class CoChangeGraphDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(CoChangeGraphDialog.class);

    private final Chrome chrome;

    // Center content management
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel centerPanel = new JPanel(cardLayout);
    private final JPanel progressPanel = new JPanel(new BorderLayout(8, 8));
    private final CoChangeGraphPanel graphPanel = new CoChangeGraphPanel();

    // Progress UI
    private final JLabel buildLabel = new JLabel("Preparing...", SwingConstants.LEFT);
    private final JProgressBar buildBar = new JProgressBar(0, 100);
    private final JLabel layoutLabel = new JLabel("Waiting for layout...", SwingConstants.LEFT);
    private final JProgressBar layoutBar = new JProgressBar(0, 20);

    // Buttons
    private final MaterialButton doneButton = new MaterialButton("Done");
    private final MaterialButton cancelButton = new MaterialButton("Cancel");

    // Zoom / LOD controls
    private final JPanel controlsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
    private final MaterialButton zoomOutBtn = new MaterialButton("-");
    private final MaterialButton p10Btn = new MaterialButton("10%");
    private final MaterialButton p20Btn = new MaterialButton("20%");
    private final MaterialButton p30Btn = new MaterialButton("30%");
    private final MaterialButton p50Btn = new MaterialButton("50%");
    private final MaterialButton allBtn = new MaterialButton("All");
    private final MaterialButton zoomInBtn = new MaterialButton("+");

    // Async orchestration
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile CompletableFuture<Graph> pipelineFuture = new CompletableFuture<>();

    public CoChangeGraphDialog(Frame owner, Chrome chrome) {
        super(owner, "Visualize File Co-Changes", true);
        this.chrome = chrome;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        // Initialize progress UI
        initProgressPanel();

        // Controls toolbar (zoom + LOD presets)
        initControlsPanel();

        // Center content starts with progress view
        centerPanel.add(progressPanel, "progress");
        centerPanel.add(graphPanel, "graph");
        add(centerPanel, BorderLayout.CENTER);
        add(controlsPanel, BorderLayout.NORTH);
        cardLayout.show(centerPanel, "progress");

        // Buttons panel (primary Done + Cancel as per style guide)
        var buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));

        SwingUtil.applyPrimaryButtonStyle(doneButton);
        doneButton.setEnabled(false);
        doneButton.addActionListener(e -> dispose());

        cancelButton.addActionListener(e -> onCancel());

        buttonsPanel.add(doneButton);
        buttonsPanel.add(cancelButton);

        add(buttonsPanel, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(960, 640));
        pack();
        setLocationRelativeTo(owner);

        logger.debug("CoChangeGraphDialog initialized");

        // Kick off background work after UI is shown
        SwingUtil.runOnEdt(this::startAsyncWork);
    }

    private void initProgressPanel() {
        var north = new JPanel(new BorderLayout(6, 6));
        north.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
        var title = new JLabel("Building co-change visualization...", SwingConstants.LEFT);
        title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 1.0f));
        north.add(title, BorderLayout.NORTH);

        var center = new JPanel();
        center.setLayout(new java.awt.GridBagLayout());
        var gc = new java.awt.GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 1.0;
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gc.insets = new java.awt.Insets(4, 12, 4, 12);

        // Build phase label + bar
        buildBar.setStringPainted(true);
        layoutBar.setStringPainted(true);
        buildBar.setIndeterminate(true);
        layoutBar.setIndeterminate(true);

        center.add(buildLabel, gc);
        gc.gridy++;
        center.add(buildBar, gc);
        gc.gridy++;
        center.add(layoutLabel, gc);
        gc.gridy++;
        center.add(layoutBar, gc);

        progressPanel.add(north, BorderLayout.NORTH);
        progressPanel.add(center, BorderLayout.CENTER);
    }

    private void initControlsPanel() {
        controlsPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 8));

        zoomOutBtn.setToolTipText("Zoom out");
        p10Btn.setToolTipText("Zoom to Top 10% edges");
        p20Btn.setToolTipText("Zoom to Top 20% edges");
        p30Btn.setToolTipText("Zoom to Top 30% edges");
        p50Btn.setToolTipText("Zoom to Top 50% edges");
        allBtn.setToolTipText("Zoom to show all edges");
        zoomInBtn.setToolTipText("Zoom in");

        zoomOutBtn.addActionListener(e -> graphPanel.zoomByCenter(1.0 / 1.2));
        p10Btn.addActionListener(e -> graphPanel.zoomToScale(CoChangeGraphPanel.TARGET_SCALE_10));
        p20Btn.addActionListener(e -> graphPanel.zoomToScale(CoChangeGraphPanel.TARGET_SCALE_20));
        p30Btn.addActionListener(e -> graphPanel.zoomToScale(CoChangeGraphPanel.TARGET_SCALE_30));
        p50Btn.addActionListener(e -> graphPanel.zoomToScale(CoChangeGraphPanel.TARGET_SCALE_50));
        allBtn.addActionListener(e -> graphPanel.zoomToScale(CoChangeGraphPanel.TARGET_SCALE_ALL));
        zoomInBtn.addActionListener(e -> graphPanel.zoomByCenter(1.2));

        controlsPanel.add(zoomOutBtn);
        controlsPanel.add(p10Btn);
        controlsPanel.add(p20Btn);
        controlsPanel.add(p30Btn);
        controlsPanel.add(p50Btn);
        controlsPanel.add(allBtn);
        controlsPanel.add(zoomInBtn);
    }

    private void startAsyncWork() {
        // Phase 1: build graph from commits
        var builder = new CoChangeGraphBuilder(chrome);

        Consumer<CoChangeGraphBuilder.Progress> buildConsumer = p -> SwingUtil.runOnEdt(() -> updateBuildProgress(p));

        pipelineFuture = builder.buildAsync(buildConsumer, cancelled::get, null, null)
                .thenCompose(graph -> {
                    if (cancelled.get()) {
                        throw new CancellationException("Cancelled after build phase");
                    }
                    // Phase 2: physics layout
                    var layout = new CoChangePhysicsLayout();
                    SwingUtil.runOnEdt(() -> layoutBar.setIndeterminate(false));
                    Consumer<CoChangePhysicsLayout.Progress> layoutConsumer =
                            p -> SwingUtil.runOnEdt(() -> updateLayoutProgress(p));
                    int h = graphPanel.getHeight();
                    if (h <= 0) {
                        h = graphPanel.getPreferredSize().height;
                    }
                    double viewHeight = Math.max(1, h);
                    return layout.runAsync(graph, layoutConsumer, viewHeight);
                });

        pipelineFuture.whenComplete((graph, ex) -> {
            if (ex != null) {
                var cause = unwrapCompletion(ex);
                if (cause instanceof CancellationException) {
                    logger.info("Co-change visualization cancelled");
                    SwingUtil.runOnEdt(this::onCancelled);
                } else {
                    logger.error("Co-change visualization failed", cause);
                    SwingUtil.runOnEdt(() -> onFailed(cause));
                }
                return;
            }
            // Success
            SwingUtil.runOnEdt(() -> onCompleted(graph));
        });
    }

    private void updateBuildProgress(CoChangeGraphBuilder.Progress p) {
        buildLabel.setText(p.message());
        if (p.max() > 0) {
            buildBar.setIndeterminate(false);
            buildBar.setMaximum(p.max());
            buildBar.setValue(p.value());
            buildBar.setString(p.value() + " / " + p.max());
        } else {
            buildBar.setIndeterminate(true);
            buildBar.setString("Working...");
        }
    }

    private void updateLayoutProgress(CoChangePhysicsLayout.Progress p) {
        layoutLabel.setText(p.message());
        layoutBar.setIndeterminate(false);
        layoutBar.setMaximum(p.max());
        layoutBar.setValue(p.value());
        layoutBar.setString(p.value() + " / " + p.max());
    }

    private void onCompleted(Graph graph) {
        graphPanel.setGraph(graph);
        cardLayout.show(centerPanel, "graph");
        graphPanel.resetView();
        // Start maximally zoomed out on first show
        graphPanel.zoomToScale(CoChangeGraphPanel.TARGET_SCALE_10);
        doneButton.setEnabled(true);
        cancelButton.setText("Close");
    }

    private void onCancelled() {
        buildBar.setIndeterminate(false);
        layoutBar.setIndeterminate(false);
        buildLabel.setText("Cancelled");
        layoutLabel.setText("");
        buildBar.setValue(0);
        layoutBar.setValue(0);
        doneButton.setEnabled(true);
        cancelButton.setText("Close");
    }

    private void onFailed(Throwable t) {
        buildBar.setIndeterminate(false);
        layoutBar.setIndeterminate(false);
        buildLabel.setText("Error: " + t.getMessage());
        layoutLabel.setText("See logs for details");
        doneButton.setEnabled(true);
        cancelButton.setText("Close");
    }

    private void onCancel() {
        if (cancelled.compareAndSet(false, true)) {
            cancelButton.setEnabled(false);
            pipelineFuture.cancel(true);
        }
        dispose();
    }

    private static Throwable unwrapCompletion(Throwable ex) {
        if (ex instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null) {
            return ce.getCause();
        }
        if (ex instanceof java.util.concurrent.ExecutionException ee && ee.getCause() != null) {
            return ee.getCause();
        }
        return ex;
    }
}
