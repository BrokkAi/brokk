package ai.brokk.gui.dialogs;

import static java.util.Objects.requireNonNull;

import ai.brokk.Service;
import ai.brokk.SettingsChangeListener;
import ai.brokk.gui.Chrome;
import ai.brokk.gui.SwingUtil.ThemedIcon;
import ai.brokk.gui.components.BrowserLabel;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.components.McpToolTable;
import ai.brokk.gui.components.SpinnerIconUtil;
import ai.brokk.gui.theme.GuiTheme;
import ai.brokk.gui.theme.ThemeAware;
import ai.brokk.gui.util.Icons;
import ai.brokk.gui.util.KeyboardShortcutUtil;
import ai.brokk.mcp.HttpMcpServer;
import ai.brokk.mcp.McpConfig;
import ai.brokk.mcp.McpServer;
import ai.brokk.mcp.McpUtils;
import ai.brokk.mcp.StdioMcpServer;
import ai.brokk.project.MainProject;
import ai.brokk.util.Environment;
import ai.brokk.util.GlobalUiSettings;
import ai.brokk.util.GpgKeyUtil;
import io.modelcontextprotocol.spec.McpSchema;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class SettingsGlobalPanel extends JPanel implements ThemeAware, SettingsChangeListener {
    private static final Logger logger = LogManager.getLogger(SettingsGlobalPanel.class);

    private final Chrome chrome;
    private final SettingsDialog parentDialog; // To access project for data retention refresh

    // Service controls
    private JTextField brokkKeyField = new JTextField();
    private JTextField balanceField = new JTextField();
    private BrowserLabel signupLabel = new BrowserLabel("", "");

    @Nullable
    private JRadioButton brokkProxyRadio; // may be null when STAGING

    @Nullable
    private JRadioButton localhostProxyRadio;

    @Nullable
    private JCheckBox forceToolEmulationCheckbox; // dev-only

    // Appearance controls (kept in Global)
    private JComboBox<String> themeCombo = new JComboBox<>();
    private JCheckBox gpgSigningCheckbox = new JCheckBox("Sign commits with GPG");
    private JComboBox<GpgKeyUtil.GpgKey> gpgKeyCombo = new JComboBox<>();
    private JCheckBox wordWrapCheckbox = new JCheckBox("Enable word wrap");
    private JCheckBox classicBrokkViewCheckbox = new JCheckBox("Enable Classic (Horizontal) View");
    private JRadioButton diffSideBySideRadio = new JRadioButton("Side-by-Side");
    private JRadioButton diffUnifiedRadio = new JRadioButton("Unified");

    @Nullable
    private JRadioButton uiScaleAutoRadio;

    @Nullable
    private JRadioButton uiScaleCustomRadio;

    @Nullable
    private JComboBox<String> uiScaleCombo;

    private JSpinner terminalFontSizeSpinner = new JSpinner();

    // GitHub / MCP / Keybindings
    private GitHubSettingsPanel gitHubSettingsPanel;
    private DefaultListModel<McpServer> mcpServersListModel = new DefaultListModel<>();
    private JList<McpServer> mcpServersList = new JList<>(mcpServersListModel);

    private JTabbedPane globalSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

    /**
     * Constructor.
     */
    public SettingsGlobalPanel(Chrome chrome, SettingsDialog parentDialog) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        this.chrome = chrome;
        this.parentDialog = parentDialog;
        setLayout(new BorderLayout());
        initComponents(); // This will fully initialize or conditionally initialize fields
        // NOTE: loadSettings() is now called explicitly in SettingsDialog.showSettingsDialog()
        // to ensure consistent timing with project panel

        // Disable panel until data is loaded
        setEnabled(false);
        MainProject.addSettingsChangeListener(this);
    }

    /**
     * Populate from SettingsData (loaded off-EDT). Only populates Service, Appearance, GitHub, MCP.
     */
    public void populateFromData(SettingsDialog.SettingsData data) {
        assert SwingUtilities.isEventDispatchThread() : "Must be called on EDT";
        setEnabled(true);

        // Service
        brokkKeyField.setText(data.brokkApiKey());
        balanceField.setText(data.accountBalance());
        updateSignupLabelVisibility();

        if (brokkProxyRadio != null && localhostProxyRadio != null) {
            if (MainProject.getProxySetting() == MainProject.LlmProxySetting.BROKK) {
                brokkProxyRadio.setSelected(true);
            } else {
                localhostProxyRadio.setSelected(true);
            }
        }

        if (forceToolEmulationCheckbox != null) {
            forceToolEmulationCheckbox.setSelected(MainProject.getForceToolEmulation());
        }

        // Appearance
        String currentTheme = MainProject.getTheme();
        String displayName = getThemeDisplayName(currentTheme);
        themeCombo.setSelectedItem(displayName);
        wordWrapCheckbox.setSelected(MainProject.getCodeBlockWrapMode());
        classicBrokkViewCheckbox.setSelected(!GlobalUiSettings.isVerticalActivityLayout());

        // UI Scale (if present)
        if (uiScaleAutoRadio != null && uiScaleCustomRadio != null && uiScaleCombo != null) {
            String pref = MainProject.getUiScalePref();
            if ("auto".equalsIgnoreCase(pref)) {
                uiScaleAutoRadio.setSelected(true);
                uiScaleCombo.setSelectedItem("1.0");
                uiScaleCombo.setEnabled(false);
            } else {
                uiScaleCustomRadio.setSelected(true);
                var model = (DefaultComboBoxModel<String>) uiScaleCombo.getModel();
                String selected = pref;
                boolean found = false;
                for (int i = 0; i < model.getSize(); i++) {
                    if (pref.equals(model.getElementAt(i))) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    try {
                        double v = Double.parseDouble(pref);
                        int nearest = (int) Math.round(v);
                        if (nearest < 1) nearest = 1;
                        if (nearest > 5) nearest = 5;
                        selected = nearest + ".0";
                    } catch (NumberFormatException ignore) {
                        selected = "1.0";
                    }
                }
                uiScaleCombo.setSelectedItem(selected);
                uiScaleCombo.setEnabled(true);
            }
        }

        terminalFontSizeSpinner.setValue((double) MainProject.getTerminalFontSize());

        // Diff view
        boolean unified = GlobalUiSettings.isDiffUnifiedView();
        diffUnifiedRadio.setSelected(unified);
        diffSideBySideRadio.setSelected(!unified);

        // GitHub
        populateGitHubTab();

        // MCP servers list
        populateMcpServersTab();

        // GPG Signing
        gpgSigningCheckbox.setSelected(MainProject.isGpgCommitSigningEnabled());
        discoverGpgKeys();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setEnabledRecursive(this, enabled);
    }

    private void setEnabledRecursive(Container container, boolean enabled) {
        for (Component comp : container.getComponents()) {
            comp.setEnabled(enabled);
            if (comp instanceof Container c) setEnabledRecursive(c, enabled);
        }
    }

    private void initComponents() {
        globalSubTabbedPane = new JTabbedPane(JTabbedPane.TOP);

        // Service Tab
        var servicePanel = createServicePanel();
        globalSubTabbedPane.addTab("Service", null, servicePanel, "Service configuration");

        // Appearance Tab
        var appearancePanel = createAppearancePanel();
        globalSubTabbedPane.addTab("Appearance", null, appearancePanel, "Theme settings");

        // GitHub / Git Signing Tab
        var githubAndSigningPanel = createGitHubAndSigningPanel();
        globalSubTabbedPane.addTab(
                SettingsDialog.GITHUB_SETTINGS_TAB_NAME, null, githubAndSigningPanel, "Git and signing settings");

        // MCP Servers Tab
        var mcpPanel = createMcpPanel();
        globalSubTabbedPane.addTab("MCP Servers", null, mcpPanel, "MCP server configuration");

        // Keybindings Tab (scrollable)
        var keybindingsPanel = createKeybindingsPanel();
        var keybindingsScroll = new JScrollPane(keybindingsPanel);
        keybindingsScroll.setBorder(BorderFactory.createEmptyBorder());
        keybindingsScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        keybindingsScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        globalSubTabbedPane.addTab("Keybindings", null, keybindingsScroll, "Configure keyboard shortcuts");

        add(globalSubTabbedPane, BorderLayout.CENTER);
    }

    private JPanel createServicePanel() {
        var servicePanel = new JPanel(new GridBagLayout());
        servicePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row;
        servicePanel.add(new JLabel("Brokk Key:"), gbc);

        brokkKeyField = new JTextField(20);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        servicePanel.add(brokkKeyField, gbc);

        gbc.gridx = 0;
        gbc.gridy = ++row;
        servicePanel.add(new JLabel("Balance:"), gbc);

        this.balanceField = new JTextField("Loading...");
        this.balanceField.setEditable(false);
        this.balanceField.setColumns(8);
        var balanceDisplayPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        balanceDisplayPanel.add(this.balanceField);
        var topUpUrl = Service.TOP_UP_URL;
        var topUpLabel = new BrowserLabel(topUpUrl, "Top Up");
        balanceDisplayPanel.add(topUpLabel);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.NONE;
        servicePanel.add(balanceDisplayPanel, gbc);

        var signupUrl = "https://brokk.ai";
        this.signupLabel = new BrowserLabel(signupUrl, "Sign up or get your key at " + signupUrl);
        this.signupLabel.setFont(this.signupLabel.getFont().deriveFont(Font.ITALIC));
        gbc.gridx = 1;
        gbc.gridy = ++row;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(2, 5, 8, 5);
        servicePanel.add(this.signupLabel, gbc);
        gbc.insets = new Insets(2, 5, 2, 5);

        gbc.gridx = 0;
        gbc.gridy = ++row;
        servicePanel.add(new JLabel("LLM Proxy:"), gbc);

        if (MainProject.getProxySetting() == MainProject.LlmProxySetting.STAGING) {
            var proxyInfoLabel = new JLabel(
                    "Proxy has been set to STAGING in ~/.brokk/brokk.properties. Changing it back must be done in the same place.");
            proxyInfoLabel.setFont(proxyInfoLabel.getFont().deriveFont(Font.ITALIC));
            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            servicePanel.add(proxyInfoLabel, gbc);
        } else {
            brokkProxyRadio = new JRadioButton("Brokk");
            localhostProxyRadio = new JRadioButton("Localhost");
            var proxyGroup = new ButtonGroup();
            proxyGroup.add(brokkProxyRadio);
            proxyGroup.add(localhostProxyRadio);

            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            servicePanel.add(brokkProxyRadio, gbc);

            gbc.gridy = ++row;
            servicePanel.add(localhostProxyRadio, gbc);

            var proxyInfoLabel = new JLabel("Brokk will look for a litellm proxy on localhost:4000");
            proxyInfoLabel.setFont(proxyInfoLabel.getFont().deriveFont(Font.ITALIC));
            gbc.insets = new Insets(0, 25, 2, 5);
            gbc.gridy = ++row;
            servicePanel.add(proxyInfoLabel, gbc);

            var restartLabel = new JLabel("Restart required after changing proxy settings");
            restartLabel.setFont(restartLabel.getFont().deriveFont(Font.ITALIC));
            gbc.gridy = ++row;
            servicePanel.add(restartLabel, gbc);
            gbc.insets = new Insets(2, 5, 2, 5);
        }

        if (Boolean.getBoolean("brokk.devmode")) {
            forceToolEmulationCheckbox =
                    new JCheckBox("[Dev Mode] Force tool emulation", MainProject.getForceToolEmulation());
            forceToolEmulationCheckbox.setToolTipText("Development override: emulate tool calls.");
            gbc.gridx = 1;
            gbc.gridy = ++row;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            servicePanel.add(forceToolEmulationCheckbox, gbc);
        }

        gbc.gridy = ++row;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        servicePanel.add(Box.createVerticalGlue(), gbc);

        return servicePanel;
    }

    private void updateSignupLabelVisibility() {
        String currentPersistedKey = MainProject.getBrokkKey();
        boolean keyIsEffectivelyPresent = !currentPersistedKey.trim().isEmpty();
        this.signupLabel.setVisible(!keyIsEffectivelyPresent);
    }

    private JPanel createGitHubAndSigningPanel() {
        var mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Section 1: GitHub
        var githubHeader = new JLabel("GitHub Integration");
        githubHeader.setFont(githubHeader.getFont().deriveFont(Font.BOLD));
        githubHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(githubHeader);
        mainPanel.add(Box.createVerticalStrut(5));

        gitHubSettingsPanel = new GitHubSettingsPanel(chrome.getContextManager(), this);
        // Remove individual panel border to avoid double padding
        gitHubSettingsPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 10, 5));
        gitHubSettingsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(gitHubSettingsPanel);

        mainPanel.add(new JSeparator(JSeparator.HORIZONTAL));
        mainPanel.add(Box.createVerticalStrut(15));

        // Section 2: Git Signing
        var gitHeader = new JLabel("Signing");
        gitHeader.setFont(gitHeader.getFont().deriveFont(Font.BOLD));
        gitHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(gitHeader);
        mainPanel.add(Box.createVerticalStrut(5));

        var gitSigningPanel = createGitSigningPanel();
        gitSigningPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(gitSigningPanel);

        // Push everything to the top
        mainPanel.add(Box.createVerticalGlue());

        return mainPanel;
    }

    private JPanel createGitSigningPanel() {
        var panel = new JPanel(new GridBagLayout());
        // Border removed as it is now inside the combined panel section
        panel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        var gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        int row = 0;

        gbc.gridx = 0;
        gbc.gridy = row++;
        gbc.gridwidth = 2;
        panel.add(gpgSigningCheckbox, gbc);

        gbc.gridwidth = 1;
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel("GPG Signing Key:"), gbc);

        gpgKeyCombo.setEditable(true);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(gpgKeyCombo, gbc);

        row++;
        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(Box.createGlue(), gbc);

        return panel;
    }

    private void discoverGpgKeys() {
        String savedKeyId = MainProject.getGpgSigningKey();
        new SwingWorker<List<GpgKeyUtil.GpgKey>, Void>() {
            @Override
            protected List<GpgKeyUtil.GpgKey> doInBackground() {
                return GpgKeyUtil.listSecretKeys();
            }

            @Override
            protected void done() {
                try {
                    List<GpgKeyUtil.GpgKey> keys = get();
                    DefaultComboBoxModel<GpgKeyUtil.GpgKey> model = new DefaultComboBoxModel<>();
                    GpgKeyUtil.GpgKey selected = null;
                    for (var key : keys) {
                        model.addElement(key);
                        if (key.id().equals(savedKeyId)) {
                            selected = key;
                        }
                    }

                    // If the saved key isn't in the discovered list, add it as a manual entry so it's not lost
                    if (selected == null && !savedKeyId.isEmpty()) {
                        selected = new GpgKeyUtil.GpgKey(savedKeyId, savedKeyId);
                        model.insertElementAt(selected, 0);
                    }

                    gpgKeyCombo.setModel(model);
                    if (selected != null) {
                        gpgKeyCombo.setSelectedItem(selected);
                    }
                } catch (Exception e) {
                    logger.debug("GPG key discovery update failed", e);
                }
            }
        }.execute();
    }

    private JPanel createAppearancePanel() {
        var appearancePanel = new JPanel(new GridBagLayout());
        appearancePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        var gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;

        // Theme dropdown
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.insets = new Insets(2, 0, 2, 5);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        appearancePanel.add(new JLabel("Theme:"), gbc);

        var themeModel = new DefaultComboBoxModel<String>();
        themeModel.addElement("Light");
        themeModel.addElement("Light+");
        themeModel.addElement("Dark");
        themeModel.addElement("Dark+");
        themeModel.addElement("High Contrast");
        themeCombo.setModel(themeModel);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(2, 0, 2, 0);
        appearancePanel.add(themeCombo, gbc);

        // Word wrap
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.insets = new Insets(10, 0, 2, 5);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        appearancePanel.add(new JLabel("Code Block Layout:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 0, 2, 0);
        appearancePanel.add(wordWrapCheckbox, gbc);

        // Vertical Layout (classic view)
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.insets = new Insets(10, 0, 2, 5);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        appearancePanel.add(new JLabel("Activity Layout:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 0, 2, 0);
        appearancePanel.add(classicBrokkViewCheckbox, gbc);

        // Diff view
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.insets = new Insets(10, 0, 2, 5);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        appearancePanel.add(new JLabel("Diff View:"), gbc);

        var diffViewGroup = new ButtonGroup();
        diffViewGroup.add(diffSideBySideRadio);
        diffViewGroup.add(diffUnifiedRadio);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 0, 2, 0);
        appearancePanel.add(diffSideBySideRadio, gbc);

        gbc.gridy = row++;
        appearancePanel.add(diffUnifiedRadio, gbc);

        // UI Scale controls (hidden on macOS/JBR)
        var vmVendor = System.getProperty("java.vm.vendor", "");
        var runtimeName = System.getProperty("java.runtime.name", "");
        boolean isJbr = vmVendor.toLowerCase(Locale.ROOT).contains("jetbrains")
                || runtimeName.toLowerCase(Locale.ROOT).contains("jetbrains");
        if (!Environment.isMacOs() && !isJbr) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.insets = new Insets(10, 0, 2, 5);
            gbc.fill = GridBagConstraints.NONE;
            gbc.weightx = 0.0;
            appearancePanel.add(new JLabel("UI Scale:"), gbc);

            uiScaleAutoRadio = new JRadioButton("Auto (recommended)");
            uiScaleCustomRadio = new JRadioButton("Custom:");
            var scaleGroup = new ButtonGroup();
            scaleGroup.add(uiScaleAutoRadio);
            scaleGroup.add(uiScaleCustomRadio);

            uiScaleCombo = new JComboBox<>();
            var uiScaleModel = new DefaultComboBoxModel<String>();
            uiScaleModel.addElement("1.0");
            uiScaleModel.addElement("2.0");
            uiScaleModel.addElement("3.0");
            uiScaleModel.addElement("4.0");
            uiScaleModel.addElement("5.0");
            uiScaleCombo.setModel(uiScaleModel);
            uiScaleCombo.setEnabled(false);

            uiScaleAutoRadio.addActionListener(e -> requireNonNull(uiScaleCombo).setEnabled(false));
            uiScaleCustomRadio.addActionListener(
                    e -> requireNonNull(uiScaleCombo).setEnabled(true));

            gbc.gridx = 1;
            gbc.gridy = row++;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(10, 0, 2, 0);
            appearancePanel.add(uiScaleAutoRadio, gbc);

            var customPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            customPanel.add(uiScaleCustomRadio);
            customPanel.add(uiScaleCombo);

            gbc.gridy = row++;
            appearancePanel.add(customPanel, gbc);

            var restartLabel = new JLabel("Restart required after changing UI scale");
            restartLabel.setFont(restartLabel.getFont().deriveFont(Font.ITALIC));
            gbc.gridy = row++;
            gbc.insets = new Insets(0, 0, 2, 0);
            appearancePanel.add(restartLabel, gbc);
        } else {
            uiScaleAutoRadio = null;
            uiScaleCustomRadio = null;
            uiScaleCombo = null;
        }

        // Terminal font size
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.insets = new Insets(10, 0, 2, 5);
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        appearancePanel.add(new JLabel("Terminal Font Size:"), gbc);

        var fontSizeModel = new SpinnerNumberModel(11.0, 8.0, 36.0, 0.5);
        terminalFontSizeSpinner.setModel(fontSizeModel);
        terminalFontSizeSpinner.setEditor(new JSpinner.NumberEditor(terminalFontSizeSpinner, "#0.0"));

        var terminalFontSizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        terminalFontSizePanel.add(terminalFontSizeSpinner);

        gbc.gridx = 1;
        gbc.gridy = row++;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 0, 2, 0);
        appearancePanel.add(terminalFontSizePanel, gbc);

        // Horizontal filler to push content left
        gbc.gridx = 2;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 0, 0);
        appearancePanel.add(Box.createHorizontalGlue(), gbc);

        // Vertical filler to push content up
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        appearancePanel.add(Box.createVerticalGlue(), gbc);

        return appearancePanel;
    }

    private JPanel createMcpPanel() {
        var mcpPanel = new JPanel(new BorderLayout(5, 5));
        mcpPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        mcpServersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mcpServersList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof HttpMcpServer server) {
                    setText(server.name() + " (" + server.url() + ")");
                } else if (value instanceof StdioMcpServer server) {
                    setText(server.name() + " (" + server.command() + ")");
                }
                return this;
            }
        });
        var scrollPane = new JScrollPane(mcpServersList);
        mcpPanel.add(scrollPane, BorderLayout.CENTER);

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        var addHttpButton = new MaterialButton("Add HTTP...");
        var addStdioButton = new MaterialButton("Add Stdio...");
        var editButton = new MaterialButton("Edit...");
        var removeButton = new MaterialButton("Remove");

        addHttpButton.setEnabled(true);
        addStdioButton.setEnabled(true);
        editButton.setEnabled(false);
        removeButton.setEnabled(false);

        mcpServersList.addListSelectionListener(e -> {
            boolean hasSelection = !mcpServersList.isSelectionEmpty();
            editButton.setEnabled(hasSelection);
            removeButton.setEnabled(hasSelection);
        });

        addHttpButton.addActionListener(e -> showMcpServerDialog("Add HTTP MCP Server", null, server -> {
            mcpServersListModel.addElement(server);
            mcpServersList.setSelectedValue(server, true);
        }));

        addStdioButton.addActionListener(e -> showStdioMcpServerDialog("Add Stdio MCP Server", null, server -> {
            mcpServersListModel.addElement(server);
            mcpServersList.setSelectedValue(server, true);
        }));

        editButton.addActionListener(e -> {
            int idx = mcpServersList.getSelectedIndex();
            if (idx < 0) return;
            McpServer existing = mcpServersListModel.getElementAt(idx);
            if (existing instanceof HttpMcpServer http) {
                showMcpServerDialog("Edit MCP Server", http, updated -> {
                    mcpServersListModel.setElementAt(updated, idx);
                    mcpServersList.setSelectedIndex(idx);
                });
            } else if (existing instanceof StdioMcpServer stdio) {
                showStdioMcpServerDialog("Edit MCP Server", stdio, updated -> {
                    mcpServersListModel.setElementAt(updated, idx);
                    mcpServersList.setSelectedIndex(idx);
                });
            }
        });

        removeButton.addActionListener(e -> {
            int idx = mcpServersList.getSelectedIndex();
            if (idx >= 0) {
                int confirm = JOptionPane.showConfirmDialog(
                        SettingsGlobalPanel.this,
                        "Remove selected MCP server?",
                        "Confirm Remove",
                        JOptionPane.YES_NO_OPTION);
                if (confirm == JOptionPane.YES_OPTION) {
                    mcpServersListModel.removeElementAt(idx);
                }
            }
        });

        buttonPanel.add(addHttpButton);
        buttonPanel.add(addStdioButton);
        buttonPanel.add(editButton);
        buttonPanel.add(removeButton);
        mcpPanel.add(buttonPanel, BorderLayout.SOUTH);

        return mcpPanel;
    }

    private void populateMcpServersTab() {
        mcpServersListModel.clear();
        var mcpConfig = chrome.getProject().getMainProject().getMcpConfig();
        for (McpServer server : mcpConfig.servers()) {
            mcpServersListModel.addElement(server);
        }
    }

    private void populateGitHubTab() {
        gitHubSettingsPanel.loadSettings();
    }

    /**
     * Asynchronously saves a keybinding to persistent storage and provides optional UI feedback.
     */
    private void saveKeybindingAsync(
            String id,
            KeyStroke stroke,
            boolean refreshKeybindings,
            @Nullable Runnable onSuccessMessage,
            Component dialogParent,
            String failureContext) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                GlobalUiSettings.saveKeybinding(id, stroke);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    if (refreshKeybindings) {
                        try {
                            chrome.refreshKeybindings();
                        } catch (Exception ex) {
                            logger.debug("refreshKeybindings failed (non-fatal)", ex);
                        }
                    }
                    if (onSuccessMessage != null) onSuccessMessage.run();
                } catch (Exception ex) {
                    logger.error("Failed to {}", failureContext, ex);
                    JOptionPane.showMessageDialog(
                            dialogParent,
                            "Failed to " + failureContext + ": " + ex.getMessage(),
                            "Save Error",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private JPanel createKeybindingsPanel() {
        var panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        final AtomicInteger row = new AtomicInteger(0);

        class RowAdder {
            void add(String id, String label) {
                KeyStroke def = defaultFor(id);
                KeyStroke cur = GlobalUiSettings.getKeybinding(id, def);

                int r = row.getAndIncrement();
                var gbcLabel = new GridBagConstraints();
                gbcLabel.insets = new Insets(4, 6, 4, 6);
                gbcLabel.anchor = GridBagConstraints.WEST;
                gbcLabel.gridx = 0;
                gbcLabel.gridy = r;
                gbcLabel.weightx = 0.0;
                panel.add(new JLabel(label + ":"), gbcLabel);

                var field = new JTextField(formatKeyStroke(cur));
                field.setEditable(false);
                var gbcField = new GridBagConstraints();
                gbcField.insets = new Insets(4, 6, 4, 6);
                gbcField.fill = GridBagConstraints.HORIZONTAL;
                gbcField.gridx = 1;
                gbcField.gridy = r;
                gbcField.weightx = 1.0;
                panel.add(field, gbcField);

                var setBtn = new MaterialButton("Set");
                var gbcSet = new GridBagConstraints();
                gbcSet.insets = new Insets(4, 6, 4, 6);
                gbcSet.gridx = 2;
                gbcSet.gridy = r;
                gbcSet.weightx = 0.0;
                panel.add(setBtn, gbcSet);

                var clearBtn = new MaterialButton("Clear");
                var gbcClear = new GridBagConstraints();
                gbcClear.insets = new Insets(4, 6, 4, 6);
                gbcClear.gridx = 3;
                gbcClear.gridy = r;
                gbcClear.weightx = 0.0;
                panel.add(clearBtn, gbcClear);

                setBtn.addActionListener(ev -> {
                    KeyStroke captured = captureKeyStroke(panel);
                    if ("global.closeWindow".equals(id)
                            && captured.getKeyCode() == KeyEvent.VK_ESCAPE
                            && captured.getModifiers() == 0) {
                        JOptionPane.showMessageDialog(
                                panel,
                                "ESC alone cannot be used for Close Window.",
                                "Invalid Shortcut",
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    }

                    String conflictingAction = findConflictingKeybinding(captured, id);
                    if (conflictingAction != null) {
                        int result = JOptionPane.showConfirmDialog(
                                panel,
                                String.format(
                                        "This shortcut is already used by '%s'. Do you want to reassign it?",
                                        conflictingAction),
                                "Shortcut Conflict",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.WARNING_MESSAGE);
                        if (result != JOptionPane.YES_OPTION) {
                            return;
                        }
                    }

                    field.setText(formatKeyStroke(captured));
                    Runnable onSuccess = () -> JOptionPane.showMessageDialog(panel, "Saved and applied.");
                    saveKeybindingAsync(id, captured, true, onSuccess, panel, "save keybinding");
                });

                clearBtn.addActionListener(ev -> {
                    field.setText(formatKeyStroke(def));
                    saveKeybindingAsync(id, def, false, null, panel, "clear keybinding");
                });
            }
        }

        RowAdder adder = new RowAdder();
        // A subset of bindings; keep the same IDs for compatibility
        adder.add("instructions.submit", "Submit (Enter)");
        adder.add("instructions.toggleMode", "Toggle Code/Ask/Lutz");
        adder.add("global.undo", "Undo");
        adder.add("global.redo", "Redo");
        adder.add("global.copy", "Copy");
        adder.add("global.paste", "Paste");
        adder.add("global.toggleMicrophone", "Toggle Microphone");
        adder.add("global.openSettings", "Open Settings");
        adder.add("global.closeWindow", "Close Window");
        adder.add("global.closeTab", "Close Tab");
        adder.add("panel.switchToProjectFiles", "Switch to Project Files");
        adder.add("panel.switchToDependencies", "Switch to Dependencies");
        adder.add("panel.switchToChanges", "Switch to Changes");
        adder.add("panel.switchToWorktrees", "Switch to Worktrees");
        adder.add("panel.switchToLog", "Switch to Log");
        adder.add("panel.switchToPullRequests", "Switch to Pull Requests");
        adder.add("panel.switchToIssues", "Switch to Issues");
        adder.add("drawer.toggleTerminal", "Toggle Terminal Drawer");
        adder.add("drawer.toggleDependencies", "Toggle Dependencies Drawer");
        adder.add("drawer.switchToTerminal", "Switch to Terminal Tab");
        adder.add("drawer.switchToTasks", "Switch to Tasks Tab");
        adder.add("view.zoomIn", "Zoom In");
        adder.add("view.zoomInAlt", "Zoom In (Alt)");
        adder.add("view.zoomOut", "Zoom Out");
        adder.add("view.resetZoom", "Reset Zoom");
        adder.add("workspace.attachContext", "Add Content to Workspace");
        adder.add("workspace.attachFilesAndSummarize", "Attach Files + Summarize");

        var resetAllBtn = new JButton("Reset All to Defaults");
        resetAllBtn.setToolTipText("Reset all keybindings to their default values");
        var gbcReset = new GridBagConstraints();
        gbcReset.gridx = 0;
        gbcReset.gridy = row.getAndIncrement();
        gbcReset.gridwidth = 4;
        gbcReset.insets = new Insets(20, 6, 6, 6);
        gbcReset.anchor = GridBagConstraints.CENTER;
        gbcReset.fill = GridBagConstraints.NONE;
        panel.add(resetAllBtn, gbcReset);

        resetAllBtn.addActionListener(ev -> {
            int result = JOptionPane.showConfirmDialog(
                    panel,
                    "This will reset ALL keybindings to their default values. Are you sure?",
                    "Reset All Keybindings",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (result == JOptionPane.YES_OPTION) {
                resetAllKeybindingsToDefaults();
                SwingUtilities.invokeLater(() -> {
                    try {
                        chrome.refreshKeybindings();
                        JOptionPane.showMessageDialog(
                                panel,
                                "All keybindings have been reset to defaults. Please close and reopen this settings dialog to see the updated values.");
                    } catch (Exception ex) {
                        logger.debug("Failed to refresh keybindings after reset", ex);
                    }
                });
            }
        });

        var gbcSpacer = new GridBagConstraints();
        gbcSpacer.gridx = 0;
        gbcSpacer.gridy = row.get();
        gbcSpacer.weightx = 1.0;
        gbcSpacer.weighty = 1.0;
        gbcSpacer.fill = GridBagConstraints.BOTH;
        panel.add(Box.createGlue(), gbcSpacer);

        return panel;
    }

    private static String formatKeyStroke(KeyStroke ks) {
        try {
            int modifiers = ks.getModifiers();
            int keyCode = ks.getKeyCode();
            String modText = InputEvent.getModifiersExText(modifiers);
            String keyText = KeyEvent.getKeyText(keyCode);
            if (modText == null || modText.isBlank()) return keyText;
            return modText + "+" + keyText;
        } catch (Exception e) {
            return ks.toString();
        }
    }

    private static KeyStroke captureKeyStroke(Component parent) {
        final KeyStroke[] result = new KeyStroke[1];
        final KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyEventDispatcher[] ref = new KeyEventDispatcher[1];

        var dialog = new BaseThemedDialog((Window) null, "Press new shortcut");
        var label = new JLabel("Press the desired key combination now (ESC to cancel)...");
        label.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        dialog.getContentRoot().add(label);
        dialog.setSize(420, 140);
        dialog.setLocationRelativeTo(parent);

        KeyEventDispatcher dispatcher = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) return false;
            int code = e.getKeyCode();
            if (code == KeyEvent.VK_SHIFT
                    || code == KeyEvent.VK_CONTROL
                    || code == KeyEvent.VK_ALT
                    || code == KeyEvent.VK_META) {
                return true;
            }
            if (code == KeyEvent.VK_ESCAPE && e.getModifiersEx() == 0) {
                result[0] = null;
            } else {
                result[0] = KeyStroke.getKeyStroke(code, e.getModifiersEx());
            }
            dialog.dispose();
            return true;
        };
        ref[0] = dispatcher;
        kfm.addKeyEventDispatcher(ref[0]);

        try {
            dialog.setFocusable(true);
            dialog.setFocusableWindowState(true);
            dialog.setAlwaysOnTop(true);
            dialog.setVisible(true);
        } finally {
            if (ref[0] != null) kfm.removeKeyEventDispatcher(ref[0]);
        }
        return result[0] == null ? KeyStroke.getKeyStroke(0, 0) : result[0];
    }

    private static @Nullable String findConflictingKeybinding(KeyStroke newKeyStroke, String excludeId) {
        if (newKeyStroke.getKeyCode() == 0) return null;

        String[] allKeybindingIds = {
            "instructions.submit",
            "instructions.toggleMode",
            "global.undo",
            "global.redo",
            "global.copy",
            "global.paste",
            "global.toggleMicrophone",
            "global.openSettings",
            "global.closeWindow",
            "global.closeTab",
            "panel.switchToProjectFiles",
            "panel.switchToDependencies",
            "panel.switchToChanges",
            "panel.switchToWorktrees",
            "panel.switchToLog",
            "panel.switchToPullRequests",
            "panel.switchToIssues",
            "drawer.toggleTerminal",
            "drawer.toggleDependencies",
            "drawer.switchToTerminal",
            "drawer.switchToTasks",
            "view.zoomIn",
            "view.zoomInAlt",
            "view.zoomOut",
            "view.resetZoom",
            "workspace.attachContext",
            "workspace.attachFilesAndSummarize"
        };

        for (String id : allKeybindingIds) {
            if (id.equals(excludeId)) continue;
            KeyStroke existing = GlobalUiSettings.getKeybinding(id, defaultFor(id));
            if (existing.equals(newKeyStroke)) {
                return getKeybindingDisplayName(id);
            }
        }
        return null;
    }

    private static String getKeybindingDisplayName(String id) {
        return switch (id) {
            case "instructions.submit" -> "Submit (Enter)";
            case "instructions.toggleMode" -> "Toggle Code/Ask/Lutz";
            case "global.undo" -> "Undo";
            case "global.redo" -> "Redo";
            case "global.copy" -> "Copy";
            case "global.paste" -> "Paste";
            case "global.toggleMicrophone" -> "Toggle Microphone";
            case "global.openSettings" -> "Open Settings";
            case "global.closeWindow" -> "Close Window";
            case "global.closeTab" -> "Close Tab";
            case "panel.switchToProjectFiles" -> "Switch to Project Files";
            case "panel.switchToDependencies" -> "Switch to Dependencies";
            case "panel.switchToChanges" -> "Switch to Changes";
            case "panel.switchToWorktrees" -> "Switch to Worktrees";
            case "panel.switchToLog" -> "Switch to Log";
            case "panel.switchToPullRequests" -> "Switch to Pull Requests";
            case "panel.switchToIssues" -> "Switch to Issues";
            case "drawer.toggleTerminal" -> "Toggle Terminal Drawer";
            case "drawer.toggleDependencies" -> "Toggle Dependencies Drawer";
            case "drawer.switchToTerminal" -> "Switch to Terminal Tab";
            case "drawer.switchToTasks" -> "Switch to Tasks Tab";
            case "view.zoomIn" -> "Zoom In";
            case "view.zoomInAlt" -> "Zoom In (Alt)";
            case "view.zoomOut" -> "Zoom Out";
            case "view.resetZoom" -> "Reset Zoom";
            case "workspace.attachContext" -> "Add Content to Workspace";
            case "workspace.attachFilesAndSummarize" -> "Attach Files + Summarize";
            default -> id;
        };
    }

    private static void resetAllKeybindingsToDefaults() {
        String[] allKeybindingIds = {
            "instructions.submit",
            "instructions.toggleMode",
            "global.undo",
            "global.redo",
            "global.copy",
            "global.paste",
            "global.toggleMicrophone",
            "global.openSettings",
            "global.closeWindow",
            "global.closeTab",
            "panel.switchToProjectFiles",
            "panel.switchToDependencies",
            "panel.switchToChanges",
            "panel.switchToWorktrees",
            "panel.switchToLog",
            "panel.switchToPullRequests",
            "panel.switchToIssues",
            "drawer.toggleTerminal",
            "drawer.toggleDependencies",
            "drawer.switchToTerminal",
            "drawer.switchToTasks",
            "view.zoomIn",
            "view.zoomInAlt",
            "view.zoomOut",
            "view.resetZoom",
            "workspace.attachContext",
            "workspace.attachFilesAndSummarize"
        };

        for (String id : allKeybindingIds) {
            KeyStroke defaultValue = defaultFor(id);
            GlobalUiSettings.saveKeybinding(id, defaultValue);
        }
    }

    public JTabbedPane getGlobalSubTabbedPane() {
        return globalSubTabbedPane;
    }

    /**
     * Apply the global settings managed by this panel (service, appearance, layout, diff view, MCP, GitHub).
     */
    public boolean applySettings() {
        // Validate API key
        String currentBrokkKeyInSettings = MainProject.getBrokkKey();
        String newBrokkKeyFromField = brokkKeyField.getText().trim();
        boolean keyStateChangedInUI = !newBrokkKeyFromField.equals(currentBrokkKeyInSettings);

        if (keyStateChangedInUI && !newBrokkKeyFromField.isEmpty()) {
            try {
                Service.validateKey(newBrokkKeyFromField);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, "Invalid Brokk Key", "Invalid Key", JOptionPane.ERROR_MESSAGE);
                return false;
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(
                        this,
                        "Network error: " + ex.getMessage() + ". Key saved, but validation failed.",
                        "Network Error",
                        JOptionPane.WARNING_MESSAGE);
            }
        }

        // Validate UI Scale and compute new preference
        String oldUiScalePref = MainProject.getUiScalePref();
        String newUiScalePref = oldUiScalePref;
        if (uiScaleAutoRadio != null && uiScaleCustomRadio != null && uiScaleCombo != null) {
            if (uiScaleAutoRadio.isSelected()) {
                newUiScalePref = "auto";
            } else {
                String txt = String.valueOf(uiScaleCombo.getSelectedItem()).trim();
                var allowed = Set.of("1.0", "2.0", "3.0", "4.0", "5.0");
                if (!allowed.contains(txt)) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Select a scale from 1.0, 2.0, 3.0, 4.0, or 5.0.",
                            "Invalid UI Scale",
                            JOptionPane.ERROR_MESSAGE);
                    return false;
                }
                newUiScalePref = txt;
            }
        }

        // GitHub settings
        if (!gitHubSettingsPanel.applySettings()) {
            return false;
        }

        // Capture previous values for side-effect decisions
        String oldTheme = MainProject.getTheme();
        boolean oldWrapMode = MainProject.getCodeBlockWrapMode();
        boolean previousVerticalLayout = GlobalUiSettings.isVerticalActivityLayout();

        // Service settings
        MainProject.LlmProxySetting proxySetting;
        if (brokkProxyRadio == null) {
            proxySetting = MainProject.getProxySetting();
        } else {
            proxySetting = brokkProxyRadio.isSelected()
                    ? MainProject.LlmProxySetting.BROKK
                    : MainProject.LlmProxySetting.LOCALHOST;
        }
        boolean forceToolEmulation = (forceToolEmulationCheckbox != null) && forceToolEmulationCheckbox.isSelected();

        MainProject.setBrokkKey(newBrokkKeyFromField);
        MainProject.setLlmProxySetting(proxySetting);
        MainProject.setForceToolEmulation(forceToolEmulation);

        // Appearance: theme
        String selectedDisplay = (String) themeCombo.getSelectedItem();
        String newTheme = getThemeValueFromDisplayName(selectedDisplay != null ? selectedDisplay : "Light");
        MainProject.setTheme(newTheme);

        // Appearance: word wrap
        boolean newWrapMode = wordWrapCheckbox.isSelected();
        MainProject.setCodeBlockWrapMode(newWrapMode);

        // UI Scale persistence
        if (!newUiScalePref.equals(oldUiScalePref)) {
            if ("auto".equalsIgnoreCase(newUiScalePref)) {
                MainProject.setUiScalePrefAuto();
            } else {
                try {
                    double scale = Double.parseDouble(newUiScalePref);
                    MainProject.setUiScalePrefCustom(scale);
                } catch (NumberFormatException e) {
                    JOptionPane.showMessageDialog(
                            this,
                            "Select a scale from 1.0, 2.0, 3.0, 4.0, or 5.0.",
                            "Invalid UI Scale",
                            JOptionPane.ERROR_MESSAGE);
                    return false;
                }
            }
            parentDialog.markRestartNeededForUiScale();
        }

        // Terminal font size
        float terminalFontSize = ((Double) terminalFontSizeSpinner.getValue()).floatValue();
        MainProject.setTerminalFontSize(terminalFontSize);

        // Layout and diff preferences
        boolean verticalLayout = !classicBrokkViewCheckbox.isSelected();
        GlobalUiSettings.saveVerticalActivityLayout(verticalLayout);

        boolean diffUnified = diffUnifiedRadio.isSelected();
        GlobalUiSettings.saveDiffUnifiedView(diffUnified);

        // MCP configuration
        var mcpServers = new ArrayList<McpServer>();
        for (int i = 0; i < mcpServersListModel.getSize(); i++) {
            mcpServers.add(mcpServersListModel.getElementAt(i));
        }
        var mcpConfig = new McpConfig(mcpServers);
        chrome.getProject().getMainProject().setMcpConfig(mcpConfig);

        // Git / Signing settings
        MainProject.setGpgCommitSigningEnabled(gpgSigningCheckbox.isSelected());
        Object selectedItem = gpgKeyCombo.getSelectedItem();
        if (selectedItem instanceof GpgKeyUtil.GpgKey key) {
            MainProject.setGpgSigningKey(key.id());
        } else if (selectedItem != null) {
            MainProject.setGpgSigningKey(selectedItem.toString());
        } else {
            MainProject.setGpgSigningKey("");
        }

        // Side effects

        if (keyStateChangedInUI) {
            refreshBalanceDisplay();
            updateSignupLabelVisibility();
            parentDialog.triggerDataRetentionPolicyRefresh();
            try {
                chrome.getContextManager().reloadService();
            } catch (Exception e) {
                logger.debug("Failed to reload service after Brokk key change (non-fatal)", e);
            }
        }

        boolean themeChanged = !newTheme.equals(oldTheme);
        boolean wrapChanged = newWrapMode != oldWrapMode;
        if (themeChanged || wrapChanged) {
            chrome.switchThemeAndWrapMode(newTheme, newWrapMode);
        }

        if (verticalLayout != previousVerticalLayout) {
            JOptionPane.showMessageDialog(
                    this,
                    "Restart required: Changing Activity Layout will take effect after restarting Brokk.",
                    "Restart Required",
                    JOptionPane.INFORMATION_MESSAGE);
        }

        chrome.updateTerminalFontSize();

        logger.debug("Applied global settings (service + appearance + MCP + GitHub) successfully");
        return true;
    }

    private void refreshBalanceDisplay() {
        this.balanceField.setText("Loading...");
        var contextManager = chrome.getContextManager();
        var models = contextManager.getService();
        contextManager.submitBackgroundTask("Refreshing user balance", () -> {
            try {
                float balance = models.getUserBalance();
                SwingUtilities.invokeLater(() -> this.balanceField.setText(String.format("$%.2f", balance)));
            } catch (IOException e) {
                logger.debug("Failed to refresh user balance", e);
                SwingUtilities.invokeLater(() -> this.balanceField.setText("Error"));
            }
        });
    }

    @Override
    public void applyTheme(GuiTheme guiTheme) {
        SwingUtilities.updateComponentTreeUI(this);
    }

    @Override
    public void applyTheme(GuiTheme guiTheme, boolean wordWrap) {
        SwingUtilities.updateComponentTreeUI(this);
    }

    private JLabel createMcpServerUrlErrorLabel() {
        return createErrorLabel("Invalid URL");
    }

    private JLabel createErrorLabel(String text) {
        var label = new JLabel(text);
        var errorColor = UIManager.getColor("Label.errorForeground");
        if (errorColor == null) errorColor = new Color(219, 49, 49);
        label.setForeground(errorColor);
        label.setVisible(false);
        return label;
    }

    private boolean isUrlValid(String text) {
        text = text.trim();
        if (text.isEmpty()) return false;
        try {
            URL u = new URI(text).toURL();
            String host = u.getHost();
            return host != null && !host.isEmpty();
        } catch (URISyntaxException | MalformedURLException ex) {
            return false;
        }
    }

    // --- MCP dialog helpers (reused from original implementation) ---
    private void showMcpServerDialog(String title, @Nullable HttpMcpServer existing, Consumer<McpServer> onSave) {
        final var fetchedTools =
                new AtomicReference<@Nullable List<String>>(existing != null ? existing.tools() : null);
        final var fetchedToolDetails = new AtomicReference<@Nullable List<McpSchema.Tool>>(null);
        JTextField nameField = new JTextField(existing != null ? existing.name() : "");
        JTextField urlField = new JTextField(existing != null ? existing.url().toString() : "");
        JCheckBox useTokenCheckbox = new JCheckBox("Use Bearer Token");
        JPasswordField tokenField = new JPasswordField();
        JLabel tokenLabel = new JLabel("Bearer Token:");
        var showTokenButton = new JToggleButton(Icons.VISIBILITY_OFF);
        showTokenButton.setToolTipText("Show/Hide token");
        showTokenButton.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
        showTokenButton.setContentAreaFilled(false);
        showTokenButton.setCursor(new Cursor(Cursor.HAND_CURSOR));

        char defaultEchoChar = tokenField.getEchoChar();
        showTokenButton.addActionListener(ae -> {
            if (showTokenButton.isSelected()) {
                tokenField.setEchoChar((char) 0);
                showTokenButton.setIcon(Icons.VISIBILITY);
            } else {
                tokenField.setEchoChar(defaultEchoChar);
                showTokenButton.setIcon(Icons.VISIBILITY_OFF);
            }
        });

        var tokenPanel = new JPanel(new BorderLayout());
        tokenPanel.add(tokenField, BorderLayout.CENTER);
        tokenPanel.add(showTokenButton, BorderLayout.EAST);

        String existingToken = existing != null ? existing.bearerToken() : null;
        if (existingToken != null && !existingToken.isEmpty()) {
            useTokenCheckbox.setSelected(true);
            String displayToken = existingToken;
            if (displayToken.regionMatches(false, 0, "Bearer ", 0, 7)) {
                displayToken = displayToken.substring(7);
            }
            tokenField.setText(displayToken);
            tokenLabel.setVisible(true);
            tokenPanel.setVisible(true);
        } else {
            tokenLabel.setVisible(false);
            tokenPanel.setVisible(false);
        }

        useTokenCheckbox.addActionListener(ae -> {
            boolean sel = useTokenCheckbox.isSelected();
            tokenLabel.setVisible(sel);
            tokenPanel.setVisible(sel);
            SwingUtilities.invokeLater(() -> {
                Window w = SwingUtilities.getWindowAncestor(tokenPanel);
                if (w != null) w.pack();
                tokenPanel.revalidate();
                tokenPanel.repaint();
            });
        });

        JLabel fetchStatusLabel = new JLabel(" ");

        var toolsTable = new McpToolTable();
        var toolsScrollPane = new JScrollPane(toolsTable);
        toolsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        toolsScrollPane.setPreferredSize(new Dimension(650, 240));
        toolsScrollPane.setVisible(true);

        var errorTextArea = new JTextArea(5, 20);
        errorTextArea.setEditable(false);
        errorTextArea.setLineWrap(true);
        errorTextArea.setWrapStyleWord(true);
        var errorScrollPane = new JScrollPane(errorTextArea);
        errorScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        errorScrollPane.setPreferredSize(new Dimension(650, 240));
        errorScrollPane.setVisible(false);

        if (fetchedTools.get() != null && !fetchedTools.get().isEmpty()) {
            toolsTable.setToolsFromNames(fetchedTools.get());
            toolsScrollPane.setVisible(true);
            errorScrollPane.setVisible(false);
        }

        AtomicReference<String> lastFetchedUrl =
                new AtomicReference<>(existing != null ? existing.url().toString() : null);
        final AtomicLong lastFetchStartedAt = new AtomicLong(0L);

        Runnable fetcher = () -> {
            String rawUrl = urlField.getText().trim();
            if (!isUrlValid(rawUrl)) {
                return;
            }

            URL urlObj;
            try {
                urlObj = new URI(rawUrl).toURL();
            } catch (MalformedURLException | URISyntaxException ex) {
                return;
            }

            String bearerToken = null;
            if (useTokenCheckbox.isSelected()) {
                String rawToken = new String(tokenField.getPassword()).trim();
                if (!rawToken.isEmpty()) {
                    if (rawToken.regionMatches(true, 0, "Bearer ", 0, 7)) {
                        bearerToken = rawToken;
                    } else {
                        bearerToken = "Bearer " + rawToken;
                    }
                }
            }
            final String finalBearerToken = bearerToken;

            lastFetchedUrl.set(rawUrl);
            lastFetchStartedAt.set(System.currentTimeMillis());

            Callable<List<McpSchema.Tool>> callable = () -> McpUtils.fetchTools(urlObj, finalBearerToken);
            initiateMcpToolsFetch(
                    fetchStatusLabel,
                    callable,
                    toolsTable,
                    toolsScrollPane,
                    errorTextArea,
                    errorScrollPane,
                    fetchedToolDetails,
                    fetchedTools);
        };

        final Timer[] throttleTimer = new Timer[1];
        Runnable validationAction = () -> {
            String current = urlField.getText().trim();
            if (!isUrlValid(current)) {
                return;
            }
            String previous = lastFetchedUrl.get();
            if (previous != null && previous.equals(current)) {
                return;
            }
            if (throttleTimer[0] != null && throttleTimer[0].isRunning()) {
                throttleTimer[0].stop();
            }
            long now = System.currentTimeMillis();
            long startedAt = lastFetchStartedAt.get();
            long elapsed = now - startedAt;
            if (startedAt == 0L || elapsed >= 2000L) {
                fetcher.run();
            } else {
                int delay = (int) (2000L - elapsed);
                throttleTimer[0] = new Timer(delay, ev -> {
                    String latest = urlField.getText().trim();
                    if (!isUrlValid(latest)) {
                        return;
                    }
                    String last = lastFetchedUrl.get();
                    if (last != null && last.equals(latest)) {
                        return;
                    }
                    fetcher.run();
                });
                throttleTimer[0].setRepeats(false);
                throttleTimer[0].start();
            }
        };

        JLabel urlErrorLabel = createMcpServerUrlErrorLabel();
        JLabel nameErrorLabel = createErrorLabel("Duplicate name");
        nameField.getDocument().addDocumentListener(new DocumentListener() {
            private void validateName() {
                String candidate = nameField.getText().trim();
                if (candidate.isEmpty()) {
                    candidate = urlField.getText().trim();
                }
                boolean duplicate = false;
                for (int i = 0; i < mcpServersListModel.getSize(); i++) {
                    McpServer s = mcpServersListModel.getElementAt(i);
                    if (existing != null && s.name().equalsIgnoreCase(existing.name())) {
                        continue;
                    }
                    if (s.name().equalsIgnoreCase(candidate)) {
                        duplicate = true;
                        break;
                    }
                }
                nameErrorLabel.setVisible(duplicate);
                SwingUtilities.invokeLater(() -> {
                    Window w = SwingUtilities.getWindowAncestor(nameField);
                    if (w != null) w.pack();
                });
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                validateName();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateName();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateName();
            }
        });

        SwingUtilities.invokeLater(() -> {
            Window w = SwingUtilities.getWindowAncestor(nameField);
            nameErrorLabel.setVisible(false);
            if (w != null) w.pack();
        });

        urlField.getDocument()
                .addDocumentListener(createUrlValidationListener(urlField, urlErrorLabel, validationAction));

        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.WEST;

        // Row 0: Name
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(nameField, gbc);

        // Row 1: Name Error
        gbc.gridx = 1;
        gbc.gridy = 1;
        panel.add(nameErrorLabel, gbc);

        // Row 2: URL
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(new JLabel("URL:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(urlField, gbc);

        // Row 3: URL Error
        gbc.gridx = 1;
        gbc.gridy = 3;
        panel.add(urlErrorLabel, gbc);

        // Row 4: Token checkbox
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        panel.add(useTokenCheckbox, gbc);
        gbc.gridwidth = 1;

        // Row 5: Token
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        panel.add(tokenLabel, gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        panel.add(tokenPanel, gbc);

        // Row 6: Fetch Status
        gbc.gridx = 1;
        gbc.gridy = 6;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(fetchStatusLabel, gbc);

        // Row 7: Tools Pane
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.insets = new Insets(8, 0, 0, 0);
        panel.add(toolsScrollPane, gbc);
        panel.add(errorScrollPane, gbc);

        var optionPane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        final var dialog = optionPane.createDialog(SettingsGlobalPanel.this, title);
        dialog.setResizable(true);
        var preferred = dialog.getPreferredSize();
        int minWidth = Math.max(800, preferred.width);
        int prefHeight = Math.max(500, preferred.height);
        dialog.setMinimumSize(new Dimension(700, 400));
        dialog.setPreferredSize(new Dimension(minWidth, prefHeight));
        dialog.pack();
        dialog.setLocationRelativeTo(SettingsGlobalPanel.this);

        optionPane.addPropertyChangeListener(pce -> {
            if (pce.getSource() != optionPane || !pce.getPropertyName().equals(JOptionPane.VALUE_PROPERTY)) {
                return;
            }
            var value = optionPane.getValue();
            if (value == JOptionPane.UNINITIALIZED_VALUE) {
                return;
            }

            if (value.equals(JOptionPane.OK_OPTION)) {
                String name = nameField.getText().trim();
                String rawUrl = urlField.getText().trim();
                boolean useToken = useTokenCheckbox.isSelected();

                String effectiveName = name.isEmpty() ? rawUrl : name;
                boolean duplicate = false;
                for (int i = 0; i < mcpServersListModel.getSize(); i++) {
                    McpServer s = mcpServersListModel.getElementAt(i);
                    if (existing != null && s.name().equalsIgnoreCase(existing.name())) {
                        continue;
                    }
                    if (s.name().equalsIgnoreCase(effectiveName)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    nameErrorLabel.setVisible(true);
                    dialog.pack();
                    dialog.setVisible(true);
                    optionPane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                    return;
                }

                HttpMcpServer newServer =
                        createMcpServerFromInputs(name, rawUrl, useToken, tokenField, fetchedTools.get());

                if (newServer != null) {
                    onSave.accept(newServer);
                    dialog.setVisible(false);
                } else {
                    urlErrorLabel.setVisible(true);
                    dialog.pack();
                    dialog.setVisible(true);
                    optionPane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                }
            } else {
                dialog.setVisible(false);
            }
        });

        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                String current = urlField.getText().trim();
                if (existing != null && fetchedToolDetails.get() == null && isUrlValid(current)) {
                    fetcher.run();
                }
            }
        });
        dialog.setVisible(true);
    }

    private void showStdioMcpServerDialog(String title, @Nullable StdioMcpServer existing, Consumer<McpServer> onSave) {

        JTextField nameField = new JTextField(existing != null ? existing.name() : "");
        JTextField commandField = new JTextField(existing != null ? existing.command() : "");

        List<String> initialArgs = existing != null ? existing.args() : new ArrayList<>();
        Map<String, String> initialEnv = existing != null ? existing.env() : Collections.emptyMap();

        ArgsTableModel argsModel = new ArgsTableModel(initialArgs);
        JTable argsTable = new JTable(argsModel);
        argsTable.setFillsViewportHeight(true);
        argsTable.setRowHeight(argsTable.getRowHeight() + 2);

        EnvTableModel envModel = new EnvTableModel(initialEnv);
        JTable envTable = new JTable(envModel);
        envTable.getColumnModel().getColumn(1).setCellRenderer(new EnvVarCellRenderer());
        envTable.setFillsViewportHeight(true);
        envTable.setRowHeight(envTable.getRowHeight() + 2);

        JLabel nameErrorLabel = createErrorLabel("Duplicate name");
        nameErrorLabel.setVisible(false);

        final var fetchedTools =
                new AtomicReference<@Nullable List<String>>(existing != null ? existing.tools() : null);
        final var fetchedToolDetails = new AtomicReference<@Nullable List<McpSchema.Tool>>(null);

        JLabel fetchStatusLabel = new JLabel(" ");

        var toolsTable = new McpToolTable();
        var toolsScrollPane = new JScrollPane(toolsTable);
        toolsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        toolsScrollPane.setPreferredSize(new Dimension(650, 240));
        toolsScrollPane.setVisible(true);

        var fetchErrorTextArea = new JTextArea(5, 20);
        fetchErrorTextArea.setEditable(false);
        fetchErrorTextArea.setLineWrap(true);
        fetchErrorTextArea.setWrapStyleWord(true);
        var fetchErrorScrollPane = new JScrollPane(fetchErrorTextArea);
        fetchErrorScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        fetchErrorScrollPane.setPreferredSize(new Dimension(650, 240));
        fetchErrorScrollPane.setVisible(false);

        if (fetchedTools.get() != null && !fetchedTools.get().isEmpty()) {
            toolsTable.setToolsFromNames(fetchedTools.get());
            toolsScrollPane.setVisible(true);
            fetchErrorScrollPane.setVisible(false);
        }

        nameField.getDocument().addDocumentListener(new DocumentListener() {
            private void validateName() {
                String candidate = nameField.getText().trim();
                if (candidate.isEmpty()) {
                    candidate = commandField.getText().trim();
                }
                boolean duplicate = false;
                for (int i = 0; i < mcpServersListModel.getSize(); i++) {
                    McpServer s = mcpServersListModel.getElementAt(i);
                    if (existing != null && s.name().equalsIgnoreCase(existing.name())) {
                        continue;
                    }
                    if (s.name().equalsIgnoreCase(candidate)) {
                        duplicate = true;
                        break;
                    }
                }
                nameErrorLabel.setVisible(duplicate);
                SwingUtilities.invokeLater(() -> {
                    Window w = SwingUtilities.getWindowAncestor(nameField);
                    if (w != null) w.pack();
                });
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                validateName();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateName();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateName();
            }
        });

        var argsScroll = new JScrollPane(argsTable);
        argsScroll.setPreferredSize(new Dimension(400, 120));
        var argsButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        var addArgButton = new MaterialButton("Add");
        var removeArgButton = new MaterialButton("Remove");
        argsButtons.add(addArgButton);
        argsButtons.add(removeArgButton);
        addArgButton.addActionListener(e -> {
            if (argsTable.isEditing()) argsTable.getCellEditor().stopCellEditing();
            argsModel.addRow();
            int last = argsModel.getRowCount() - 1;
            if (last >= 0) {
                argsTable.setRowSelectionInterval(last, last);
                argsTable.editCellAt(last, 0);
                Component editor = argsTable.getEditorComponent();
                if (editor != null) editor.requestFocusInWindow();
            }
        });
        removeArgButton.addActionListener(e -> {
            int viewRow = argsTable.getSelectedRow();
            if (viewRow != -1) {
                if (argsTable.isEditing()) argsTable.getCellEditor().stopCellEditing();
                int modelRow = argsTable.convertRowIndexToModel(viewRow);
                argsModel.removeRow(modelRow);
            }
        });

        var envScroll = new JScrollPane(envTable);
        envScroll.setPreferredSize(new Dimension(400, 150));
        var envButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        var addEnvButton = new MaterialButton("Add");
        var removeEnvButton = new MaterialButton("Remove");
        envButtons.add(addEnvButton);
        envButtons.add(removeEnvButton);
        addEnvButton.addActionListener(e -> {
            if (envTable.isEditing()) envTable.getCellEditor().stopCellEditing();
            envModel.addRow();
            int last = envModel.getRowCount() - 1;
            if (last >= 0) {
                envTable.setRowSelectionInterval(last, last);
                envTable.editCellAt(last, 0);
                Component editor = envTable.getEditorComponent();
                if (editor != null) editor.requestFocusInWindow();
            }
        });
        removeEnvButton.addActionListener(e -> {
            int viewRow = envTable.getSelectedRow();
            if (viewRow != -1) {
                if (envTable.isEditing()) envTable.getCellEditor().stopCellEditing();
                int modelRow = envTable.convertRowIndexToModel(viewRow);
                envModel.removeRow(modelRow);
            }
        });

        var fetchButton = new MaterialButton("Fetch Tools");
        fetchButton.addActionListener(e -> {
            String cmd = commandField.getText().trim();
            if (cmd.isEmpty()) {
                fetchErrorTextArea.setText("Command cannot be empty.");
                toolsScrollPane.setVisible(false);
                fetchErrorScrollPane.setVisible(true);
                Window w = SwingUtilities.getWindowAncestor(fetchErrorScrollPane);
                if (w != null) w.pack();
                return;
            }
            List<String> args = new ArrayList<>(argsModel.getArgs());
            Map<String, String> env = envModel.getEnvMap();

            Callable<List<McpSchema.Tool>> callable = () -> McpUtils.fetchTools(cmd, args, env, null);
            initiateMcpToolsFetch(
                    fetchStatusLabel,
                    callable,
                    toolsTable,
                    toolsScrollPane,
                    fetchErrorTextArea,
                    fetchErrorScrollPane,
                    fetchedToolDetails,
                    fetchedTools);
        });

        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;

        panel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(nameField, gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(nameErrorLabel, gbc);

        gbc.insets = new Insets(8, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel("Command:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(commandField, gbc);

        gbc.insets = new Insets(8, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("Arguments:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.BOTH;
        var argsContainer = new JPanel(new BorderLayout(5, 5));
        argsContainer.add(argsScroll, BorderLayout.CENTER);
        argsContainer.add(argsButtons, BorderLayout.SOUTH);
        panel.add(argsContainer, gbc);

        gbc.insets = new Insets(8, 5, 2, 5);
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("Environment:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.BOTH;
        var envContainer = new JPanel(new BorderLayout(5, 5));
        envContainer.add(envScroll, BorderLayout.CENTER);

        var envButtonsRow = new JPanel(new BorderLayout(5, 0));
        envButtonsRow.add(envButtons, BorderLayout.WEST);

        Icon helpIcon = Icons.HELP;
        if (helpIcon instanceof ThemedIcon themedHelpIcon) {
            helpIcon = themedHelpIcon.withSize(14);
        }
        var envHelpButton = new MaterialButton();
        envHelpButton.setIcon(helpIcon);
        envHelpButton.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        envHelpButton.setContentAreaFilled(false);
        envHelpButton.setFocusPainted(false);
        envHelpButton.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
        envHelpButton.setToolTipText(
                "You can use environment variables as values, e.g., $HOME or ${HOME}. If a variable is not set, the literal text is used.");
        envButtonsRow.add(envHelpButton, BorderLayout.EAST);

        envContainer.add(envButtonsRow, BorderLayout.SOUTH);
        panel.add(envContainer, gbc);

        var fetchControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        fetchControls.add(fetchButton);
        fetchControls.add(fetchStatusLabel);
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.gridwidth = 2;
        gbc.weightx = 0.0;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(5, 5, 5, 5);
        panel.add(fetchControls, gbc);

        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(8, 5, 5, 5);
        panel.add(toolsScrollPane, gbc);
        panel.add(fetchErrorScrollPane, gbc);

        var optionPane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        final var dialog = optionPane.createDialog(SettingsGlobalPanel.this, title);
        dialog.setResizable(true);
        var preferred = dialog.getPreferredSize();
        int minWidth = Math.max(800, preferred.width);
        int prefHeight = Math.max(500, preferred.height);
        dialog.setMinimumSize(new Dimension(700, 400));
        dialog.setPreferredSize(new Dimension(minWidth, prefHeight));
        dialog.pack();
        dialog.setLocationRelativeTo(SettingsGlobalPanel.this);

        optionPane.addPropertyChangeListener(pce -> {
            if (pce.getSource() != optionPane || !pce.getPropertyName().equals(JOptionPane.VALUE_PROPERTY)) {
                return;
            }
            var value = optionPane.getValue();
            if (value == JOptionPane.UNINITIALIZED_VALUE) {
                return;
            }

            if (value.equals(JOptionPane.OK_OPTION)) {
                String name = nameField.getText().trim();
                String command = commandField.getText().trim();
                if (name.isEmpty()) {
                    name = command;
                }

                boolean duplicate = false;
                for (int i = 0; i < mcpServersListModel.getSize(); i++) {
                    McpServer s = mcpServersListModel.getElementAt(i);
                    if (existing != null && s.name().equalsIgnoreCase(existing.name())) {
                        continue;
                    }
                    if (s.name().equalsIgnoreCase(name)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    nameErrorLabel.setVisible(true);
                    dialog.pack();
                    dialog.setVisible(true);
                    optionPane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                    return;
                }

                if (command.isEmpty()) {
                    fetchErrorTextArea.setText("Command cannot be empty.");
                    toolsScrollPane.setVisible(false);
                    fetchErrorScrollPane.setVisible(true);
                    dialog.pack();
                    dialog.setVisible(true);
                    optionPane.setValue(JOptionPane.UNINITIALIZED_VALUE);
                    return;
                }

                List<String> args = new ArrayList<>(argsModel.getArgs());
                Map<String, String> env = envModel.getEnvMap();
                StdioMcpServer toSave = new StdioMcpServer(name, command, args, env, fetchedTools.get());
                onSave.accept(toSave);
                dialog.setVisible(false);
            } else {
                dialog.setVisible(false);
            }
        });

        dialog.setVisible(true);
    }

    /**
     * Shared helper: create a debounced URL DocumentListener that validates the URL and toggles the provided error
     * label. Debounce interval is 500ms.
     */
    private DocumentListener createUrlValidationListener(
            JTextField urlField, JLabel urlErrorLabel, Runnable onValidUrl) {
        final Timer[] debounceTimer = new Timer[1];
        return new DocumentListener() {
            private void scheduleValidation() {
                if (debounceTimer[0] != null && debounceTimer[0].isRunning()) {
                    debounceTimer[0].stop();
                }
                debounceTimer[0] = new Timer(500, ev -> {
                    String text = urlField.getText().trim();
                    boolean ok = isUrlValid(text);
                    urlErrorLabel.setVisible(!ok);
                    if (ok) {
                        onValidUrl.run();
                    }
                    SwingUtilities.invokeLater(() -> {
                        Window w = SwingUtilities.getWindowAncestor(urlField);
                        if (w != null) w.pack();
                    });
                });
                debounceTimer[0].setRepeats(false);
                debounceTimer[0].start();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                scheduleValidation();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                scheduleValidation();
            }
        };
    }

    /**
     * Helper to validate inputs from Add/Edit dialogs and construct an McpServer. Returns null if validation failed.
     *
     * <p>This method also normalizes bearer token inputs if they start with "Bearer " and updates the provided
     * tokenField with the normalized value (so the user sees the normalized form).
     */
    private @Nullable HttpMcpServer createMcpServerFromInputs(
            String name, String rawUrl, boolean useToken, JPasswordField tokenField, @Nullable List<String> tools) {

        if (rawUrl.isEmpty()) {
            return null;
        }

        URL url;
        try {
            var u = new URI(rawUrl).toURL();
            String host = u.getHost();
            if (host == null || host.isEmpty()) throw new MalformedURLException("Missing host");
            url = u;
        } catch (Exception mfe) {
            return null;
        }

        if (name.isEmpty()) name = rawUrl;

        String token = null;
        if (useToken) {
            String raw = new String(tokenField.getPassword()).trim();
            if (!raw.isEmpty()) {
                String bearerToken;
                if (raw.regionMatches(true, 0, "Bearer ", 0, 7)) {
                    bearerToken = raw;
                    tokenField.setText(raw.substring(7).trim());
                } else {
                    bearerToken = "Bearer " + raw;
                }
                token = bearerToken;
            } else {
                token = null;
            }
        }

        return new HttpMcpServer(name, url, tools, token);
    }

    /** Initiates an asynchronous MCP tools fetch using the provided Callable and updates UI components accordingly. */
    private void initiateMcpToolsFetch(
            JLabel fetchStatusLabel,
            Callable<List<McpSchema.Tool>> fetcher,
            McpToolTable toolsTable,
            JScrollPane toolsScrollPane,
            JTextArea errorTextArea,
            JScrollPane errorScrollPane,
            AtomicReference<@Nullable List<McpSchema.Tool>> fetchedToolDetails,
            AtomicReference<@Nullable List<String>> fetchedTools) {

        fetchStatusLabel.setIcon(SpinnerIconUtil.getSpinner(this.chrome, true));
        fetchStatusLabel.setText("Fetching...");

        new SwingWorker<List<McpSchema.Tool>, Void>() {
            @Override
            protected List<McpSchema.Tool> doInBackground() throws Exception {
                return fetcher.call();
            }

            @Override
            protected void done() {
                fetchStatusLabel.setIcon(null);
                fetchStatusLabel.setText(" ");
                try {
                    List<McpSchema.Tool> tools = get();
                    fetchedToolDetails.set(tools);
                    var toolNames = tools.stream().map(McpSchema.Tool::name).collect(Collectors.toList());
                    fetchedTools.set(toolNames);

                    toolsTable.setToolsFromDetails(tools);

                    toolsScrollPane.setVisible(true);
                    errorScrollPane.setVisible(false);
                    SwingUtilities.getWindowAncestor(fetchStatusLabel).pack();
                } catch (Exception ex) {
                    var root = ex.getCause() != null ? ex.getCause() : ex;
                    logger.error("Error fetching MCP tools", root);
                    fetchedTools.set(null);
                    fetchedToolDetails.set(null);

                    errorTextArea.setText(root.getMessage());
                    toolsScrollPane.setVisible(false);
                    errorScrollPane.setVisible(true);
                    SwingUtilities.getWindowAncestor(fetchStatusLabel).pack();
                }
            }
        }.execute();
    }

    private static class ArgsTableModel extends AbstractTableModel {
        private final List<String> args;
        private final String[] columnNames = {"Argument"};

        public ArgsTableModel(List<String> args) {
            this.args = new ArrayList<>(args);
        }

        public List<String> getArgs() {
            return args;
        }

        @Override
        public int getRowCount() {
            return args.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return args.get(rowIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            args.set(rowIndex, (String) aValue);
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        public void addRow() {
            args.add("");
            fireTableRowsInserted(args.size() - 1, args.size() - 1);
        }

        public void removeRow(int rowIndex) {
            if (rowIndex >= 0 && rowIndex < args.size()) {
                args.remove(rowIndex);
                fireTableRowsDeleted(rowIndex, rowIndex);
            }
        }
    }

    private static class EnvVarCellRenderer extends DefaultTableCellRenderer {
        private static final Border SUCCESS_BORDER;
        private static final Border FAILURE_BORDER;

        static {
            Color successColor = UIManager.getColor("ProgressBar.foreground");
            if (successColor == null) {
                successColor = new Color(0, 176, 80);
            }
            SUCCESS_BORDER = BorderFactory.createMatteBorder(0, 0, 0, 2, successColor);

            Color errorColor = UIManager.getColor("Label.errorForeground");
            if (errorColor == null) {
                errorColor = new Color(219, 49, 49);
            }
            FAILURE_BORDER = BorderFactory.createMatteBorder(0, 0, 0, 2, errorColor);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, @Nullable Object value, boolean isSelected, boolean hasFocus, int row, int column) {

            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            setToolTipText(null);
            setBorder(null);

            if (value instanceof String val) {
                String trimmedVal = val.trim();
                var ref = Environment.detectEnvVarReference(trimmedVal);
                if (ref != null) {
                    String varName = ref.name();
                    if (ref.defined()) {
                        setToolTipText("Environment variable '" + varName + "' found.");
                        setBorder(SUCCESS_BORDER);
                    } else {
                        setToolTipText("Environment variable '" + varName
                                + "' not set in Brokk's environment. Using the literal text as-is.");
                        setBorder(FAILURE_BORDER);
                    }
                }
            }
            return this;
        }
    }

    private static class EnvTableModel extends AbstractTableModel {
        private final List<String[]> envVars;
        private final String[] columnNames = {"Variable", "Value"};

        public EnvTableModel(Map<String, String> env) {
            this.envVars = new ArrayList<>(env.entrySet().stream()
                    .map(e -> new String[] {e.getKey(), e.getValue()})
                    .collect(Collectors.toList()));
        }

        public Map<String, String> getEnvMap() {
            return envVars.stream()
                    .filter(p -> p[0] != null && !p[0].trim().isEmpty())
                    .collect(Collectors.toMap(p -> p[0], p -> p[1] != null ? p[1] : "", (v1, v2) -> v2));
        }

        @Override
        public int getRowCount() {
            return envVars.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return envVars.get(rowIndex)[columnIndex];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            envVars.get(rowIndex)[columnIndex] = (String) aValue;
            fireTableCellUpdated(rowIndex, columnIndex);
        }

        public void addRow() {
            envVars.add(new String[] {"", ""});
            fireTableRowsInserted(envVars.size() - 1, envVars.size() - 1);
        }

        public void removeRow(int rowIndex) {
            if (rowIndex >= 0 && rowIndex < envVars.size()) {
                envVars.remove(rowIndex);
                fireTableRowsDeleted(rowIndex, rowIndex);
            }
        }
    }

    // SettingsChangeListener implementation
    @Override
    public void gitHubTokenChanged() {
        gitHubSettingsPanel.gitHubTokenChanged();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        MainProject.removeSettingsChangeListener(this);
    }

    // Default keybinding helpers and defaults
    private static KeyStroke defaultToggleMode() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_M);
    }

    private static KeyStroke defaultUndo() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_Z);
    }

    private static KeyStroke defaultRedo() {
        return KeyboardShortcutUtil.createPlatformShiftShortcut(KeyEvent.VK_Z);
    }

    private static KeyStroke defaultCopy() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_C);
    }

    private static KeyStroke defaultPaste() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_V);
    }

    private static KeyStroke defaultToggleMicrophone() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_L);
    }

    private static KeyStroke defaultSwitchToProjectFiles() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_1, modifier);
    }

    private static KeyStroke defaultSwitchToDependencies() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_2, modifier);
    }

    private static KeyStroke defaultSwitchToChanges() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_3, modifier);
    }

    private static KeyStroke defaultSwitchToWorktrees() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_4, modifier);
    }

    private static KeyStroke defaultSwitchToLog() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_5, modifier);
    }

    private static KeyStroke defaultSwitchToPullRequests() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_6, modifier);
    }

    private static KeyStroke defaultSwitchToIssues() {
        int modifier = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")
                ? KeyEvent.META_DOWN_MASK
                : KeyEvent.ALT_DOWN_MASK;
        return KeyStroke.getKeyStroke(KeyEvent.VK_7, modifier);
    }

    private static KeyStroke defaultCloseWindow() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_W);
    }

    private static KeyStroke defaultCloseTab() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_W);
    }

    private static KeyStroke defaultOpenSettings() {
        return KeyboardShortcutUtil.createPlatformShortcut(KeyEvent.VK_COMMA);
    }

    private static KeyStroke defaultToggleTerminalDrawer() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
    }

    private static KeyStroke defaultToggleDependenciesDrawer() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_D, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK);
    }

    private static KeyStroke defaultSwitchToTerminalTab() {
        return KeyStroke.getKeyStroke(KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private static KeyStroke defaultSwitchToTasksTab() {
        return KeyStroke.getKeyStroke(KeyEvent.VK_K, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private static KeyStroke defaultZoomIn() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_PLUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private static KeyStroke defaultZoomInAlt() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_EQUALS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private static KeyStroke defaultZoomOut() {
        return KeyStroke.getKeyStroke(
                KeyEvent.VK_MINUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    private static KeyStroke defaultResetZoom() {
        return KeyStroke.getKeyStroke(KeyEvent.VK_0, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
    }

    @SuppressWarnings("UnusedMethod")
    private static KeyStroke defaultFor(String id) {
        return switch (id) {
            case "instructions.submit" -> KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
            case "instructions.toggleMode" -> defaultToggleMode();
            case "global.undo" -> defaultUndo();
            case "global.redo" -> defaultRedo();
            case "global.copy" -> defaultCopy();
            case "global.paste" -> defaultPaste();
            case "global.toggleMicrophone" -> defaultToggleMicrophone();
            case "panel.switchToProjectFiles" -> defaultSwitchToProjectFiles();
            case "panel.switchToDependencies" -> defaultSwitchToDependencies();
            case "panel.switchToChanges" -> defaultSwitchToChanges();
            case "panel.switchToWorktrees" -> defaultSwitchToWorktrees();
            case "panel.switchToLog" -> defaultSwitchToLog();
            case "panel.switchToPullRequests" -> defaultSwitchToPullRequests();
            case "panel.switchToIssues" -> defaultSwitchToIssues();
            case "drawer.toggleTerminal" -> defaultToggleTerminalDrawer();
            case "drawer.toggleDependencies" -> defaultToggleDependenciesDrawer();
            case "drawer.switchToTerminal" -> defaultSwitchToTerminalTab();
            case "drawer.switchToTasks" -> defaultSwitchToTasksTab();
            case "view.zoomIn" -> defaultZoomIn();
            case "view.zoomInAlt" -> defaultZoomInAlt();
            case "view.zoomOut" -> defaultZoomOut();
            case "view.resetZoom" -> defaultResetZoom();
            case "global.openSettings" -> defaultOpenSettings();
            case "global.closeWindow" -> defaultCloseWindow();
            case "global.closeTab" -> defaultCloseTab();
            case "workspace.attachContext" -> KeyboardShortcutUtil.createPlatformShiftShortcut(KeyEvent.VK_I);
            case "workspace.attachFilesAndSummarize" ->
                KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK);
            default ->
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_ENTER, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        };
    }

    private static String getThemeDisplayName(String themeValue) {
        return switch (themeValue) {
            case GuiTheme.THEME_LIGHT -> "Light";
            case GuiTheme.THEME_LIGHT_PLUS -> "Light+";
            case GuiTheme.THEME_DARK -> "Dark";
            case GuiTheme.THEME_DARK_PLUS -> "Dark+";
            case GuiTheme.THEME_HIGH_CONTRAST -> "High Contrast";
            default -> "Light";
        };
    }

    private static String getThemeValueFromDisplayName(String displayName) {
        return switch (displayName) {
            case "Light" -> GuiTheme.THEME_LIGHT;
            case "Light+" -> GuiTheme.THEME_LIGHT_PLUS;
            case "Dark" -> GuiTheme.THEME_DARK;
            case "Dark+" -> GuiTheme.THEME_DARK_PLUS;
            case "High Contrast" -> GuiTheme.THEME_HIGH_CONTRAST;
            default -> GuiTheme.THEME_LIGHT;
        };
    }
}
