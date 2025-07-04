package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.Service;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.Language;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.FileSelectionPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import io.github.jbellis.brokk.prompts.CodePrompts;
import io.github.jbellis.brokk.util.Messages;

import static java.util.Objects.requireNonNull;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

import io.github.jbellis.brokk.gui.dialogs.BlitzForgeProgressDialog.PostProcessingOption;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.jbellis.brokk.gui.Constants.H_GLUE;
import static io.github.jbellis.brokk.gui.Constants.H_GAP;

public class BlitzForgeDialog extends JDialog {
    private final Chrome chrome;
    private JTextArea instructionsArea;
    private JComboBox<Service.FavoriteModel> modelComboBox;
    private JLabel tokenWarningLabel;
    private JLabel costEstimateLabel;
    private JCheckBox includeWorkspaceCheckbox;
    private JRadioButton entireProjectScopeRadioButton;
    private JRadioButton selectFilesScopeRadioButton;
    private ButtonGroup scopeButtonGroup;
    private JPanel scopeCardsPanel;
    private CardLayout scopeCardLayout;
    private JComboBox<String> languageComboBox;
    private JComboBox<String> relatedClassesCombo;
    private JTextField perFileCommandTextField;
    private JTextArea postProcessingInstructionsArea;
    private JLabel postProcessingModelLabel;

    // Post-processing controls
    private JComboBox<String> runPostProcessCombo;
    private ButtonGroup parallelOutputGroup;
    private JRadioButton includeNoneRadio;
    private JRadioButton includeAllRadio;
    private JRadioButton includeChangedRadio;
    private JCheckBox buildFirstCheckbox;
    private JTextField contextFilterTextField;
    private static final String ALL_LANGUAGES_OPTION = "All Languages";
    private static final int TOKEN_SAFETY_MARGIN = 32768;

    // Tracks the most recent cost estimate and user balance
    private volatile double estimatedCost = 0.0;
    private volatile float userBalance    = Float.NaN;

    // Cache (file -> token count) to avoid recomputation on every UI refresh
    private final Map<ProjectFile, Long> tokenCountCache = new ConcurrentHashMap<>();

    private FileSelectionPanel fileSelectionPanel;
    private JTable selectedFilesTable;
    private javax.swing.table.DefaultTableModel tableModel;
    private JRadioButton listFilesScopeRadioButton;
    private JTextArea listFilesTextArea;
    // Components for the raw-text “List Files” card
    private javax.swing.table.DefaultTableModel parsedTableModel;
    private JTable parsedFilesTable;
    private JLabel parsedFilesCountLabel;
    private JComboBox<String> listLanguageCombo;
    private JLabel entireProjectFileCountLabel;

    private static final Icon smallInfoIcon;

    static {
        Icon baseIcon = UIManager.getIcon("OptionPane.informationIcon");
        requireNonNull(baseIcon);
        if (baseIcon instanceof ImageIcon ii) {
            Image img = ii.getImage().getScaledInstance(
                    (int) Math.round(ii.getIconWidth() * 0.5),
                    (int) Math.round(ii.getIconHeight() * 0.5),
                    Image.SCALE_SMOOTH);
            smallInfoIcon = new ImageIcon(img);
        } else {
            smallInfoIcon = new ScaledIcon(baseIcon, 0.5);
        }
    }

    /**
     * Icon wrapper that paints its delegate scaled by the given factor.
     */
    private static final class ScaledIcon implements Icon {
        private final Icon delegate;
        private final double factor;
        private final int width;
        private final int height;

        private ScaledIcon(Icon delegate, double factor)
        {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.factor = factor;
            this.width = (int) Math.round(delegate.getIconWidth() * factor);
            this.height = (int) Math.round(delegate.getIconHeight() * factor);
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.scale(factor, factor);
                delegate.paintIcon(c, g2, 0, 0);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }
    }

    /**
     * Simple filled-circle icon used for speed indicators.
     */
    private static final class ColorDotIcon implements Icon {
        private final Color color;
        private final int   diameter;

        private ColorDotIcon(Color color, int diameter) {
            this.color     = Objects.requireNonNull(color, "color");
            this.diameter  = diameter;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillOval(x, y, diameter, diameter);
            g.setColor(Color.DARK_GRAY);
            g.drawOval(x, y, diameter, diameter); // subtle outline
        }

        @Override
        public int getIconWidth()  { return diameter; }
        @Override
        public int getIconHeight() { return diameter; }
    }

    public BlitzForgeDialog(Frame owner, Chrome chrome) {
        super(owner, "BlitzForge", true);
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
        contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Explanation Label
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 3;
        JLabel explanationLabel = new JLabel("""
                                             <html>
                                             BlitzForge applies your instructions independently to multiple files in parallel, with optional
                                             post-processing by a single agent.
                                             </html>
                                             """);
        contentPanel.add(explanationLabel, gbc);


        // Scope Row
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        // Scope Cards Panel
        // This is now populated before being placed into the scope selection panel below
        scopeCardLayout = new CardLayout();
        scopeCardsPanel = new JPanel(scopeCardLayout);

        // "Entire Project" Card
        JPanel entireProjectPanel = new JPanel(new GridBagLayout()); // Changed to GridBagLayout
        GridBagConstraints epGBC = new GridBagConstraints();
        epGBC.insets = new Insets(0, 0, 0, 0); // Reset insets for this panel

        // Row 0: "Restrict to Language" Label and ComboBox
        epGBC.gridx = 0;
        epGBC.gridy = 0;
        epGBC.anchor = GridBagConstraints.WEST;
        epGBC.fill = GridBagConstraints.NONE;
        epGBC.weightx = 0.0;
        entireProjectPanel.add(new JLabel("Restrict to Language"), epGBC);

        epGBC.gridx = 1;
        epGBC.weightx = 1.0; // Allow combobox to take extra horizontal space
        epGBC.fill = GridBagConstraints.HORIZONTAL; // Fill horizontally
        languageComboBox = new JComboBox<>();
        languageComboBox.addItem(ALL_LANGUAGES_OPTION);
        chrome.getProject().getAnalyzerLanguages().stream()
                .map(Language::toString)
                .sorted()
                .forEach(languageComboBox::addItem);
        languageComboBox.setSelectedItem(ALL_LANGUAGES_OPTION);
        entireProjectPanel.add(languageComboBox, epGBC);
        // Action listener will be added later, after the new update method is defined

        // Row 1: File Count Label
        epGBC.gridx = 0;
        epGBC.gridy = 1;
        epGBC.gridwidth = 2; // Span across both columns
        epGBC.weightx = 1.0;
        epGBC.fill = GridBagConstraints.HORIZONTAL;
        epGBC.anchor = GridBagConstraints.WEST;
        entireProjectFileCountLabel = new JLabel(" ");
        entireProjectFileCountLabel.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0)); // Add a bit of top padding
        entireProjectPanel.add(entireProjectFileCountLabel, epGBC);

        // Spacer to push content to the top (remaining vertical space)
        epGBC.gridx = 0;
        epGBC.gridy = 2;
        epGBC.gridwidth = 2;
        epGBC.weighty = 1.0;
        epGBC.fill = GridBagConstraints.VERTICAL;
        entireProjectPanel.add(new JPanel(), epGBC); // Filler panel

        scopeCardsPanel.add(entireProjectPanel, "ENTIRE");

        // "Select Files" Card
        var fspConfig = new FileSelectionPanel.Config(
                chrome.getProject(),
                false, // no external files
                File::isFile,
                CompletableFuture.completedFuture(
                        chrome.getProject().getRepo().getTrackedFiles().stream()
                                .map(ProjectFile::absPath).map(Path::toAbsolutePath).toList()),
                true, // multi-select
                __ -> {
                },
                true, // include project files in autocomplete
                "Ctrl-Enter to add files to the list below."
        );
        fileSelectionPanel = new FileSelectionPanel(fspConfig);
        var inputComponent = fileSelectionPanel.getFileInputComponent();
        var addFilesAction = new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                addSelectedFilesToTable();
            }
        };
        var ctrlEnterStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
        inputComponent.getInputMap(JComponent.WHEN_FOCUSED).put(ctrlEnterStroke, "addFiles");
        inputComponent.getActionMap().put("addFiles", addFilesAction);

        JPanel selectFilesCardPanel = new JPanel(new BorderLayout(0, 5));
        selectFilesCardPanel.add(fileSelectionPanel, BorderLayout.NORTH);

        tableModel = new javax.swing.table.DefaultTableModel(new String[]{"File"}, 0);
        selectedFilesTable = new JTable(tableModel);

        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem removeItem = new JMenuItem("Remove");
        removeItem.addActionListener(e -> removeSelectedFilesFromTable());
        popupMenu.add(removeItem);
        selectedFilesTable.setComponentPopupMenu(popupMenu);

        selectedFilesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showPopup(e);
                }
            }

            private void showPopup(MouseEvent e) {
                int row = selectedFilesTable.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    // if the right-clicked row is not in the current selection, update the selection
                    if (!selectedFilesTable.isRowSelected(row)) {
                        selectedFilesTable.setRowSelectionInterval(row, row);
                    }
                }
                popupMenu.show(e.getComponent(), e.getX(), e.getY());
            }
        });

        JScrollPane tableScrollPane = new JScrollPane(selectedFilesTable);
        tableScrollPane.setPreferredSize(new Dimension(500, 120)); // Smaller height for the table
        selectFilesCardPanel.add(tableScrollPane, BorderLayout.CENTER);

        JPanel removeButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton removeButton = new JButton("Remove Selected");
        removeButton.addActionListener(e -> removeSelectedFilesFromTable());
        removeButtonPanel.add(removeButton);
        selectFilesCardPanel.add(removeButtonPanel, BorderLayout.SOUTH);

        scopeCardsPanel.add(selectFilesCardPanel, "SELECT");

        // "List Files" Card
        JPanel listFilesCardPanel = new JPanel(new BorderLayout(5, 5));

        // --- Left: raw-text area ---
        listFilesTextArea = new JTextArea(8, 40);
        listFilesTextArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateParsedFilesTable(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateParsedFilesTable(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateParsedFilesTable(); }
        });
        JScrollPane rawTextScroll = new JScrollPane(listFilesTextArea);

        // --- Right: parsed-file table with language filter ---
        parsedTableModel = new javax.swing.table.DefaultTableModel(new String[]{"File"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        parsedFilesTable = new JTable(parsedTableModel);
        JScrollPane parsedScroll = new JScrollPane(parsedFilesTable);

        /* top-right panel: language combo */
        JPanel rightTopPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, H_GAP, 0));
        rightTopPanel.add(new JLabel("Restrict to Language"));
        listLanguageCombo = new JComboBox<>();
        listLanguageCombo.addItem(ALL_LANGUAGES_OPTION);
        chrome.getProject().getAnalyzerLanguages().stream()
              .map(Language::toString)
              .sorted()
              .forEach(listLanguageCombo::addItem);
        listLanguageCombo.setSelectedItem(ALL_LANGUAGES_OPTION);
        listLanguageCombo.addActionListener(e -> {
            updateParsedFilesTable();
            updateCostEstimate();
        });
        rightTopPanel.add(listLanguageCombo);

        /* bottom-right: file count */
        parsedFilesCountLabel = new JLabel("0 files");
        parsedFilesCountLabel.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));

        JPanel rightPanel = new JPanel(new BorderLayout(0, 5));
        rightPanel.add(rightTopPanel, BorderLayout.NORTH);
        rightPanel.add(parsedScroll,   BorderLayout.CENTER);
        rightPanel.add(parsedFilesCountLabel, BorderLayout.SOUTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rawTextScroll, rightPanel);
        splitPane.setResizeWeight(0.5);

        JLabel listFilesInstructions = new JLabel("Raw text containing filenames");
        listFilesCardPanel.add(listFilesInstructions, BorderLayout.NORTH);
        listFilesCardPanel.add(splitPane,                BorderLayout.CENTER);


        scopeCardsPanel.add(listFilesCardPanel, "LIST");


        // ----------------------------------------------------
        // Context + Post-processing option panels
        // ----------------------------------------------------
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;

        JPanel combined = new JPanel(new GridBagLayout());
        GridBagConstraints cmbc = new GridBagConstraints();
        cmbc.insets = new Insets(0, 0, 0, 0);
        // allow both panels to expand equally in both directions
        cmbc.fill = GridBagConstraints.BOTH;
        cmbc.anchor = GridBagConstraints.NORTH;
        cmbc.weighty = 1.0;
        cmbc.weightx = 0.5;

        // ---- parallel processing panel --------------------------------
        JPanel parallelProcessingPanel = new JPanel(new GridBagLayout());
        parallelProcessingPanel.setBorder(BorderFactory.createTitledBorder("Parallel processing"));
        GridBagConstraints paraGBC = new GridBagConstraints();
        paraGBC.insets = new Insets(5, 5, 5, 5);
        paraGBC.fill = GridBagConstraints.HORIZONTAL;

        // Instructions label
        paraGBC.gridx = 0;
        paraGBC.gridy = 0;
        paraGBC.gridwidth = 3;
        paraGBC.weightx = 0;
        paraGBC.weighty = 0;
        paraGBC.fill = GridBagConstraints.NONE;
        paraGBC.anchor = GridBagConstraints.WEST;
        parallelProcessingPanel.add(new JLabel("Instructions:"), paraGBC);

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

        // Model Row
        paraGBC.gridy++;
        paraGBC.gridx = 0;
        paraGBC.gridwidth = 1;
        paraGBC.weightx = 0.0;
        paraGBC.weighty = 0;
        paraGBC.fill = GridBagConstraints.NONE;
        paraGBC.anchor = GridBagConstraints.EAST;
        parallelProcessingPanel.add(new JLabel("Model"), paraGBC);

        if (chrome.getProject().getDataRetentionPolicy() == MainProject.DataRetentionPolicy.IMPROVE_BROKK) {
            paraGBC.gridx = 1;
            paraGBC.weightx = 0.0;
            paraGBC.fill = GridBagConstraints.NONE;
            paraGBC.anchor = GridBagConstraints.WEST;
            var deepSeekV3Icon = new JLabel(smallInfoIcon);
            deepSeekV3Icon.setToolTipText("""
                                          <html>
                                          Strong options include:
                                          <ul>
                                          <li>DeepSeek v3: inexpensive, massively parallel
                                          <li>Gemini Flash Lite: even cheaper than DSv3. Not as parallel but faster per-task
                                          </ul>
                                          </html>
                                          """);
            paraGBC.insets = new Insets(5, 2, 5, 5);
            parallelProcessingPanel.add(deepSeekV3Icon, paraGBC);
            paraGBC.insets = new Insets(5, 5, 5, 5);
        }

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
            private final Icon redDot    = new ColorDotIcon(Color.RED,    10);
            private final Icon yellowDot = new ColorDotIcon(Color.YELLOW, 10);
            private final Icon greenDot  = new ColorDotIcon(new Color(0, 170, 0), 10); // deeper green for visibility

            @Override
            public Component getListCellRendererComponent(JList<?> list,
                                                          Object value,
                                                          int index,
                                                          boolean isSelected,
                                                          boolean cellHasFocus)
            {
                // Use default renderer for basic background/selection handling
                super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus);
                var fav = (Service.FavoriteModel) value;
                var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
                panel.setOpaque(true);
                panel.setBackground(getBackground());

                // Determine metric + color
                var model   = service.getModel(fav.modelName(), fav.reasoning());
                Color color = Color.GRAY;
                String tooltip = "unknown capacity";
                if (model != null) {
                    Integer maxConc = service.getMaxConcurrentRequests(model);
                    if (maxConc != null) {
                        tooltip = "%,d max concurrent requests".formatted(maxConc);
                        color   = (maxConc <= 5) ? Color.RED
                                                 : (maxConc <= 50) ? Color.YELLOW
                                                                   : new Color(0, 170, 0);
                    } else {
                        Integer tpm = service.getTokensPerMinute(model);
                        if (tpm != null) {
                            tooltip = "%,d tokens/minute".formatted(tpm);
                            color   = (tpm <= 1_000_000)  ? Color.RED
                                                          : (tpm <= 10_000_000) ? Color.YELLOW
                                                                                : new Color(0, 170, 0);
                        }
                    }
                }

                Icon dot = switch (color.getRGB()) {
                    case 0xFFFF0000 -> redDot;
                    case 0xFFFFFF00 -> yellowDot;
                    default         -> greenDot;
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
        JPanel modelCostPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, H_GAP, 0));
        modelCostPanel.add(modelComboBox);
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


        paraGBC.gridy++;
        paraGBC.gridx = 0;
        paraGBC.anchor = GridBagConstraints.EAST;
        paraGBC.weightx = 0;
        parallelProcessingPanel.add(new JLabel("Per-file command"), paraGBC);
        paraGBC.gridx = 1;
        paraGBC.anchor = GridBagConstraints.WEST;
        var perFileIconCtx = new JLabel(smallInfoIcon);
        perFileIconCtx.setToolTipText("""
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
        relatedClassesCombo = new JComboBox<>(new String[]{"0", "5", "10", "20"});
        relatedClassesCombo.addActionListener(e -> updateCostEstimate());
        relatedClassesCombo.setEditable(true);
        relatedClassesCombo.setSelectedItem("0");
        parallelProcessingPanel.add(relatedClassesCombo, paraGBC);

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

        // ---- post-processing panel ------------------------
        JPanel ppPanel = new JPanel(new GridBagLayout());
        ppPanel.setBorder(BorderFactory.createTitledBorder("Post-processing"));
        GridBagConstraints ppGBC = new GridBagConstraints();
        ppGBC.insets = new Insets(5, 5, 5, 5);
        ppGBC.fill = GridBagConstraints.HORIZONTAL;
        ppGBC.anchor = GridBagConstraints.WEST;

        // --- Instructions area at top ---
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

        // --- run-choice ---
        // ------------------------------------------------------------------
        //  Action panel (runPostProcessCombo, model label, build checkbox)
        // ------------------------------------------------------------------
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
        noneInfoIcon.setToolTipText("Optionally select Architect or Ask to run after parallel processing");

        actGbc.gridx = 0;
        actGbc.gridy = 0;
        actGbc.weightx = 0.0;
        actionPanel.add(noneInfoIcon, actGbc);

        /* combo box – stretches to fill remaining width */
        runPostProcessCombo = new JComboBox<>(new String[]{"None", "Architect", "Ask"});
        actGbc.gridx = 1;
        actGbc.weightx = 1.0;
        actionPanel.add(runPostProcessCombo, actGbc);

        /* model label on its own row, aligned with column 1 */
        actGbc.gridy++;
        actGbc.gridx = 1;
        postProcessingModelLabel = new JLabel(" ");
        actionPanel.add(postProcessingModelLabel, actGbc);

        /* build-first checkbox on its own row, also in column 1 */
        actGbc.gridy++;
        buildFirstCheckbox = new JCheckBox("Build project first");
        buildFirstCheckbox.setToolTipText("Run the project's build/verification command before invoking post-processing");
        buildFirstCheckbox.setEnabled(false);
        actionPanel.add(buildFirstCheckbox, actGbc);

        ppPanel.add(actionPanel, ppGBC);
        ppGBC.gridwidth = 1;

        // ------------------------------------------------------------------
        //  Parallel-processing output panel (include radios + filter)
        // ------------------------------------------------------------------
        ppGBC.gridy++;
        ppGBC.gridx = 0;
        ppGBC.gridwidth = 3;
        var outputPanel = new JPanel(new GridBagLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder("Parallel processing output"));
        GridBagConstraints opGBC = new GridBagConstraints();
        opGBC.insets = new Insets(5, 5, 5, 5);
        opGBC.anchor = GridBagConstraints.WEST;
        opGBC.fill = GridBagConstraints.NONE;

        parallelOutputGroup = new ButtonGroup();
        includeNoneRadio = new JRadioButton("Include none");
        includeAllRadio = new JRadioButton("Include all");
        includeChangedRadio = new JRadioButton("Include changed files");
        parallelOutputGroup.add(includeNoneRadio);
        parallelOutputGroup.add(includeAllRadio);
        parallelOutputGroup.add(includeChangedRadio);

        int opRow = 0;

        /* Radios */
        opGBC.gridx = 0;
        opGBC.gridy = opRow++;
        opGBC.gridwidth = 3;
        outputPanel.add(includeNoneRadio, opGBC);
        opGBC.gridy = opRow++;
        outputPanel.add(includeAllRadio, opGBC);
        opGBC.gridy = opRow++;
        outputPanel.add(includeChangedRadio, opGBC);

        /* Filter row – label */
        GridBagConstraints filterLabelGbc = new GridBagConstraints();
        filterLabelGbc.insets = new Insets(5, 5, 5, 5);
        filterLabelGbc.gridx = 0;
        filterLabelGbc.gridy = opRow;
        filterLabelGbc.anchor = GridBagConstraints.EAST;
        outputPanel.add(new JLabel("Filter"), filterLabelGbc);

        /* Filter row – info bubble */
        GridBagConstraints filterInfoGbc = new GridBagConstraints();
        filterInfoGbc.insets = new Insets(5, H_GLUE, 5, 5);
        filterInfoGbc.gridx = 1;
        filterInfoGbc.gridy = opRow;
        filterInfoGbc.anchor = GridBagConstraints.WEST;
        var filterInfo = new JLabel(smallInfoIcon);
        filterInfo.setToolTipText("Only include parallel processing for files whose output passes this natural language classifier");
        outputPanel.add(filterInfo, filterInfoGbc);

        /* Filter row – input field */
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

        // --- spacer ---
        var ppSpacer = new GridBagConstraints();
        ppSpacer.gridy = ppGBC.gridy + 1;
        ppSpacer.gridwidth = 3;
        ppSpacer.weighty = 1.0;
        ppSpacer.fill = GridBagConstraints.VERTICAL;
        ppPanel.add(new JPanel(), ppSpacer);

        java.awt.event.ActionListener postProcessListener = ev -> {
            String selectedOption = (String) runPostProcessCombo.getSelectedItem();
            boolean ask = "Ask".equals(selectedOption);
            boolean architect = "Architect".equals(selectedOption);
            boolean none = "None".equals(selectedOption);

            postProcessingInstructionsArea.setEnabled(!none);
            postProcessingScrollPane.setEnabled(!none);

            if (ask) {
                var verificationCommand = io.github.jbellis.brokk.agents.BuildAgent
                        .determineVerificationCommand(chrome.getContextManager());
                boolean hasVerification = verificationCommand != null && !verificationCommand.isBlank();
                buildFirstCheckbox.setEnabled(hasVerification);
                buildFirstCheckbox.setSelected(false); // default off for Ask
                if (!hasVerification) {
                    buildFirstCheckbox.setToolTipText("No build/verification command available");
                }
                includeAllRadio.setSelected(true);
            } else if (architect) {
                var verificationCommand = io.github.jbellis.brokk.agents.BuildAgent
                        .determineVerificationCommand(chrome.getContextManager());
                boolean hasVerification = verificationCommand != null && !verificationCommand.isBlank();
                buildFirstCheckbox.setEnabled(hasVerification);
                buildFirstCheckbox.setSelected(hasVerification);
                if (!hasVerification) {
                    buildFirstCheckbox.setToolTipText("No build/verification command available");
                }
                includeChangedRadio.setSelected(true);
                if (postProcessingInstructionsArea.getText().trim().isEmpty()) {
                    postProcessingInstructionsArea.setText("Fix any build errors");
                }
            } else { // None
                buildFirstCheckbox.setEnabled(false);
                buildFirstCheckbox.setSelected(false);
                includeNoneRadio.setSelected(true);
            }

            var cm = chrome.getContextManager();
            if (architect) {
                String modelName = cm.getService().nameOf(cm.getArchitectModel());
                postProcessingModelLabel.setText("Model: " + modelName);
            } else if (ask) {
                String modelName = cm.getService().nameOf(cm.getAskModel());
                postProcessingModelLabel.setText("Model: " + modelName);
            } else {
                postProcessingModelLabel.setText(" ");
            }
        };
        runPostProcessCombo.addActionListener(postProcessListener);
        postProcessListener.actionPerformed(new java.awt.event.ActionEvent(runPostProcessCombo, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));


        // ---- add both panels ------------------------------
        cmbc.gridx = 0;
        combined.add(parallelProcessingPanel, cmbc);
        cmbc.gridx = 1;
        combined.add(ppPanel, cmbc);
        contentPanel.add(combined, gbc);

        // Scope Panel at the bottom
        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 3;
        gbc.weighty = 0.1;
        gbc.fill = GridBagConstraints.BOTH;

        JPanel scopePanel = new JPanel(new BorderLayout(0, 5));
        scopePanel.setBorder(BorderFactory.createTitledBorder("Scope"));

        entireProjectScopeRadioButton = new JRadioButton("Entire Project");
        selectFilesScopeRadioButton = new JRadioButton("Select Files");
        listFilesScopeRadioButton = new JRadioButton("List Files");
        selectFilesScopeRadioButton.setSelected(true);

        scopeButtonGroup = new ButtonGroup();
        scopeButtonGroup.add(entireProjectScopeRadioButton);
        scopeButtonGroup.add(selectFilesScopeRadioButton);
        scopeButtonGroup.add(listFilesScopeRadioButton);

        JPanel scopeRadioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        scopeRadioPanel.add(entireProjectScopeRadioButton);
        scopeRadioPanel.add(selectFilesScopeRadioButton);
        scopeRadioPanel.add(listFilesScopeRadioButton);

        scopePanel.add(scopeRadioPanel, BorderLayout.NORTH);
        scopePanel.add(scopeCardsPanel, BorderLayout.CENTER);

        contentPanel.add(scopePanel, gbc);

        // Listeners to switch cards
        java.awt.event.ActionListener scopeListener = e -> {
            String command;
            if (entireProjectScopeRadioButton.isSelected()) {
                command = "ENTIRE";
                updateEntireProjectFileCountLabel();
            } else if (listFilesScopeRadioButton.isSelected()) {
                command = "LIST";
            } else {
                command = "SELECT";
            }
            scopeCardLayout.show(scopeCardsPanel, command);
            updateCostEstimate();
        };
        languageComboBox.addActionListener(e -> {
            updateEntireProjectFileCountLabel();
            updateCostEstimate();
        });

        entireProjectScopeRadioButton.addActionListener(scopeListener);
        selectFilesScopeRadioButton.addActionListener(scopeListener);
        listFilesScopeRadioButton.addActionListener(scopeListener);
        scopeCardLayout.show(scopeCardsPanel, "SELECT");


        add(contentPanel, BorderLayout.CENTER);

        // Buttons Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");

        okButton.addActionListener(e -> onOK());
        cancelButton.addActionListener(e -> setVisible(false));

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void updateCostEstimate() {
        var cm      = chrome.getContextManager();
        var service = cm.getService();
        var fav     = (Service.FavoriteModel) modelComboBox.getSelectedItem();
        requireNonNull(fav);

        var pricing = service.getModelPricing(fav.modelName());

        List<ProjectFile> files = getSelectedFilesForCost();
        int n = files.size();
        if (n == 0) { costEstimateLabel.setText(" "); return; }

        long tokensFiles = files.parallelStream()
                .mapToLong(this::getTokenCount)
                .sum();
        double avgTokens = tokensFiles / (double) n;

        long workspaceTokens = includeWorkspaceCheckbox.isSelected()
                               ? Messages.getApproximateTokens(
                io.github.jbellis.brokk.prompts.CodePrompts.instance
                        .getWorkspaceContentsMessages(cm.topContext()))
                               : 0;
        long workspaceAdd = includeWorkspaceCheckbox.isSelected() ? workspaceTokens * n : 0;

        int relatedK = 0;
        try {
            var txt = Objects.toString(relatedClassesCombo.getEditor().getItem(), "").trim();
            if (!txt.isEmpty()) relatedK = Integer.parseInt(txt);
        } catch (NumberFormatException ex) {
            // Invalid number → treat as zero related classes
            relatedK = 0;
        }
        long relatedAdd = relatedK > 0 ? Math.round(n * relatedK * avgTokens * 0.1) : 0;

        long totalInput = tokensFiles + workspaceAdd + relatedAdd;
        long estOutput  = Math.min(4000, totalInput / 2);
        double cost     = pricing.estimateCost(totalInput, 0, estOutput);
        estimatedCost   = cost;

        costEstimateLabel.setText(String.format("Cost Estimate: $%.2f", cost));
    }
    
    private void fetchUserBalance() {
        var cm = chrome.getContextManager();
        cm.submitBackgroundTask("Fetch balance",
                                () -> cm.getService().getUserBalance())
          .thenAccept(balance -> userBalance = balance);
    }
    
    /**
     * Returns the cached token count of a file, computing it once if necessary.
     */
    private long getTokenCount(ProjectFile pf) {
        return tokenCountCache.computeIfAbsent(pf, file -> {
            try {
                return (long) Messages.getApproximateTokens(file.read());
            } catch (Exception e) {
                return 0L;
            }
        });
    }
    
    /**
     * Gather the currently selected files (no validation).
     */
    private List<ProjectFile> getSelectedFilesForCost() {
        try {
            if (entireProjectScopeRadioButton.isSelected()) {
                var files = chrome.getProject().getRepo().getTrackedFiles().stream()
                                   .filter(ProjectFile::isText);
                String langSel = Objects.toString(languageComboBox.getSelectedItem(), ALL_LANGUAGES_OPTION);
                if (!ALL_LANGUAGES_OPTION.equals(langSel)) {
                    files = files.filter(pf -> langSel.equals(pf.getLanguage().toString()));
                }
                return files.toList();
            }

            if (listFilesScopeRadioButton.isSelected()) {
                String text = listFilesTextArea.getText();
                var projectFiles = chrome.getProject().getRepo().getTrackedFiles();
                return projectFiles.parallelStream()
                                   .filter(ProjectFile::isText)
                                   .filter(pf -> {
                                       boolean nameMatch = text.contains(pf.toString()) || text.contains(pf.getFileName());
                                       if (!nameMatch) return false;
                                       String langSel = Objects.toString(listLanguageCombo.getSelectedItem(), ALL_LANGUAGES_OPTION);
                                       if (ALL_LANGUAGES_OPTION.equals(langSel)) return true;
                                       return langSel.equals(pf.getLanguage().toString());
                                   })
                                   .toList();
            }

            // select-files scope
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

    private void updateEntireProjectFileCountLabel() {
        var files = chrome.getProject().getRepo().getTrackedFiles().stream()
                .filter(ProjectFile::isText);
        String langSel = Objects.toString(languageComboBox.getSelectedItem(), ALL_LANGUAGES_OPTION);
        if (!ALL_LANGUAGES_OPTION.equals(langSel)) {
            files = files.filter(pf -> langSel.equals(pf.getLanguage().toString()));
        }
        long count = files.count();
        entireProjectFileCountLabel.setText(count + " file" + (count == 1 ? "" : "s"));
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

        var model = service.getModel(favModel.modelName(), favModel.reasoning());
        if (model == null) {
            tokenWarningLabel.setVisible(false);
            return;
        }
        var maxTokens = service.getMaxInputTokens(model);

        var workspaceTokens = Messages.getApproximateTokens(CodePrompts.instance.getWorkspaceContentsMessages(cm.topContext()));
        var historyTokens = Messages.getApproximateTokens(cm.getHistoryMessages());

        long remaining = (long) maxTokens - workspaceTokens - historyTokens;

        if (remaining < TOKEN_SAFETY_MARGIN) {
            tokenWarningLabel.setText(
                    "<html><b>Warning:</b> The selected model has a " + maxTokens +
                    "-token window. Your workspace (" + workspaceTokens + " tokens) " +
                    "+ history (" + historyTokens + " tokens) leaves only " + remaining +
                    " tokens, which is below the " + TOKEN_SAFETY_MARGIN + " token safety margin. " +
                    "Trim your workspace or choose a model with a larger context window.</html>"
            );
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

    private void addSelectedFilesToTable() {
        List<BrokkFile> resolvedFiles = fileSelectionPanel.resolveAndGetSelectedFiles();
        if (resolvedFiles.isEmpty()) {
            return;
        }

        // Use a Set to prevent duplicate entries
        Set<String> existingPaths = new HashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            existingPaths.add((String) tableModel.getValueAt(i, 0));
        }

        for (BrokkFile file : resolvedFiles) {
            if (file instanceof ProjectFile pf) {
                String relPath = pf.getRelPath().toString();
                if (!existingPaths.contains(relPath)) {
                    tableModel.addRow(new Object[]{relPath});
                }
            }
        }
        fileSelectionPanel.setInputText("");
        updateCostEstimate();
    }

    private void removeSelectedFilesFromTable() {
        int[] selectedRows = selectedFilesTable.getSelectedRows();
        // Remove rows in reverse order to prevent index shifting issues
        for (int i = selectedRows.length - 1; i >= 0; i--) {
            tableModel.removeRow(selectedRows[i]);
        }
        updateCostEstimate();
    }

    private void updateParsedFilesTable() {
        String text = listFilesTextArea.getText();
        parsedTableModel.setRowCount(0);

        if (text.isBlank()) {
            parsedFilesCountLabel.setText("0 files");
            updateCostEstimate();
            return;
        }

        var tracked = chrome.getProject().getRepo().getTrackedFiles();

        String langSel = Objects.toString(listLanguageCombo.getSelectedItem(), ALL_LANGUAGES_OPTION);

        List<ProjectFile> matches = tracked.parallelStream()
                                           .filter(ProjectFile::isText)
                                           .filter(pf -> {
                                               boolean nameMatch = text.contains(pf.toString())
                                                                 || text.contains(pf.getFileName());
                                               if (!nameMatch) return false;
                                               if (ALL_LANGUAGES_OPTION.equals(langSel)) return true;
                                               return langSel.equals(pf.getLanguage().toString());
                                           })
                                           .sorted(Comparator.comparing(ProjectFile::toString))
                                           .toList();

        matches.forEach(pf -> parsedTableModel.addRow(new Object[]{pf.getRelPath().toString()}));
        parsedFilesCountLabel.setText(matches.size() + " file" + (matches.size() == 1 ? "" : "s"));
        updateCostEstimate();
    }

    private void onOK() {
        String instructions = instructionsArea.getText().trim();
        if (instructions.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Instructions cannot be empty", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        Service.FavoriteModel selectedFavorite = (Service.FavoriteModel) modelComboBox.getSelectedItem();
        if (selectedFavorite == null) {
            JOptionPane.showMessageDialog(this, "Please select a model", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Refresh cost estimate and warn if it is more than half the balance
        updateCostEstimate();
        if (!Float.isNaN(userBalance) && estimatedCost > userBalance / 2.0) {
            int choice = JOptionPane.showConfirmDialog(
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

        // Determine scope and get files to process
        List<ProjectFile> filesToProcessList;

        if (entireProjectScopeRadioButton.isSelected()) {
            var filesToProcess = chrome.getProject().getRepo().getTrackedFiles().stream()
                    .filter(ProjectFile::isText);

            String selectedLanguageString = (String) languageComboBox.getSelectedItem();
            if (selectedLanguageString != null && !ALL_LANGUAGES_OPTION.equals(selectedLanguageString)) {
                filesToProcess = filesToProcess.filter(pf -> {
                    Language lang = pf.getLanguage();
                    return selectedLanguageString.equals(lang.toString());
                });
            }
            filesToProcessList = filesToProcess.toList();

            if (filesToProcessList.isEmpty()) {
                String message = (ALL_LANGUAGES_OPTION.equals(languageComboBox.getSelectedItem()) || languageComboBox.getSelectedItem() == null)
                                 ? "No text files found in the project to process"
                                 : "No text files found for the selected language (" + languageComboBox.getSelectedItem() + ")";
                JOptionPane.showMessageDialog(this, message, "No Files", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        } else if (listFilesScopeRadioButton.isSelected()) {
            String text = listFilesTextArea.getText();
            if (text.isBlank()) {
                JOptionPane.showMessageDialog(this, "Raw text cannot be empty", "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            var projectFiles = chrome.getProject().getRepo().getTrackedFiles();
            filesToProcessList = projectFiles.parallelStream()
                    .filter(ProjectFile::isText)
                    .filter(pf -> {
                        boolean nameMatch = text.contains(pf.toString()) || text.contains(pf.getFileName());
                        if (!nameMatch) return false;
                        String langSel = Objects.toString(listLanguageCombo.getSelectedItem(), ALL_LANGUAGES_OPTION);
                        if (ALL_LANGUAGES_OPTION.equals(langSel)) return true;
                        return langSel.equals(pf.getLanguage().toString());
                    })
                    .toList();

            if (filesToProcessList.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No tracked files found from the list", "No Files", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        } else { // Select Files
            if (tableModel.getRowCount() == 0) {
                JOptionPane.showMessageDialog(this, "No files have been selected", "No Files", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            List<ProjectFile> selectedFiles = new java.util.ArrayList<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String relPath = (String) tableModel.getValueAt(i, 0);
                selectedFiles.add(chrome.getContextManager().toFile(relPath));
            }
            filesToProcessList = selectedFiles;
        }

        Integer relatedK = null;
        var txt = Objects.toString(relatedClassesCombo.getEditor().getItem(), "").trim();
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
                JOptionPane.showMessageDialog(this,
                                              "Value for 'Include related classes' must be a non-negative integer",
                                              "Input Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        String perFileCommandTemplate = perFileCommandTextField.getText().trim();
        if (perFileCommandTemplate.isEmpty()) {
            perFileCommandTemplate = null;
        }

        boolean includeWorkspace = includeWorkspaceCheckbox.isSelected();

        PostProcessingOption runOption = switch ((String) runPostProcessCombo.getSelectedItem()) {
            case "Architect" -> PostProcessingOption.ARCHITECT;
            case "Ask" -> PostProcessingOption.ASK;
            default -> PostProcessingOption.NONE;
        };
        String parallelOutputMode;
        if (includeNoneRadio.isSelected()) {
            parallelOutputMode = "none";
        } else if (includeAllRadio.isSelected()) {
            parallelOutputMode = "all";
        } else {
            parallelOutputMode = "changed";
        }
        boolean buildFirst = buildFirstCheckbox.isSelected();
        String contextFilter = contextFilterTextField.getText().trim();
        String postProcessingInstructions = postProcessingInstructionsArea.getText().trim();

        if (runOption != PostProcessingOption.NONE && postProcessingInstructions.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Post-processing instructions cannot be empty when a post-processing action is selected.", "Input Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        setVisible(false); // Hide this dialog

        // Show progress dialog
        var progressDialog = new BlitzForgeProgressDialog(
                (Frame) getOwner(),
                instructions,
                selectedFavorite,
                filesToProcessList,
                chrome,
                relatedK,
                perFileCommandTemplate,
                includeWorkspace,
                runOption,
                contextFilter,
                parallelOutputMode,
                buildFirst,
                postProcessingInstructions
        );
        progressDialog.setVisible(true);
    }
}
