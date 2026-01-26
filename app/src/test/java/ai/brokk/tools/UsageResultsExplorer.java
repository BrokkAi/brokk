package ai.brokk.tools;

import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.dialogs.BaseThemedDialog;
import ai.brokk.gui.theme.GuiTheme;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Component;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class UsageResultsExplorer extends BaseThemedDialog {

    private final Path resultsDir;
    private final EvalResults summary;
    private final DetailedResults truePositives;
    private final DetailedResults falsePositives;
    private final DetailedResults falseNegatives;

    private final JTree tpTree;
    private final JTree fpTree;
    private final JTree fnTree;
    private final RSyntaxTextArea previewArea;
    private @Nullable JTabbedPane tabs;

    public UsageResultsExplorer(Path resultsDir) throws Exception {
        super(null, "UsageBenchEval Results Explorer");
        this.resultsDir = resultsDir;

        ObjectMapper mapper = new ObjectMapper();
        this.summary = mapper.readValue(resultsDir.resolve("summary.json").toFile(), EvalResults.class);
        this.truePositives = mapper.readValue(resultsDir.resolve("true-positives.json").toFile(), DetailedResults.class);
        this.falsePositives = mapper.readValue(resultsDir.resolve("false-positives.json").toFile(), DetailedResults.class);
        this.falseNegatives = mapper.readValue(resultsDir.resolve("false-negatives.json").toFile(), DetailedResults.class);

        this.tpTree = createTree(truePositives);
        this.fpTree = createTree(falsePositives);
        this.fnTree = createTree(falseNegatives);
        this.previewArea = new RSyntaxTextArea();
        this.previewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        this.previewArea.setEditable(false);

        // Apply RSyntax theme to match the application theme
        GuiTheme.loadRSyntaxTheme(GuiTheme.THEME_DARK).ifPresent(theme -> theme.apply(previewArea));

        initializeUI();
        setupSelectionListeners();
    }

    private void initializeUI() {
        JPanel root = getContentRoot();
        root.setLayout(new BorderLayout());

        // NORTH: Summary Panel
        JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 10));
        summaryPanel.setBorder(BorderFactory.createTitledBorder("Aggregate Metrics"));
        var agg = summary.aggregate();
        summaryPanel.add(new JLabel("TP: " + agg.totalTP()));
        summaryPanel.add(new JLabel("FP: " + agg.totalFP()));
        summaryPanel.add(new JLabel("FN: " + agg.totalFN()));
        summaryPanel.add(new JLabel(String.format("Precision: %.3f", agg.precision())));
        summaryPanel.add(new JLabel(String.format("Recall: %.3f", agg.recall())));
        summaryPanel.add(new JLabel(String.format("F1: %.3f", agg.f1())));
        root.add(summaryPanel, BorderLayout.NORTH);

        // CENTER: Tabs
        this.tabs = new JTabbedPane();
        tabs.addTab("True Positives", createTabComponent(tpTree));
        tabs.addTab("False Positives", createTabComponent(fpTree));
        tabs.addTab("False Negatives", createTabComponent(fnTree));

        tabs.addChangeListener(e -> {
            previewArea.setText("");
            // Clear selections on non-active trees
            int selectedIndex = tabs.getSelectedIndex();
            if (selectedIndex != 0) tpTree.clearSelection();
            if (selectedIndex != 1) fpTree.clearSelection();
            if (selectedIndex != 2) fnTree.clearSelection();
        });

        root.add(tabs, BorderLayout.CENTER);

        // SOUTH: Close
        JPanel southPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        MaterialButton closeButton = new MaterialButton("Close");
        closeButton.addActionListener(e -> dispose());
        SwingUtil.applyPrimaryButtonStyle(closeButton);
        southPanel.add(closeButton);
        root.add(southPanel, BorderLayout.SOUTH);

        setSize(new Dimension(1000, 700));
        setLocationRelativeTo(null);
    }

    private JSplitPane createTabComponent(JTree tree) {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(new JScrollPane(tree));
        split.setRightComponent(new RTextScrollPane(previewArea));
        split.setDividerLocation(400);
        return split;
    }

    private JTree createTree(DetailedResults results) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Results");
        Map<String, Map<String, List<CodeUnitDetail>>> grouped = results.codeUnits().stream()
                .collect(Collectors.groupingBy(
                        CodeUnitDetail::project,
                        Collectors.groupingBy(cud -> {
                            String path = cud.searchedFilePath();
                            return (path == null || path.isEmpty()) ? "(unknown file)" : path;
                        })
                ));

        for (Map.Entry<String, Map<String, List<CodeUnitDetail>>> projectEntry : grouped.entrySet()) {
            int projectUnitCount = projectEntry.getValue().values().stream().mapToInt(List::size).sum();
            DefaultMutableTreeNode projectNode = new DefaultMutableTreeNode(new ProjectNode(projectEntry.getKey(), projectUnitCount));

            for (Map.Entry<String, List<CodeUnitDetail>> fileEntry : projectEntry.getValue().entrySet()) {
                DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(new FileNode(fileEntry.getKey(), fileEntry.getValue().size()));
                for (CodeUnitDetail detail : fileEntry.getValue()) {
                    fileNode.add(new DefaultMutableTreeNode(detail));
                }
                projectNode.add(fileNode);
            }
            root.add(projectNode);
        }

        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode node) {
                    Object userObj = node.getUserObject();
                    if (userObj instanceof ProjectNode(String name, int count)) {
                        setText(String.format("%s (%d units)", name, count));
                    } else if (userObj instanceof FileNode(String displayName, int count)) {
                        if (!displayName.equals("(unknown file)")) {
                            try {
                                Path p = Paths.get(displayName);
                                Path fileName = p.getFileName();
                                if (fileName != null) {
                                    displayName = fileName.toString();
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        setText(String.format("%s (%d code units)", displayName, count));
                    } else if (userObj instanceof CodeUnitDetail cud) {
                        setText(String.format("%s (%d usages)", cud.searchedFqn(), cud.usages().size()));
                    }
                }
                return this;
            }
        });
        return tree;
    }

    private void setupSelectionListeners() {
        tpTree.addTreeSelectionListener(e -> {
            if (tabs != null && tabs.getSelectedIndex() == 0) {
                updatePreviewFromTree(e.getNewLeadSelectionPath());
            }
        });
        fpTree.addTreeSelectionListener(e -> {
            if (tabs != null && tabs.getSelectedIndex() == 1) {
                updatePreviewFromTree(e.getNewLeadSelectionPath());
            }
        });
        fnTree.addTreeSelectionListener(e -> {
            if (tabs != null && tabs.getSelectedIndex() == 2) {
                updatePreviewFromTree(e.getNewLeadSelectionPath());
            }
        });
    }

    private void updatePreviewFromTree(@Nullable TreePath path) {
        if (path == null) {
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof CodeUnitDetail detail) {
            updatePreview(detail);
        } else {
            previewArea.setText("");
        }
    }

    private void updatePreview(CodeUnitDetail detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Searched FQN: ").append(detail.searchedFqn()).append("\n");
        sb.append("// Project: ").append(detail.project()).append("\n\n");

        for (UsageDetail usage : detail.usages()) {
            sb.append("// ----------------------------------------------------------------\n");
            sb.append("// Usage in: ").append(usage.fqName()).append("\n");
            sb.append("// File: ").append(usage.filePath()).append("\n");
            sb.append("// ----------------------------------------------------------------\n");
            sb.append(usage.snippet()).append("\n\n");
        }
        previewArea.setText(sb.toString());
        previewArea.setCaretPosition(0);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: UsageResultsExplorer <results_directory>");
            System.exit(1);
        }

        Path path = Path.of(args[0]);
        SwingUtilities.invokeLater(() -> {
            try {
                UsageResultsExplorer dialog = new UsageResultsExplorer(path);
                dialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                dialog.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // --- UI Helpers ---

    private record ProjectNode(String name, int count) {}

    private record FileNode(String path, int count) {}

    // --- JSON Data Models ---

    public record DetailedResults(@JsonProperty("codeUnits") List<CodeUnitDetail> codeUnits) {}

    public record CodeUnitDetail(
            @JsonProperty("searchedFqn") String searchedFqn,
            @JsonProperty("searchedFilePath") String searchedFilePath,
            @JsonProperty("project") String project,
            @JsonProperty("projectPath") String projectPath,
            @JsonProperty("language") String language,
            @JsonProperty("usages") List<UsageDetail> usages) {}

    public record UsageDetail(
            @JsonProperty("fqName") String fqName,
            @JsonProperty("snippet") String snippet,
            @JsonProperty("filePath") String filePath) {}

    public record EvalResults(
            @JsonProperty("projects") List<ProjectResult> projects,
            @JsonProperty("aggregate") AggregateMetrics aggregate) {}

    public record ProjectResult(
            @JsonProperty("project") String project,
            @JsonProperty("language") String language,
            @JsonProperty("truePositives") int truePositives,
            @JsonProperty("falsePositives") int falsePositives,
            @JsonProperty("falseNegatives") int falseNegatives,
            @JsonProperty("precision") double precision,
            @JsonProperty("recall") double recall,
            @JsonProperty("f1") double f1) {}

    public record AggregateMetrics(
            @JsonProperty("totalTP") int totalTP,
            @JsonProperty("totalFP") int totalFP,
            @JsonProperty("totalFN") int totalFN,
            @JsonProperty("precision") double precision,
            @JsonProperty("recall") double recall,
            @JsonProperty("f1") double f1) {}
}
