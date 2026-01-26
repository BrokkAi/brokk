package ai.brokk.gui.dialogs;

import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
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

    private final JTable tpTable;
    private final JTable fpTable;
    private final JTable fnTable;
    private final RSyntaxTextArea previewArea;

    public UsageResultsExplorer(Path resultsDir) throws Exception {
        super(null, "UsageBenchEval Results Explorer");
        this.resultsDir = resultsDir;

        ObjectMapper mapper = new ObjectMapper();
        this.summary = mapper.readValue(resultsDir.resolve("summary.json").toFile(), EvalResults.class);
        this.truePositives = mapper.readValue(resultsDir.resolve("true-positives.json").toFile(), DetailedResults.class);
        this.falsePositives = mapper.readValue(resultsDir.resolve("false-positives.json").toFile(), DetailedResults.class);
        this.falseNegatives = mapper.readValue(resultsDir.resolve("false-negatives.json").toFile(), DetailedResults.class);

        this.tpTable = createTable(truePositives);
        this.fpTable = createTable(falsePositives);
        this.fnTable = createTable(falseNegatives);
        this.previewArea = new RSyntaxTextArea();
        this.previewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        this.previewArea.setEditable(false);

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
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("True Positives", createTabComponent(tpTable));
        tabs.addTab("False Positives", createTabComponent(fpTable));
        tabs.addTab("False Negatives", createTabComponent(fnTable));
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

    private JSplitPane createTabComponent(JTable table) {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setLeftComponent(new JScrollPane(table));
        split.setRightComponent(new RTextScrollPane(previewArea));
        split.setDividerLocation(400);
        return split;
    }

    private JTable createTable(DetailedResults results) {
        String[] columns = {"Searched FQN", "Project", "# Usages"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        for (CodeUnitDetail detail : results.codeUnits()) {
            model.addRow(new Object[]{
                detail.searchedFqn(),
                detail.project(),
                detail.usages().size()
            });
        }

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        return table;
    }

    private void setupSelectionListeners() {
        tpTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updatePreview(truePositives, tpTable.getSelectedRow());
        });
        fpTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updatePreview(falsePositives, fpTable.getSelectedRow());
        });
        fnTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updatePreview(falseNegatives, fnTable.getSelectedRow());
        });
    }

    private void updatePreview(DetailedResults results, int rowIndex) {
        if (rowIndex < 0 || rowIndex >= results.codeUnits().size()) {
            previewArea.setText("");
            return;
        }

        CodeUnitDetail detail = results.codeUnits().get(rowIndex);
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
