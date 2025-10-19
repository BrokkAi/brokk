package io.github.jbellis.brokk.gui.dialogs;

import static io.github.jbellis.brokk.gui.Constants.*;
import static java.util.Objects.requireNonNull;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.google.common.base.Splitter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.TaskResult;
import io.github.jbellis.brokk.agents.ArchitectAgent;
import io.github.jbellis.brokk.agents.BlitzForge;
import io.github.jbellis.brokk.agents.BuildAgent;
import io.github.jbellis.brokk.agents.CodeAgent;
import io.github.jbellis.brokk.agents.RelevanceClassifier;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.BorderUtils;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.InstructionsPanel;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.dialogs.BlitzForgeProgressDialog.PostProcessingOption;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.gui.util.ScaledIcon;
import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.Messages;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BlitzForgeDialog extends JDialog {
    private static final Logger logger = LogManager.getLogger(BlitzForgeDialog.class);
    private final Chrome chrome;
    private JTextArea instructionsArea;
    private JComboBox<Service.FavoriteModel> modelComboBox;
    private JComboBox<String> actionComboBox;
    private JLabel tokenWarningLabel;
    private JLabel costEstimateLabel;
    private JCheckBox includeWorkspaceCheckbox;
    private JComboBox<String> relatedClassesCombo;
    private JTextField perFileCommandTextField;
    private JTextArea postProcessingInstructionsArea;
    private JLabel postProcessingModelLabel;

    // Post-processing controls
    private JComboBox<String> runPostProcessCombo;
    private JComboBox<String> parallelOutputCombo;
    private JCheckBox buildFirstCheckbox;
    private JTextField contextFilterTextField;
    private static final String ALL_LANGUAGES_OPTION = "All Languages";
    private static final int TOKEN_SAFETY_MARGIN = 32768;
    private static final int MAX_BLITZ_HISTORY_ITEMS = 50;
    private static final int HISTORY_TRUNCATION_LENGTH = 100;

    // Tracks the most recent cost estimate and user balance
    private volatile double estimatedCost = 0.0;
    private volatile float userBalance = Float.NaN;

    // Cache (file -> token count) to avoid recomputation on every UI refresh
    private final Map<ProjectFile, Long> tokenCountCache = new ConcurrentHashMap<>();

    @SuppressWarnings("NullAway.Init")
    private JComboBox<String> languageComboBox;

    @SuppressWarnings("NullAway.Init")
    private JTable selectedFilesTable;

    @SuppressWarnings("NullAway.Init")
    private DefaultTableModel tableModel;

    @SuppressWarnings("NullAway.Init")
    private TableRowSorter<DefaultTableModel> selectedFilesSorter;

    private JLabel selectedFilesCountLabel;

    private static final Icon smallInfoIcon;

    static {
        Icon baseIcon = UIManager.getIcon("OptionPane.informationIcon");
        requireNonNull(baseIcon);
        if (baseIcon instanceof ImageIcon ii) {
            Image img = ii.getImage()
                    .getScaledInstance(
                            (int) Math.round(ii.getIconWidth() * 0.5),
                            (int) Math.round(ii.getIconHeight() * 0.5),
                            Image.SCALE_SMOOTH);
            smallInfoIcon = new ImageIcon(img);
        } else {
            smallInfoIcon = new ScaledIcon(baseIcon, 0.5);
        }
    }

    /** Simple filled-circle icon used for speed indicators. */
    private static final class ColorDotIcon implements Icon {
        private final Color color;
        private final int diameter;

        private ColorDotIcon(Color color, int diameter) {
            this.color = color;
            this.diameter = diameter;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillOval(x, y, diameter, diameter);
            g.setColor(Color.DARK_GRAY);
            g.drawOval(x, y, diameter, diameter); // subtle outline
        }

        @Override
        public int getIconWidth() {
            return diameter;
        }

        @Override
        public int getIconHeight() {
            return diameter;
        }
    }

    public BlitzForgeDialog(Frame owner, Chrome chrome) {
        super(owner, "BlitzForge", true);
        setPreferredSize(new Dimension(1000, 800));
        this.chrome = chrome;
        initComponents();
        setupKeyBindings();
        modelComboBox.addActionListener(e -> {
            updateTokenWarningLabel();
            updateCostEstimate();
        });
        includeWorkspaceCheckbox.addActionListener(e -> {
            updateTokenWarningLabel();
            updateCostEstimate();
        });
        updateTokenWarningLabel();
        updateCostEstimate();
        fetchUserBalance();
        pack();
        setLocationRelativeTo(owner);
    }

    private void initComponents() {
        setLayout(new BorderLayout(10, 10));

        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new GridBagLayout());
        // Initialize early so it is non-null when updateCostEstimate() is triggered during UI construction
        selectedFilesCountLabel = new JLabel("0 files selected");
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Context + Post-processing option panels
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.weighty = 0.15;
        gbc.fill = GridBagConstraints.BOTH;

        JPanel combined = new JPanel(new GridLayout(1, 2, H_GAP, 0));

        // ---- parallel processing panel --------------------------------
        JPanel parallelProcessingPanel = new JPanel(new GridBagLayout());
        var ppTitleBorder = BorderFactory.createTitledBorder("Parallel processing");
        parallelProcessingPanel.setBorder(
                BorderFactory.createCompoundBorder(ppTitleBorder, BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        GridBagConstraints paraGBC = new GridBagConstraints();
        paraGBC.insets = new Insets(0, 0, 0, 0); // Removed per-cell insets
        paraGBC.fill = GridBagConstraints.HORIZONTAL;

        // Instructions label and info icon
        paraGBC.gridx = 0;
        paraGBC.gridy = 0;
        paraGBC.gridwidth = 3;
        paraGBC.weightx = 1.0; // Allow it to expand horizontally
        paraGBC.weighty = 0;
        paraGBC.anchor = GridBagConstraints.WEST;

        var instructionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); // explicit H_GAP components
        var instructionsLabel = new JLabel("Instructions:");
        instructionsPanel.add(instructionsLabel);

        // spacing between label and History button
        instructionsPanel.add(Box.createHorizontalStrut(H_GAP));

        // History dropdown button (scaled to label height)
        MaterialButton blitzHistoryButton = new MaterialButton("History ▼");
        blitzHistoryButton.setToolTipText("Select a previous BlitzForge instruction");
        blitzHistoryButton.addActionListener(ev -> showBlitzHistoryMenu(blitzHistoryButton));

        var labelHeight = instructionsLabel.getPreferredSize().height;
        var btnSize = blitzHistoryButton.getPreferredSize();
        blitzHistoryButton.setMargin(new Insets(0, 5, 0, 5)); // small horizontal padding
        Dimension newSize = new Dimension(btnSize.width, labelHeight - 4);
        blitzHistoryButton.setPreferredSize(newSize);
        blitzHistoryButton.setMinimumSize(newSize);
        blitzHistoryButton.setMaximumSize(newSize);

        instructionsPanel.add(blitzHistoryButton);
        instructionsPanel.add(Box.createHorizontalStrut(H_GAP));

        var infoIcon = new JLabel(smallInfoIcon);
        infoIcon.setToolTipText(
                """
                                        <html>
                                        BlitzForge applies your instructions independently to multiple files in parallel, with optional
                                        post-processing by a single agent.
                                        </html>
                                        """);
        instructionsPanel.add(infoIcon);
        parallelProcessingPanel.add(instructionsPanel, paraGBC);

        // Instructions TextArea
        paraGBC.gridy = 1;
        paraGBC.gridx = 0;
        paraGBC.gridwidth = 3;
        paraGBC.weightx = 1.0;
        paraGBC.weighty = 1.0;
        paraGBC.fill = GridBagConstraints.BOTH;
        instructionsArea = new JTextArea(4, 50);
        instructionsArea.setLineWrap(true);
        instructionsArea.setWrapStyleWord(true);
        JScrollPane instructionsScrollPane = new JScrollPane(instructionsArea);
        parallelProcessingPanel.add(instructionsScrollPane, paraGBC);

        // Action Row
        paraGBC.gridy++;
        paraGBC.insets = new Insets(V_GAP, H_GLUE, 0, 0);
        paraGBC.gridx = 0;
        paraGBC.gridwidth = 1;
        paraGBC.weightx = 0.0;
        paraGBC.weighty = 0;
        paraGBC.fill = GridBagConstraints.NONE;
        paraGBC.anchor = GridBagConstraints.EAST;
        parallelProcessingPanel.add(new JLabel("Action"), paraGBC);

        paraGBC.gridx = 2;
        paraGBC.weightx = 0.0;
        paraGBC.fill = GridBagConstraints.NONE;
        paraGBC.anchor = GridBagConstraints.WEST;
        actionComboBox = new JComboBox<>(new String[] {"Code", "Ask"});
        actionComboBox.setSelectedItem("Code");
        parallelProcessingPanel.add(actionComboBox, paraGBC);

        // Model Row
        paraGBC.gridy++;
        paraGBC.insets = new Insets(V_GAP, H_GLUE, 0, 0);
        paraGBC.gridx = 0;
        paraGBC.gridwidth = 1;
        paraGBC.weightx = 0.0;
        paraGBC.weighty = 0;
        paraGBC.fill = GridBagConstraints.NONE;
        paraGBC.anchor = GridBagConstraints.EAST;
        parallelProcessingPanel.add(new JLabel("Model"), paraGBC);

        paraGBC.gridx = 2;
        paraGBC.weightx = 0.0;
        paraGBC.fill = GridBagConstraints.NONE;
        paraGBC.anchor = GridBagConstraints.WEST;
        List<Service.FavoriteModel> favoriteModels = MainProject.loadFavoriteModels();
        favoriteModels.sort((m1, m2) -> m1.alias().compareToIgnoreCase(m2.alias()));
        modelComboBox = new JComboBox<>(favoriteModels.toArray(new Service.FavoriteModel[0]));
        // Add colored-dot speed indicator next to each model alias
        var service = chrome.getContextManager().getService();
        modelComboBox.setRenderer(new DefaultListCellRenderer() {
            private final Icon redDot = new ColorDotIcon(Color.RED, 10);
            private final Icon yellowDot = new ColorDotIcon(Color.YELLOW, 10);
            private final Icon greenDot = new ColorDotIcon(new Color(0, 170, 0), 10); // deeper green for visibility

            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                // Use default renderer for basic background/selection handling
                super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus);
                var fav = (Service.FavoriteModel) value;
                var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
                panel.setOpaque(true);
                panel.setBackground(getBackground());

                // Determine metric + color
                var model = service.getModel(fav.config());
                Color color = Color.GRAY;
                String tooltip = "unknown capacity";
                if (model != null) {
                    Integer maxConc = service.getMaxConcurrentRequests(model);
                    if (maxConc != null) {
                        tooltip = "%,d max concurrent requests".formatted(maxConc);
                        color = (maxConc <= 5) ? Color.RED : (maxConc <= 50) ? Color.YELLOW : new Color(0, 170, 0);
                    } else {
                        Integer tpm = service.getTokensPerMinute(model);
                        if (tpm != null) {
                            tooltip = "%,d tokens/minute".formatted(tpm);
                            color = (tpm <= 1_000_000)
                                    ? Color.RED
                                    : (tpm <= 10_000_000) ? Color.YELLOW : new Color(0, 170, 0);
                        }
                    }
                }

                Icon dot =
                        switch (color.getRGB()) {
                            case 0xFFFF0000 -> redDot;
                            case 0xFFFFFF00 -> yellowDot;
                            default -> greenDot;
                        };

                panel.setToolTipText(tooltip);
                panel.add(new JLabel(dot));
                var name = new JLabel(fav.alias());
                name.setForeground(getForeground());
                panel.add(name);
                return panel;
            }
        });
        if (!favoriteModels.isEmpty()) {
            modelComboBox.setSelectedIndex(0);
        }
        // Use FlowLayout with 0 horizontal gap to ensure modelComboBox starts at the cell's left edge
        JPanel modelCostPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        modelCostPanel.add(modelComboBox);
        modelCostPanel.add(Box.createHorizontalStrut(H_GAP)); // Add explicit horizontal gap
        costEstimateLabel = new JLabel(" ");
        modelCostPanel.add(costEstimateLabel);
        parallelProcessingPanel.add(modelCostPanel, paraGBC);

        // Token Warning Label
        paraGBC.gridy++;
        paraGBC.gridx = 2;
        paraGBC.anchor = GridBagConstraints.WEST;
        tokenWarningLabel = new JLabel();
        tokenWarningLabel.setForeground(Color.RED);
        tokenWarningLabel.setVisible(false);
        parallelProcessingPanel.add(tokenWarningLabel, paraGBC);

        // Per-file command
        paraGBC.gridy++;
        paraGBC.gridx = 0;
        paraGBC.anchor = GridBagConstraints.EAST;
        paraGBC.weightx = 0;
        parallelProcessingPanel.add(new JLabel("Per-file command"), paraGBC);
        paraGBC.gridx = 1;
        paraGBC.anchor = GridBagConstraints.WEST;
        var perFileIconCtx = new JLabel(smallInfoIcon);
        perFileIconCtx.setToolTipText(
                """
                                              <html>
                                              Command to run for each file.<br>
                                              Use {{filepath}} for the file path. Blank for no command.
                                              <br>The output will be sent to the LLM with each target file.
                                              </html>
                                              """);
        parallelProcessingPanel.add(perFileIconCtx, paraGBC);
        // Per-file command input on the same row, spanning the remaining column(s)
        paraGBC.gridx = 2;
        paraGBC.weightx = 1.0;
        paraGBC.fill = GridBagConstraints.HORIZONTAL;
        perFileCommandTextField = new JTextField();
        parallelProcessingPanel.add(perFileCommandTextField, paraGBC);
        // reset for subsequent rows
        paraGBC.weightx = 0.0;
        paraGBC.fill = GridBagConstraints.NONE;
        paraGBC.gridwidth = 1;

        // Related files
        paraGBC.gridy++;
        paraGBC.gridx = 0;
        paraGBC.weightx = 0;
        paraGBC.anchor = GridBagConstraints.EAST;
        parallelProcessingPanel.add(new JLabel("Related files"), paraGBC);
        paraGBC.gridx = 1;
        paraGBC.anchor = GridBagConstraints.WEST;
        var relatedIcon = new JLabel(smallInfoIcon);
        relatedIcon.setToolTipText("Includes summaries of the most closely related files for each target file");
        parallelProcessingPanel.add(relatedIcon, paraGBC);
        paraGBC.gridx = 2;
        relatedClassesCombo = new JComboBox<>(new String[] {"0", "5", "10", "20"});
        relatedClassesCombo.addActionListener(e -> updateCostEstimate());
        relatedClassesCombo.setEditable(true);
        relatedClassesCombo.setSelectedItem("0");
        parallelProcessingPanel.add(relatedClassesCombo, paraGBC);

        // Workspace
        paraGBC.gridy++;
        paraGBC.gridx = 0;
        paraGBC.anchor = GridBagConstraints.EAST;
        parallelProcessingPanel.add(new JLabel("Workspace"), paraGBC);
        paraGBC.gridx = 1;
        paraGBC.anchor = GridBagConstraints.WEST;
        var wsIcon = new JLabel(smallInfoIcon);
        wsIcon.setToolTipText("Include the current Workspace contents with each file");
        parallelProcessingPanel.add(wsIcon, paraGBC);
        paraGBC.gridx = 2;
        includeWorkspaceCheckbox = new JCheckBox();
        parallelProcessingPanel.add(includeWorkspaceCheckbox, paraGBC);

        // Post-processing panel
        JPanel ppPanel = new JPanel(new GridBagLayout());
        var postTitleBorder = BorderFactory.createTitledBorder("Post-processing");
        ppPanel.setBorder(
                BorderFactory.createCompoundBorder(postTitleBorder, BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        GridBagConstraints ppGBC = new GridBagConstraints();
        ppGBC.insets = new Insets(0, 0, 0, 0); // Removed per-cell insets
        ppGBC.fill = GridBagConstraints.HORIZONTAL;
        ppGBC.anchor = GridBagConstraints.WEST;

        // Instructions area at top
        ppGBC.gridx = 0;
        ppGBC.gridy = 0;
        ppGBC.gridwidth = 3;
        ppPanel.add(new JLabel("Instructions:"), ppGBC);

        ppGBC.gridy++;
        postProcessingInstructionsArea = new JTextArea(4, 30);
        postProcessingInstructionsArea.setLineWrap(true);
        postProcessingInstructionsArea.setWrapStyleWord(true);
        JScrollPane postProcessingScrollPane = new JScrollPane(postProcessingInstructionsArea);
        ppGBC.weightx = 1.0;
        ppGBC.weighty = 1.0;
        ppGBC.fill = GridBagConstraints.BOTH;
        ppPanel.add(postProcessingScrollPane, ppGBC);
        ppGBC.weighty = 0; // reset
        ppGBC.weightx = 0;
        ppGBC.fill = GridBagConstraints.HORIZONTAL;
        ppGBC.gridwidth = 1;

        // Action panel (runPostProcessCombo, model label, build checkbox)
        ppGBC.gridy++;
        ppGBC.gridx = 0;
        ppGBC.gridwidth = 3;
        var actionPanel = new JPanel(new GridBagLayout());
        actionPanel.setBorder(BorderFactory.createTitledBorder("Action"));
        GridBagConstraints actGbc = new GridBagConstraints();
        actGbc.insets = new Insets(5, 5, 5, 5);
        actGbc.anchor = GridBagConstraints.WEST;
        actGbc.fill = GridBagConstraints.NONE;
        actGbc.gridx = 0;
        actGbc.gridy = 0;

        var noneInfoIcon = new JLabel(smallInfoIcon);
        noneInfoIcon.setToolTipText("Optionally select Agent or Ask to run after parallel processing");

        GridBagConstraints iconGbc = new GridBagConstraints();
        iconGbc.insets = new Insets(5, 5, 5, 5);
        iconGbc.anchor = GridBagConstraints.WEST;
        iconGbc.fill = GridBagConstraints.NONE;
        iconGbc.gridx = 0;
        iconGbc.gridy = 0;
        iconGbc.weightx = 0.0;
        actionPanel.add(noneInfoIcon, iconGbc);

        // Combo box and Model label row
        runPostProcessCombo = new JComboBox<>(new String[] {"None", "Agent", "Ask"});
        postProcessingModelLabel = new JLabel(" ");

        // Determine the maximum width needed for the model label
        var tempLabel = new JLabel();
        var fm = tempLabel.getFontMetrics(tempLabel.getFont());
        var cm = chrome.getContextManager();
        String selectedModelName = service.nameOf(chrome.getInstructionsPanel().getSelectedModel());
        int maxWidth = fm.stringWidth("Model: " + selectedModelName);

        // Use a GridBagLayout for the combo and label to allow the label to shrink
        // but set a minimum size based on calculated max width.
        JPanel comboAndModelPanel = new JPanel(new GridBagLayout());
        GridBagConstraints cAmpGbc = new GridBagConstraints();
        cAmpGbc.insets = new Insets(0, H_GAP, 0, 0); // Horizontal gap for the model label

        // Add the runPostProcessCombo
        cAmpGbc.gridx = 0;
        cAmpGbc.gridy = 0;
        cAmpGbc.weightx = 0.0;
        cAmpGbc.fill = GridBagConstraints.NONE;
        cAmpGbc.anchor = GridBagConstraints.WEST;
        comboAndModelPanel.add(runPostProcessCombo, cAmpGbc);

        // Add the postProcessingModelLabel
        cAmpGbc.gridx = 1;
        cAmpGbc.gridy = 0;
        cAmpGbc.weightx = 1.0; // Allow the label to take extra space
        cAmpGbc.fill = GridBagConstraints.HORIZONTAL;
        cAmpGbc.anchor = GridBagConstraints.WEST;
        postProcessingModelLabel.setMinimumSize(
                new Dimension(maxWidth, postProcessingModelLabel.getPreferredSize().height));
        postProcessingModelLabel.setPreferredSize(new Dimension(
                maxWidth, postProcessingModelLabel.getPreferredSize().height)); // Set preferred to minimum
        comboAndModelPanel.add(postProcessingModelLabel, cAmpGbc);

        GridBagConstraints comboModelGbc = new GridBagConstraints();
        comboModelGbc.insets = new Insets(5, 5, 5, 5);
        comboModelGbc.anchor = GridBagConstraints.WEST;
        comboModelGbc.fill = GridBagConstraints.HORIZONTAL; // This panel can fill horizontally
        comboModelGbc.gridx = 1; // Align with the info icon's column for consistency
        comboModelGbc.gridy = 0;
        comboModelGbc.weightx = 1.0; // Allow this panel to take horizontal space
        actionPanel.add(comboAndModelPanel, comboModelGbc);

        // build-first checkbox on its own row, aligned with column 1
        GridBagConstraints buildCheckboxGbc = new GridBagConstraints();
        buildCheckboxGbc.insets = new Insets(5, 5, 5, 5);
        buildCheckboxGbc.anchor = GridBagConstraints.WEST;
        buildCheckboxGbc.fill = GridBagConstraints.NONE;
        buildCheckboxGbc.gridx = 1;
        buildCheckboxGbc.gridy = 1; // This is now row 1 as combo/model are on row 0
        buildCheckboxGbc.weightx = 0.0;
        buildFirstCheckbox = new JCheckBox("Build project first");
        buildFirstCheckbox.setToolTipText(
                "Run the project's build/verification command before invoking post-processing"); // Kept for when it IS
        // enabled
        buildFirstCheckbox.setEnabled(false);

        // Build first info icon (only visible when no build command)
        JLabel buildFirstInfoIcon = new JLabel(smallInfoIcon);
        buildFirstInfoIcon.setToolTipText("No build/verification command available");
        buildFirstInfoIcon.setVisible(false); // Initially hidden

        JPanel buildFirstPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buildFirstPanel.add(buildFirstCheckbox);

        // Place the info icon in the first column, aligned under the Action icon
        GridBagConstraints buildIconGbc = new GridBagConstraints();
        buildIconGbc.insets = new Insets(5, 5, 5, 5);
        buildIconGbc.anchor = GridBagConstraints.WEST;
        buildIconGbc.fill = GridBagConstraints.NONE;
        buildIconGbc.gridx = 0;
        buildIconGbc.gridy = 1;
        actionPanel.add(buildFirstInfoIcon, buildIconGbc);

        actionPanel.add(buildFirstPanel, buildCheckboxGbc);

        ppPanel.add(actionPanel, ppGBC);
        ppGBC.gridwidth = 1;

        // Parallel-processing output panel (include radios + filter)
        ppGBC.gridy++;
        ppGBC.gridx = 0;
        ppGBC.gridwidth = 3;
        var outputPanel = new JPanel(new GridBagLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder("Parallel processing output"));
        GridBagConstraints opGBC = new GridBagConstraints();
        opGBC.insets = new Insets(5, 5, 5, 5);
        opGBC.anchor = GridBagConstraints.WEST;
        opGBC.fill = GridBagConstraints.NONE;

        parallelOutputCombo = new JComboBox<>(new String[] {"Include none", "Include all", "Include changed files"});
        parallelOutputCombo.setSelectedIndex(0);

        int opRow = 0;

        // Combo
        opGBC.gridx = 0;
        opGBC.gridy = opRow++;
        opGBC.gridwidth = 3;
        outputPanel.add(parallelOutputCombo, opGBC);

        // Filter row – label
        GridBagConstraints filterLabelGbc = new GridBagConstraints();
        filterLabelGbc.insets = new Insets(5, 5, 5, 5);
        filterLabelGbc.gridx = 0;
        filterLabelGbc.gridy = opRow;
        filterLabelGbc.anchor = GridBagConstraints.EAST;
        outputPanel.add(new JLabel("Filter"), filterLabelGbc);

        // Filter row – info bubble
        GridBagConstraints filterInfoGbc = new GridBagConstraints();
        filterInfoGbc.insets = new Insets(5, H_GLUE, 5, 5);
        filterInfoGbc.gridx = 1;
        filterInfoGbc.gridy = opRow;
        filterInfoGbc.anchor = GridBagConstraints.WEST;
        var filterInfo = new JLabel(smallInfoIcon);
        filterInfo.setToolTipText(
                "Only include parallel processing for files whose output passes this natural language classifier");
        outputPanel.add(filterInfo, filterInfoGbc);

        // Filter row – input field
        GridBagConstraints filterFieldGbc = new GridBagConstraints();
        filterFieldGbc.insets = new Insets(5, H_GLUE, 5, 5);
        filterFieldGbc.gridx = 2;
        filterFieldGbc.gridy = opRow;
        filterFieldGbc.weightx = 1.0;
        filterFieldGbc.fill = GridBagConstraints.HORIZONTAL;
        contextFilterTextField = new JTextField(20);
        outputPanel.add(contextFilterTextField, filterFieldGbc);

        ppPanel.add(outputPanel, ppGBC);
        ppGBC.gridwidth = 1;

        ActionListener postProcessListener = ev -> {
            String selectedOption = (String) runPostProcessCombo.getSelectedItem();
            boolean ask = "Ask".equals(selectedOption);
            boolean agent = "Agent".equals(selectedOption);
            boolean none = "None".equals(selectedOption);

            postProcessingInstructionsArea.setEnabled(!none);
            postProcessingScrollPane.setEnabled(!none);
            assert SwingUtilities.isEventDispatchThread();

            // Build-first checkbox handling (runs off-EDT via ContextManager)
            final @Nullable String option = selectedOption; // capture for async callback
            BuildAgent.determineVerificationCommandAsync(cm)
                    .thenAccept(verificationCommand -> SwingUtilities.invokeLater(() -> {
                        boolean hasVerification = !Objects.requireNonNullElse(verificationCommand, "")
                                .isBlank();

                        if (!hasVerification) {
                            buildFirstCheckbox.setEnabled(false);
                            buildFirstCheckbox.setSelected(false);
                            buildFirstInfoIcon.setVisible(true);
                        } else {
                            buildFirstCheckbox.setToolTipText(
                                    "Run the project's build/verification command before invoking post-processing");
                            buildFirstInfoIcon.setVisible(false);

                            switch (option) {
                                case "Agent" -> {
                                    buildFirstCheckbox.setEnabled(true);
                                    buildFirstCheckbox.setSelected(true);
                                }
                                case "Ask" -> {
                                    buildFirstCheckbox.setEnabled(true);
                                    buildFirstCheckbox.setSelected(false);
                                }
                                default -> {
                                    buildFirstCheckbox.setEnabled(false);
                                    buildFirstCheckbox.setSelected(false);
                                }
                            }
                        }
                    }));

            // Parallel-output combo defaults
            if (ask) {
                parallelOutputCombo.setSelectedItem("Include all");
            } else if (agent) {
                parallelOutputCombo.setSelectedItem("Include changed files");
                if (postProcessingInstructionsArea.getText().trim().isEmpty()) {
                    postProcessingInstructionsArea.setText("Fix any build errors");
                }
            } else { // None
                parallelOutputCombo.setSelectedItem("Include none");
            }

            // Model label
            String modelName =
                    cm.getService().nameOf(chrome.getInstructionsPanel().getSelectedModel());
            postProcessingModelLabel.setText("Model: " + modelName);
        };
        runPostProcessCombo.addActionListener(postProcessListener);
        postProcessListener.actionPerformed(new ActionEvent(runPostProcessCombo, ActionEvent.ACTION_PERFORMED, ""));

        // Add both panels
        combined.add(parallelProcessingPanel);
        combined.add(ppPanel);
        contentPanel.add(combined, gbc);

        // Scope Panel: Files table + left actions (top-left aligned)
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weighty = 0.1;
        gbc.fill = GridBagConstraints.BOTH;

        JPanel scopePanel = createScopePanel();
        contentPanel.add(scopePanel, gbc);
        add(contentPanel, BorderLayout.CENTER);

        // Buttons Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var okButton = new MaterialButton("OK");
        var cancelButton = new MaterialButton("Cancel");

        // Style OK button as primary action (bright blue with white text)
        io.github.jbellis.brokk.gui.SwingUtil.applyPrimaryButtonStyle(okButton);

        okButton.addActionListener(e -> onOK());
        cancelButton.addActionListener(e -> setVisible(false));

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel createScopePanel() {
        JPanel scopePanel = new JPanel(new BorderLayout(H_GAP, V_GAP));
        var scopeTitle = BorderFactory.createTitledBorder("Scope");
        scopePanel.setBorder(
                BorderFactory.createCompoundBorder(scopeTitle, BorderFactory.createEmptyBorder(5, 5, 5, 5)));

        JPanel scopeMain = new JPanel(new GridBagLayout());

        // --- right column (files table) ---
        JPanel rightPanel = new JPanel(new BorderLayout(0, 5));
        tableModel = new javax.swing.table.DefaultTableModel(new String[] {"File"}, 0);
        selectedFilesTable = new JTable(tableModel);
        selectedFilesTable.setFillsViewportHeight(true);
        selectedFilesTable.setToolTipText("Tip: You can paste a list of files here (Ctrl+V)");
        selectedFilesSorter = new TableRowSorter<>(tableModel);
        selectedFilesTable.setRowSorter(selectedFilesSorter);
        selectedFilesSorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));

        JScrollPane tableScrollPane = new JScrollPane(selectedFilesTable);
        // Remove the LAF border so the table aligns with the uniform 5px scope inset
        tableScrollPane.setBorder(BorderFactory.createEmptyBorder());
        rightPanel.add(tableScrollPane, BorderLayout.CENTER);

        BorderUtils.addFocusBorder(tableScrollPane, selectedFilesTable);

        // Focus delegation: clicking on table/scrollpane/rightPanel focuses the table
        MouseAdapter focusMouseListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                selectedFilesTable.requestFocusInWindow();
            }
        };
        selectedFilesTable.addMouseListener(focusMouseListener);
        tableScrollPane.addMouseListener(focusMouseListener);
        tableScrollPane.getViewport().addMouseListener(focusMouseListener);
        rightPanel.addMouseListener(focusMouseListener);

        // Right-click context menu for paste
        MouseAdapter tableContextMenu = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showTableContextMenu(selectedFilesTable, e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showTableContextMenu(selectedFilesTable, e);
                }
            }
        };
        selectedFilesTable.addMouseListener(tableContextMenu);

        // Bottom action bar: left (entire project + language), right (attach files, remove)
        // Left side
        MaterialButton addEntireButton = new MaterialButton("Add entire project");
        languageComboBox = new JComboBox<>();
        languageComboBox.addItem(ALL_LANGUAGES_OPTION);
        chrome.getProject().getAnalyzerLanguages().stream()
                .map(Language::toString)
                .sorted()
                .forEach(languageComboBox::addItem);
        languageComboBox.setSelectedItem(ALL_LANGUAGES_OPTION);
        JPanel leftButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, H_GAP, 0));
        leftButtonsPanel.add(addEntireButton);
        leftButtonsPanel.add(languageComboBox);

        // Right side
        MaterialButton attachFilesButton = new MaterialButton();
        attachFilesButton.setIcon(Icons.ATTACH_FILE);
        attachFilesButton.setToolTipText("Attach files");
        MaterialButton removeButton = new MaterialButton();
        removeButton.setIcon(Icons.REMOVE);
        removeButton.setToolTipText("Remove Selected");
        removeButton.addActionListener(e -> removeSelectedFilesFromTable());
        JPanel rightButtonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, H_GAP, 0));
        rightButtonsPanel.add(attachFilesButton);
        rightButtonsPanel.add(removeButton);

        // Combine left and right into one row
        JPanel bottomButtonsPanel = new JPanel(new BorderLayout());
        bottomButtonsPanel.add(leftButtonsPanel, BorderLayout.WEST);
        bottomButtonsPanel.add(rightButtonsPanel, BorderLayout.EAST);

        // Count label in a separate row below the buttons
        JPanel countRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        countRow.add(selectedFilesCountLabel);

        JPanel bottomContainer = new JPanel();
        bottomContainer.setLayout(new BoxLayout(bottomContainer, BoxLayout.Y_AXIS));
        bottomContainer.add(bottomButtonsPanel);
        bottomContainer.add(countRow);

        rightPanel.add(bottomContainer, BorderLayout.SOUTH);

        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        var pasteStroke = KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask);
        var pasteShiftInsert = KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK);
        var tableInputMap = selectedFilesTable.getInputMap(JComponent.WHEN_FOCUSED);
        tableInputMap.put(pasteStroke, "paste-files");
        tableInputMap.put(pasteShiftInsert, "paste-files");
        selectedFilesTable.getActionMap().put("paste-files", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    var content = (String)
                            Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                    addRelPathsFromText(content);
                } catch (Exception ex) {
                    logger.debug("Failed to paste files from clipboard", ex);
                }
            }
        });

        GridBagConstraints rightCol = new GridBagConstraints();
        rightCol.gridx = 0;
        rightCol.gridy = 0;
        rightCol.weightx = 1.0;
        rightCol.weighty = 1.0;
        rightCol.fill = GridBagConstraints.BOTH;
        rightCol.anchor = GridBagConstraints.NORTHWEST;
        scopeMain.add(rightPanel, rightCol);

        scopePanel.add(scopeMain, BorderLayout.CENTER);

        // Wire actions
        addEntireButton.addActionListener(e -> {
            var files = chrome.getProject().getRepo().getTrackedFiles().stream().filter(ProjectFile::isText);
            String langSel = Objects.toString(languageComboBox.getSelectedItem(), ALL_LANGUAGES_OPTION);
            var filtered = ALL_LANGUAGES_OPTION.equals(langSel)
                    ? files
                    : files.filter(pf -> langSel.equals(
                            Languages.fromExtension(pf.extension()).toString()));
            addProjectFilesToTable(filtered.toList());
        });
        attachFilesButton.addActionListener(e -> openAttachFilesDialog());

        return scopePanel;
    }

    /**
     * Display a dropdown menu containing recent BlitzForge instructions. Selecting an entry populates the Instructions
     * and Post-processing fields.
     */
    private void showBlitzHistoryMenu(Component invoker) {
        var historyEntries = chrome.getProject().loadBlitzHistory();
        JPopupMenu popup = new JPopupMenu();

        if (historyEntries.isEmpty()) {
            JMenuItem empty = new JMenuItem("(No history items)");
            empty.setEnabled(false);
            popup.add(empty);
        } else {
            for (int i = historyEntries.size() - 1; i >= 0; i--) {
                var entry = historyEntries.get(i);
                String firstLine = entry.isEmpty() ? "" : entry.get(0).replace('\n', ' ');
                String display = firstLine.length() > HISTORY_TRUNCATION_LENGTH
                        ? firstLine.substring(0, HISTORY_TRUNCATION_LENGTH) + "..."
                        : firstLine;

                JMenuItem item = new JMenuItem(display);

                // Build tooltip with all saved lines
                StringBuilder tooltip = new StringBuilder("<html><pre>");
                for (var line : entry) {
                    tooltip.append(line.replace("&", "&amp;")
                                    .replace("<", "&lt;")
                                    .replace(">", "&gt;")
                                    .replace("\"", "&quot;"))
                            .append("<br><br>");
                }
                tooltip.append("</pre></html>");
                item.setToolTipText(tooltip.toString());

                int idx = i; // capture for lambda
                item.addActionListener(e -> {
                    var selected = historyEntries.get(idx);
                    if (!selected.isEmpty()) {
                        instructionsArea.setText(selected.get(0));
                        if (selected.size() > 1) {
                            postProcessingInstructionsArea.setText(selected.get(1));
                        }
                    }
                });
                popup.add(item);
            }
        }

        // Removed direct access to package-private field; popup will still work
        popup.show(invoker, 0, invoker.getHeight());
    }

    private void updateCostEstimate() {
        var cm = chrome.getContextManager();
        var service = cm.getService();
        var fav = (Service.FavoriteModel) modelComboBox.getSelectedItem();
        requireNonNull(fav);

        var pricing = service.getModelPricing(fav.config().name());

        List<ProjectFile> files = getSelectedFilesForCost();
        int n = files.size();
        selectedFilesCountLabel.setText(n + " file" + (n == 1 ? "" : "s") + " selected");
        if (n == 0) {
            costEstimateLabel.setText(" ");
            return;
        }

        long tokensFiles = files.parallelStream().mapToLong(this::getTokenCount).sum();
        double avgTokens = tokensFiles / (double) n;

        long workspaceTokens = includeWorkspaceCheckbox.isSelected()
                ? Messages.getApproximateMessageTokens(
                        io.github.jbellis.brokk.prompts.CodePrompts.instance.getWorkspaceContentsMessages(
                                cm.topContext()))
                : 0;
        long workspaceAdd = includeWorkspaceCheckbox.isSelected() ? workspaceTokens * n : 0;

        int relatedK = 0;
        try {
            var txt = Objects.toString(relatedClassesCombo.getEditor().getItem(), "")
                    .trim();
            if (!txt.isEmpty()) {
                relatedK = Integer.parseInt(txt);
            }
        } catch (NumberFormatException ex) {
            // Invalid number → treat as zero related classes
        }
        long relatedAdd = relatedK > 0 ? Math.round(n * relatedK * avgTokens * 0.1) : 0;

        long totalInput = tokensFiles + workspaceAdd + relatedAdd;
        long estOutput = Math.min(4000, totalInput / 2);
        double cost = pricing.estimateCost(totalInput, 0, estOutput);
        estimatedCost = cost;

        costEstimateLabel.setText(String.format("Cost Estimate: $%.2f", cost));
    }

    private void fetchUserBalance() {
        var cm = chrome.getContextManager();
        cm.submitBackgroundTask("Fetch balance", () -> cm.getService().getUserBalance())
                .thenAccept(balance -> userBalance = balance);
    }

    /** Returns the cached token count of a file, computing it once if necessary. */
    private long getTokenCount(ProjectFile pf) {
        return tokenCountCache.computeIfAbsent(pf, file -> {
            return (long) Messages.getApproximateTokens(file.read().orElse(""));
        });
    }

    /** Gather the currently selected files (no validation). */
    private List<ProjectFile> getSelectedFilesForCost() {
        try {
            List<ProjectFile> files = new ArrayList<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String rel = (String) tableModel.getValueAt(i, 0);
                files.add(chrome.getContextManager().toFile(rel));
            }
            return files;
        } catch (Exception e) {
            return List.of();
        }
    }

    /* ---------------- existing method ------------------------------ */
    private void updateTokenWarningLabel() {
        if (!includeWorkspaceCheckbox.isSelected()) {
            tokenWarningLabel.setVisible(false);
            return;
        }

        var cm = chrome.getContextManager();
        var service = cm.getService();

        var favModel = (Service.FavoriteModel) modelComboBox.getSelectedItem();
        if (favModel == null) {
            tokenWarningLabel.setVisible(false);
            return;
        }

        var model = service.getModel(new Service.ModelConfig(
                favModel.config().name(), favModel.config().reasoning()));
        if (model == null) {
            tokenWarningLabel.setVisible(false);
            return;
        }
        var maxTokens = service.getMaxInputTokens(model);

        var workspaceTokens = Messages.getApproximateMessageTokens(
                CodePrompts.instance.getWorkspaceContentsMessages(cm.topContext()));
        var historyTokens = Messages.getApproximateMessageTokens(cm.getHistoryMessages());

        long remaining = (long) maxTokens - workspaceTokens - historyTokens;

        if (remaining < TOKEN_SAFETY_MARGIN) {
            tokenWarningLabel.setText(
                    "<html><b>Warning:</b> The selected model has a " + maxTokens + "-token window. Your workspace ("
                            + workspaceTokens + " tokens) " + "+ history ("
                            + historyTokens + " tokens) leaves only " + remaining + " tokens, which is below the "
                            + TOKEN_SAFETY_MARGIN + " token safety margin. "
                            + "Trim your workspace or choose a model with a larger context window.</html>");
            tokenWarningLabel.setVisible(true);
        } else {
            tokenWarningLabel.setVisible(false);
        }
    }

    private void setupKeyBindings() {
        // Allow Shift+Enter to insert a newline in the text area
        var inputMap = instructionsArea.getInputMap(JComponent.WHEN_FOCUSED);
        var actionMap = instructionsArea.getActionMap();
        var enterAction = actionMap.get("insert-break");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break-shifted");
        actionMap.put("insert-break-shifted", enterAction);

        // Allow Esc to close the dialog
        var rootPane = getRootPane();
        var escapeStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        java.awt.event.ActionListener actionListener = e -> setVisible(false);
        rootPane.registerKeyboardAction(actionListener, escapeStroke, JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void removeSelectedFilesFromTable() {
        int[] selectedRows = selectedFilesTable.getSelectedRows();
        // Remove rows in reverse order to prevent index shifting issues
        for (int i = selectedRows.length - 1; i >= 0; i--) {
            int modelIndex = selectedFilesTable.convertRowIndexToModel(selectedRows[i]);
            tableModel.removeRow(modelIndex);
        }
        selectedFilesTable.clearSelection();
        updateSelectedFilesCount();
        updateCostEstimate();
    }

    private void updateSelectedFilesCount() {
        int n = tableModel.getRowCount();
        selectedFilesCountLabel.setText(n + " file" + (n == 1 ? "" : "s") + " selected");
    }

    private void addProjectFilesToTable(Collection<ProjectFile> files) {
        Set<String> existingPaths = new HashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            existingPaths.add((String) tableModel.getValueAt(i, 0));
        }
        for (ProjectFile pf : files) {
            String rel = pf.getRelPath().toString();
            if (!existingPaths.contains(rel)) {
                tableModel.addRow(new Object[] {rel});
                existingPaths.add(rel);
            }
        }
        selectedFilesSorter.sort();
        updateSelectedFilesCount();
        updateCostEstimate();
    }

    private void addRelPathsFromText(String text) {
        if (text.isBlank()) {
            return;
        }
        var cm = chrome.getContextManager();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        Iterable<String> lines = Splitter.on('\n').split(normalized);
        Set<String> rels = new LinkedHashSet<>();
        for (String line : lines) {
            String rel = line.trim();
            if (rel.isEmpty()) continue;
            try {
                var pf = cm.toFile(rel);
                if (pf.isText()) {
                    rels.add(pf.getRelPath().toString());
                }
            } catch (Exception ex) {
                logger.debug("Skipping path {} while parsing pasted list", rel, ex);
            }
        }
        if (!rels.isEmpty()) {
            for (String r : rels) {
                tableModel.addRow(new Object[] {r});
            }
            selectedFilesSorter.sort();
            updateSelectedFilesCount();
            updateCostEstimate();
        }
    }

    private void showTableContextMenu(JTable table, MouseEvent e) {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.addActionListener(ev -> {
            try {
                var content = (String)
                        Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
                addRelPathsFromText(content);
            } catch (Exception ex) {
                logger.debug("Failed to paste files from clipboard via context menu", ex);
            }
        });
        popup.add(pasteItem);
        popup.show(table, e.getX(), e.getY());
    }

    private void openAttachFilesDialog() {
        var dlg = new AttachContextDialog(chrome.getFrame(), chrome.getContextManager(), false);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        var result = dlg.getSelection();
        if (result == null) {
            return;
        }
        Set<ContextFragment> fragments = result.fragments();
        var cm = chrome.getContextManager();
        cm.submitBackgroundTask("Attach files", () -> fragments.stream()
                        .flatMap(frag -> frag.files().stream())
                        .collect(Collectors.toCollection(ArrayList::new)))
                .thenAccept(flist -> SwingUtil.runOnEdt(() -> addProjectFilesToTable(flist)));
    }

    /** High-level action the engine is asked to perform. */
    public enum Action {
        CODE,
        ASK,
    }

    private void onOK() {
        String instructions = instructionsArea.getText().trim();
        if (instructions.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this, "Instructions cannot be empty", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        var perFileModelSelection = (Service.FavoriteModel) requireNonNull(modelComboBox.getSelectedItem());

        // Refresh cost estimate and warn if it is more than half the balance
        updateCostEstimate();
        if (!Float.isNaN(userBalance) && estimatedCost > userBalance / 2.0) {
            int choice = chrome.showConfirmDialog(
                    this,
                    String.format(
                            "The estimated cost is $%.2f, which exceeds half of your remaining balance ($%.2f).\nDo you want to continue?",
                            estimatedCost, (double) userBalance),
                    "Low Balance Warning",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
        }

        // Determine files to process (from the Files table)
        List<ProjectFile> filesToProcessList;
        if (tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(
                    this, "No files have been selected", "No Files", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<ProjectFile> selectedFiles = new java.util.ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String relPath = (String) tableModel.getValueAt(i, 0);
            selectedFiles.add(chrome.getContextManager().toFile(relPath));
        }
        filesToProcessList = selectedFiles;

        Integer relatedK = null;
        var txt =
                Objects.toString(relatedClassesCombo.getEditor().getItem(), "").trim();
        if (!txt.isEmpty()) {
            try {
                int kValue = Integer.parseInt(txt);
                if (kValue < 0) {
                    throw new NumberFormatException("Value must be non-negative");
                }
                if (kValue > 0) {
                    relatedK = kValue;
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Value for 'Include related classes' must be a non-negative integer",
                        "Input Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        String perFileCommandTemplate = perFileCommandTextField.getText().trim();
        if (perFileCommandTemplate.isEmpty()) {
            perFileCommandTemplate = null;
        }

        boolean includeWorkspace = includeWorkspaceCheckbox.isSelected();

        PostProcessingOption runOption =
                switch ((String) runPostProcessCombo.getSelectedItem()) {
                    case "Agent" -> PostProcessingOption.ARCHITECT;
                    case "Ask" -> PostProcessingOption.ASK;
                    default -> PostProcessingOption.NONE;
                };
        var selectedInclude = (String) parallelOutputCombo.getSelectedItem();
        var parallelOutputMode =
                switch (selectedInclude) {
                    case "Include none" -> BlitzForge.ParallelOutputMode.NONE;
                    case "Include changed files" -> BlitzForge.ParallelOutputMode.CHANGED;
                    default -> BlitzForge.ParallelOutputMode.ALL;
                };
        boolean buildFirst = buildFirstCheckbox.isSelected();
        String contextFilter = contextFilterTextField.getText().trim();
        String postProcessingInstructions =
                postProcessingInstructionsArea.getText().trim();

        if (runOption != PostProcessingOption.NONE && postProcessingInstructions.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Post-processing instructions cannot be empty when a post-processing action is selected.",
                    "Input Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        String action = (String) requireNonNull(actionComboBox.getSelectedItem());

        // Persist Blitz-history entry
        chrome.getProject().addToBlitzHistory(instructions, postProcessingInstructions, MAX_BLITZ_HISTORY_ITEMS);

        setVisible(false); // Hide this dialog

        // Build the execution config for the engine
        var cm = chrome.getContextManager();
        var service = cm.getService();
        StreamingChatModel perFileModel = requireNonNull(service.getModel(perFileModelSelection.config()));
        var engineOutputMode =
                switch (parallelOutputMode) {
                    case NONE -> BlitzForge.ParallelOutputMode.NONE;
                    case ALL -> BlitzForge.ParallelOutputMode.ALL;
                    case CHANGED -> BlitzForge.ParallelOutputMode.CHANGED;
                };
        var engineAction =
                switch (action.trim().toUpperCase(Locale.ROOT)) {
                    case "ASK" -> Action.ASK;
                    case "CODE" -> Action.CODE;
                    default -> Action.CODE;
                };

        // Snapshot values for lambda capture
        final Integer fRelatedKSupplier = relatedK;
        final boolean fIncludeWorkspaceForSupplier = includeWorkspace;

        // Post-processing capture
        final PostProcessingOption fRunOption = runOption;
        final BlitzForge.ParallelOutputMode fOutputMode = engineOutputMode;
        final String fPostProcessingInstructions = postProcessingInstructions;
        final List<ProjectFile> fFilesToProcess = filesToProcessList;

        BlitzForge.RunConfig runCfg = new BlitzForge.RunConfig(
                instructions,
                perFileModel,
                () -> {
                    if (fRelatedKSupplier != null) {
                        ContextFragment.SkeletonFragment acFragment;
                        try {
                            acFragment = cm.liveContext().buildAutoContext(fRelatedKSupplier);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return "";
                        }
                        if (!acFragment.text().isBlank()) {
                            return """
                            <related_classes>
                            The user requested to include the top %d related classes.

                            %s
                            </related_classes>
                            """
                                    .formatted(fRelatedKSupplier, acFragment.text());
                        }
                    }
                    return "";
                },
                () -> {
                    if (!fIncludeWorkspaceForSupplier) {
                        return "";
                    }
                    var ctx = cm.topContext();
                    var list = new ArrayList<ChatMessage>();
                    list.addAll(CodePrompts.instance.getWorkspaceContentsMessages(ctx));
                    list.addAll(CodePrompts.instance.getHistoryMessages(ctx));
                    var text = "";
                    for (var m : list) {
                        text += m + "\n";
                    }
                    return text;
                },
                contextFilter,
                engineOutputMode);

        // Snapshot locals for lambda capture
        final @Nullable Integer fRelatedK = relatedK;
        final @Nullable String fPerFileCmd = perFileCommandTemplate;
        final String fContextFilter = contextFilter;

        // Prepare listener dialog + cancel wiring
        var progressDialog = new BlitzForgeProgressDialog(chrome, cm::interruptLlmAction);

        // Kick off background execution
        var analyzerWrapper = cm.getAnalyzerWrapper();
        cm.submitLlmAction(() -> {
            analyzerWrapper.pause();
            try (var scope = cm.beginTask(instructions, false)) {
                var parallelResult = runParallel(
                        runCfg,
                        progressDialog,
                        filesToProcessList,
                        includeWorkspace,
                        fRelatedK,
                        fPerFileCmd,
                        engineAction,
                        instructions,
                        fContextFilter,
                        contextFilter);
                scope.append(parallelResult);

                // If the parallel phase was cancelled/interrupted, skip any post-processing (including build).
                if (parallelResult.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                    logger.debug("Parallel processing was interrupted; skipping post-processing.");
                    return;
                }

                var mainIo = cm.getIo();

                if (fRunOption == PostProcessingOption.NONE) {
                    return;
                }

                String buildFailureText;
                if (buildFirst) {
                    buildFailureText = BuildAgent.runVerification(cm);
                } else {
                    buildFailureText = "";
                }

                if (fPostProcessingInstructions.isEmpty() && buildFailureText.isEmpty()) {
                    logger.debug("Build successful or not run, and parallel output processing was not requested");
                    return;
                }

                var files = fFilesToProcess.stream().map(ProjectFile::toString).collect(Collectors.joining("\n"));

                var messages = parallelResult.output().messages();
                assert messages.isEmpty() || messages.size() == 2 : messages.size(); // by construction
                var effectiveOutputMode = messages.isEmpty() ? BlitzForge.ParallelOutputMode.NONE : fOutputMode;

                var parallelDetails =
                        switch (effectiveOutputMode) {
                            case NONE -> "The task was applied to the following files:\n```\n%s```".formatted(files);
                            case CHANGED -> {
                                var output = Messages.getText(messages.getLast());
                                yield "The parallel processing made changes to the following files:\n```\n%s```"
                                        .formatted(output);
                            }
                            default -> { // "all"
                                var output = Messages.getText(messages.getLast());
                                yield "The output from the parallel processing was:\n```\n%s```".formatted(output);
                            }
                        };

                var effectiveGoal = fPostProcessingInstructions.isBlank()
                        ? "Please fix the problems."
                        : "Here are the postprocessing instructions:\n```\n%s```"
                                .formatted(fPostProcessingInstructions);

                // Build the agent instructions WITHOUT embedding raw build output; Architect should consult
                // the Build Results fragment in the session context for full build logs/details.
                var agentInstructions =
                        """
                        I just finished a parallel upgrade task with the following instructions:
                        ```
                        %s
                        ```

                        %s

                        Build details and verification output are available in the session's Build Results fragment;
                        please consult it when fixing any remaining issues.

                        %s
                        """
                                .formatted(instructions, parallelDetails, effectiveGoal);

                TaskResult postProcessResult;
                if (fRunOption == PostProcessingOption.ASK) {
                    mainIo.systemNotify(
                            "Ask command has been invoked.",
                            "Post-processing",
                            javax.swing.JOptionPane.INFORMATION_MESSAGE);
                    postProcessResult = InstructionsPanel.executeAskCommand(cm, perFileModel, agentInstructions);
                } else {
                    mainIo.systemNotify(
                            "Architect has been invoked.",
                            "Post-processing",
                            javax.swing.JOptionPane.INFORMATION_MESSAGE);
                    var agent = new ArchitectAgent(
                            cm,
                            chrome.getInstructionsPanel().getSelectedModel(),
                            perFileModel,
                            agentInstructions,
                            scope);
                    postProcessResult = agent.executeWithSearch(scope);
                }
                scope.append(postProcessResult);
            } finally {
                analyzerWrapper.resume();
            }
        });
        // Show the progress dialog (modeless)
        progressDialog.setVisible(true);
    }

    private @NotNull TaskResult runParallel(
            BlitzForge.RunConfig runCfg,
            BlitzForgeProgressDialog progressDialog,
            List<ProjectFile> filesToProcessList,
            boolean fIncludeWorkspace,
            @Nullable Integer fRelatedK,
            @Nullable String fPerFileCmd,
            Action engineAction,
            String instructions,
            String fContextFilter,
            String contextFilter) {
        var cm = chrome.getContextManager();
        var service = cm.getService();
        var frozenContext = cm.topContext();
        var selectedFavorite = (Service.FavoriteModel) requireNonNull(modelComboBox.getSelectedItem());
        var model = requireNonNull(service.getModel(selectedFavorite.config()));

        // Engine + per-file processor
        var engine = new BlitzForge(cm, service, runCfg, progressDialog);

        // Per-file processor: mirrors the previous dialog's processSingleFile logic
        return engine.executeParallel(filesToProcessList, file -> {
            if (Thread.currentThread().isInterrupted()) {
                return new BlitzForge.FileResult(file, false, "Cancelled by user.", "");
            }

            var dialogIo = progressDialog.getConsoleIO(file);
            String errorMessage = null;

            List<ChatMessage> readOnlyMessages = new ArrayList<>();
            try {
                if (fIncludeWorkspace) {
                    readOnlyMessages.addAll(CodePrompts.instance.getWorkspaceContentsMessages(frozenContext));
                    readOnlyMessages.addAll(CodePrompts.instance.getHistoryMessages(frozenContext));
                }
                if (fRelatedK != null) {
                    var acFragment = cm.liveContext().buildAutoContext(fRelatedK);
                    if (!acFragment.text().isBlank()) {
                        var msgText =
                                """
                                <related_classes>
                                The user requested to include the top %d related classes.

                                %s
                                </related_classes>
                                """
                                        .formatted(fRelatedK, acFragment.text());
                        readOnlyMessages.add(new UserMessage(msgText));
                    }
                }

                if (fPerFileCmd != null && !fPerFileCmd.isBlank()) {
                    MustacheFactory mf = new DefaultMustacheFactory();
                    Mustache mustache = mf.compile(new StringReader(fPerFileCmd), "perFileCommand");
                    StringWriter writer = new StringWriter();
                    Map<String, Object> scope = new HashMap<>();
                    scope.put("filepath", file.toString());
                    mustache.execute(writer, scope);
                    String finalCommand = writer.toString();

                    String commandOutputText;
                    try {
                        String output = Environment.instance.runShellCommand(
                                finalCommand, cm.getProject().getRoot(), line -> {}, Environment.UNLIMITED_TIMEOUT);
                        commandOutputText =
                                """
                                <per_file_command_output command="%s">
                                %s
                                </per_file_command_output>
                                """
                                        .formatted(finalCommand, output);
                    } catch (Environment.SubprocessException ex) {
                        commandOutputText =
                                """
                                <per_file_command_output command="%s">
                                Error executing command: %s
                                Output (if any):
                                %s
                                </per_file_command_output>
                                """
                                        .formatted(finalCommand, ex.getMessage(), ex.getOutput());
                        dialogIo.toolError(
                                "Per-file command failed: " + ex.getMessage() + "\nOutput (if any):\n" + ex.getOutput(),
                                "Command Execution Error");
                    }
                    readOnlyMessages.add(new UserMessage(commandOutputText));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errorMessage = "Interrupted during message preparation.";
            } catch (Exception ex) {
                errorMessage = "Setup failed: " + ex.getMessage();
                dialogIo.toolError(errorMessage, "Setup");
            }

            if (errorMessage != null) {
                return new BlitzForge.FileResult(file, false, errorMessage, "");
            }

            // Run the task
            TaskResult tr;
            if (engineAction == Action.ASK) {
                var messages = CodePrompts.instance.getSingleFileAskMessages(cm, file, readOnlyMessages, instructions);
                var llm = cm.getLlm(model, "Ask", true);
                llm.setOutput(dialogIo);
                tr = InstructionsPanel.executeAskCommand(llm, messages, cm, instructions);
            } else {
                var agent = new CodeAgent(cm, model, dialogIo);
                tr = agent.runSingleFileEdit(file, instructions, readOnlyMessages);
            }

            if (tr.stopDetails().reason() == TaskResult.StopReason.INTERRUPTED) {
                Thread.currentThread().interrupt();
                errorMessage = "Processing interrupted.";
            } else if (tr.stopDetails().reason() != TaskResult.StopReason.SUCCESS) {
                errorMessage = "Processing failed: " + tr.stopDetails().reason()
                        + (tr.stopDetails().explanation().isEmpty()
                                ? ""
                                : " - " + tr.stopDetails().explanation());
                dialogIo.toolError(errorMessage, "Agent Processing Error");
            }

            boolean edited = tr.changedFiles().contains(file);
            String llmOutput = dialogIo.getLlmOutput();

            // Optional context filtering
            if (!fContextFilter.isBlank() && !llmOutput.isBlank()) {
                try {
                    var quickestModel = cm.getService().quickestModel();
                    var filterLlm = cm.getLlm(quickestModel, "ContextFilter");
                    filterLlm.setOutput(dialogIo);
                    boolean keep = RelevanceClassifier.isRelevant(filterLlm, contextFilter, llmOutput);
                    if (!keep) {
                        llmOutput = "";
                    }
                } catch (Exception e) {
                    // Non-fatal; keep output
                }
            }

            return new BlitzForge.FileResult(file, edited, errorMessage, llmOutput);
        });
    }
}
