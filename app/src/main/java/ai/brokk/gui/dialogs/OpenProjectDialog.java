package ai.brokk.gui.dialogs;

import ai.brokk.Brokk;
import ai.brokk.BuildInfo;
import ai.brokk.GitHubAuth;
import ai.brokk.MainProject;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.gui.SwingUtil;
import ai.brokk.gui.components.MaterialButton;
import ai.brokk.gui.util.GitUiUtil;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenProjectDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger(OpenProjectDialog.class);

    private static record GitHubRepoInfo(
            String fullName,
            String description,
            String httpsUrl,
            String sshUrl,
            Instant lastUpdated,
            boolean isPrivate) {}

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile("https://github.com/([^/]+)/([^/\\s]+)");
    private final @Nullable Frame parentFrame;
    private @Nullable Path selectedProjectPath = null;
    private List<GitHubRepoInfo> loadedRepositories = List.of();

    // Tab state management
    private JTabbedPane tabbedPane;
    private int gitHubTabIndex = -1;
    private JPanel gitHubReposPanel;

    public OpenProjectDialog(@Nullable Frame parent) {
        super(parent, "Open Project", true);
        this.parentFrame = parent;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        initComponents();
        pack();
        setLocationRelativeTo(parent);
    }

    private void initComponents() {
        var mainPanel = new JPanel(new BorderLayout());

        var leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var iconUrl = Brokk.class.getResource(Brokk.ICON_RESOURCE);
        if (iconUrl != null) {
            var originalIcon = new ImageIcon(iconUrl);
            var image = originalIcon.getImage().getScaledInstance(128, 128, Image.SCALE_SMOOTH);
            var projectsLabel = new JLabel("Projects");
            projectsLabel.setFont(projectsLabel.getFont().deriveFont(Font.BOLD, 24f));
            projectsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            leftPanel.add(projectsLabel);
            leftPanel.add(Box.createVerticalStrut(20)); // Add some space

            var iconLabel = new JLabel(new ImageIcon(image));
            iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            leftPanel.add(iconLabel);

            var versionLabel = new JLabel("Brokk " + BuildInfo.version);
            versionLabel.setFont(versionLabel.getFont().deriveFont(Font.PLAIN, 12f));
            versionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            leftPanel.add(Box.createVerticalStrut(10)); // Add some space
            leftPanel.add(versionLabel);
        }

        tabbedPane = new JTabbedPane();
        var knownProjectsPanel = createKnownProjectsPanel();
        if (knownProjectsPanel != null) {
            tabbedPane.addTab("Known Projects", knownProjectsPanel);
        }
        tabbedPane.addTab("Open Local", createOpenLocalPanel());
        tabbedPane.addTab("Clone from Git", createClonePanel());

        // Always add GitHub repositories tab, but control its enabled state
        gitHubReposPanel = createGitHubReposPanel();
        gitHubTabIndex = tabbedPane.getTabCount();
        tabbedPane.addTab("GitHub Repositories", gitHubReposPanel);

        // Set initial state based on token presence
        if (GitHubAuth.tokenPresent()) {
            enableGitHubTab();
            validateTokenAndLoadRepositories();
        } else {
            disableGitHubTab("No GitHub token configured. Go to Settings → GitHub to configure your token.");
        }

        mainPanel.add(leftPanel, BorderLayout.WEST);
        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        setContentPane(mainPanel);
    }

    @Nullable
    private JPanel createKnownProjectsPanel() {
        var panel = new JPanel(new BorderLayout());
        String[] columnNames = {"Project", "Last Opened"};

        var tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 1 ? Instant.class : String.class;
            }
        };

        var recentProjects = MainProject.loadRecentProjects();
        if (recentProjects.isEmpty()) {
            return null;
        }
        var today = LocalDate.now(ZoneId.systemDefault());
        for (var entry : recentProjects.entrySet()) {
            var path = entry.getKey();
            var metadata = entry.getValue();
            var lastOpenedInstant = Instant.ofEpochMilli(metadata.lastOpened());
            tableModel.addRow(new Object[] {path.toString(), lastOpenedInstant});
        }

        var table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(1).setCellRenderer((table1, value, isSelected, hasFocus, row, column) -> {
            var label = new JLabel(GitUiUtil.formatRelativeDate((Instant) value, today));
            label.setOpaque(true);
            if (isSelected) {
                label.setBackground(table1.getSelectionBackground());
                label.setForeground(table1.getSelectionForeground());
            } else {
                label.setBackground(table1.getBackground());
                label.setForeground(table1.getForeground());
            }
            label.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
            return label;
        });

        var sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    int viewRow = table.getSelectedRow();
                    if (viewRow >= 0) {
                        int modelRow = table.convertRowIndexToModel(viewRow);
                        String pathString = (String) tableModel.getValueAt(modelRow, 0);
                        openProject(Paths.get(pathString));
                    }
                }
            }
        });

        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        var openButton = new MaterialButton("Open Selected");
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(openButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        openButton.addActionListener(e -> {
            int viewRow = table.getSelectedRow();
            if (viewRow >= 0) {
                int modelRow = table.convertRowIndexToModel(viewRow);
                String pathString = (String) tableModel.getValueAt(modelRow, 0);
                openProject(Paths.get(pathString));
            }
        });

        return panel;
    }

    private JPanel createOpenLocalPanel() {
        var panel = new JPanel(new BorderLayout());
        var chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select a project directory");
        chooser.setControlButtonsAreShown(false);
        panel.add(chooser, BorderLayout.CENTER);

        var openButton = new JButton("Open");
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(openButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        openButton.addActionListener(e -> {
            var selectedFile = chooser.getSelectedFile();
            if (selectedFile != null) {
                openProject(selectedFile.toPath());
            }
        });

        return panel;
    }

    private JPanel createClonePanel() {
        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Repository URL:"), gbc);
        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        var urlField = new JTextField(40);
        panel.add(urlField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Directory:"), gbc);
        var dirField = new JTextField(System.getProperty("user.home"));

        var chooseIcon = UIManager.getIcon("FileChooser.directoryIcon");
        if (chooseIcon == null) {
            chooseIcon = UIManager.getIcon("FileView.directoryIcon");
        }
        MaterialButton chooseButton;
        if (chooseIcon != null) {
            chooseButton = new MaterialButton();
            chooseButton.setIcon(chooseIcon);
        } else {
            chooseButton = new MaterialButton("...");
        }
        if (chooseIcon != null) {
            var iconDim = new Dimension(chooseIcon.getIconWidth(), chooseIcon.getIconHeight());
            chooseButton.setPreferredSize(iconDim);
            chooseButton.setMinimumSize(iconDim);
            chooseButton.setMaximumSize(iconDim);
            chooseButton.setMargin(new Insets(0, 0, 0, 0));
        } else {
            chooseButton.setMargin(new Insets(0, 0, 0, 0));
            var size = chooseButton.getPreferredSize();
            var minDim = new Dimension(size.height, size.height);
            chooseButton.setPreferredSize(minDim);
            chooseButton.setMinimumSize(minDim);
            chooseButton.setMaximumSize(minDim);
        }
        chooseButton.setToolTipText("Choose directory");

        var directoryInputPanel = new JPanel(new BorderLayout(5, 0));
        directoryInputPanel.add(dirField, BorderLayout.CENTER);
        directoryInputPanel.add(chooseButton, BorderLayout.EAST);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(directoryInputPanel, gbc);

        // Load persisted shallow clone preferences
        boolean shallowEnabled = MainProject.getGitHubShallowCloneEnabled();
        int shallowDepth = MainProject.getGitHubShallowCloneDepth();

        var shallowCloneCheckbox = new JCheckBox("Shallow clone with", shallowEnabled);
        var depthSpinner = new JSpinner(new SpinnerNumberModel(shallowDepth, 1, Integer.MAX_VALUE, 1));
        depthSpinner.setEnabled(shallowEnabled);
        ((JSpinner.DefaultEditor) depthSpinner.getEditor()).getTextField().setColumns(3);
        var commitsLabel = new JLabel("commits");

        var shallowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        shallowPanel.add(shallowCloneCheckbox);
        shallowPanel.add(depthSpinner);
        shallowPanel.add(commitsLabel);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(shallowPanel, gbc);

        shallowCloneCheckbox.addActionListener(e -> {
            boolean selected = shallowCloneCheckbox.isSelected();
            depthSpinner.setEnabled(selected);
            MainProject.setGitHubShallowCloneEnabled(selected);
        });

        // Save depth when changed
        depthSpinner.addChangeListener(e -> {
            MainProject.setGitHubShallowCloneDepth((Integer) depthSpinner.getValue());
        });

        chooseButton.addActionListener(e -> {
            var chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Directory to Clone Into");
            chooser.setCurrentDirectory(new File(System.getProperty("user.home")));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                dirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        var cloneButton = new MaterialButton("Clone and Open");
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cloneButton);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 3;
        gbc.anchor = GridBagConstraints.SOUTHEAST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        panel.add(buttonPanel, gbc);

        cloneButton.addActionListener(
                e -> cloneAndOpen(urlField.getText(), dirField.getText(), shallowCloneCheckbox.isSelected(), (Integer)
                        depthSpinner.getValue()));
        return panel;
    }

    private JPanel createGitHubReposPanel() {
        var panel = new JPanel(new BorderLayout());

        String[] columnNames = {"Repository", "Description", "Type", "Updated"};
        var tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 3 -> Instant.class;
                    default -> String.class;
                };
            }
        };

        var table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.getColumnModel().getColumn(2).setCellRenderer(this::renderTypeColumn);
        table.getColumnModel().getColumn(3).setCellRenderer(this::renderDateColumn);

        var sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.DESCENDING)));

        var scrollPane = new JScrollPane(table);
        panel.add(scrollPane, BorderLayout.CENTER);

        var controlsPanel = createGitHubControlsPanel(table, tableModel);
        panel.add(controlsPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createGitHubControlsPanel(JTable table, DefaultTableModel tableModel) {
        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel("Directory:"), gbc);

        var dirField = new JTextField(System.getProperty("user.home"), 30);
        var chooseIcon = UIManager.getIcon("FileChooser.directoryIcon");
        if (chooseIcon == null) {
            chooseIcon = UIManager.getIcon("FileView.directoryIcon");
        }
        var chooseDirButton = chooseIcon != null ? new JButton(chooseIcon) : new JButton("...");
        if (chooseIcon != null) {
            var iconDim = new Dimension(chooseIcon.getIconWidth(), chooseIcon.getIconHeight());
            chooseDirButton.setPreferredSize(iconDim);
            chooseDirButton.setMinimumSize(iconDim);
            chooseDirButton.setMaximumSize(iconDim);
            chooseDirButton.setMargin(new Insets(0, 0, 0, 0));
        } else {
            chooseDirButton.setMargin(new Insets(0, 0, 0, 0));
            var size = chooseDirButton.getPreferredSize();
            var minDim = new Dimension(size.height, size.height);
            chooseDirButton.setPreferredSize(minDim);
            chooseDirButton.setMinimumSize(minDim);
            chooseDirButton.setMaximumSize(minDim);
        }
        chooseDirButton.setToolTipText("Choose directory");

        chooseDirButton.addActionListener(e -> {
            var chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Directory to Clone Into");
            chooser.setCurrentDirectory(new File(System.getProperty("user.home")));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                dirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        var dirPanel = new JPanel(new BorderLayout(5, 0));
        dirPanel.add(dirField, BorderLayout.CENTER);
        dirPanel.add(chooseDirButton, BorderLayout.EAST);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(dirPanel, gbc);

        // Load persisted protocol preference
        String preferredProtocol = MainProject.getGitHubCloneProtocol();
        boolean useHttps = "https".equals(preferredProtocol);

        var httpsRadio = new JRadioButton("HTTPS", useHttps);
        var sshRadio = new JRadioButton("SSH", !useHttps);
        var protocolGroup = new ButtonGroup();
        protocolGroup.add(httpsRadio);
        protocolGroup.add(sshRadio);

        var protocolPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        protocolPanel.add(new JLabel("Protocol: "));
        protocolPanel.add(httpsRadio);
        protocolPanel.add(sshRadio);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(protocolPanel, gbc);

        // Shallow clone controls - load persisted preferences
        boolean shallowEnabled = MainProject.getGitHubShallowCloneEnabled();
        int shallowDepth = MainProject.getGitHubShallowCloneDepth();

        var shallowCloneCheckbox = new JCheckBox("Shallow clone with", shallowEnabled);
        var depthSpinner = new JSpinner(new SpinnerNumberModel(shallowDepth, 1, Integer.MAX_VALUE, 1));
        depthSpinner.setEnabled(shallowEnabled);
        ((JSpinner.DefaultEditor) depthSpinner.getEditor()).getTextField().setColumns(3);
        var commitsLabel = new JLabel("commits");

        var shallowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        shallowPanel.add(shallowCloneCheckbox);
        shallowPanel.add(depthSpinner);
        shallowPanel.add(commitsLabel);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(shallowPanel, gbc);

        // Add persistence listeners
        httpsRadio.addActionListener(e -> {
            if (httpsRadio.isSelected()) {
                MainProject.setGitHubCloneProtocol("https");
            }
        });

        sshRadio.addActionListener(e -> {
            if (sshRadio.isSelected()) {
                MainProject.setGitHubCloneProtocol("ssh");
            }
        });

        shallowCloneCheckbox.addActionListener(e -> {
            boolean selected = shallowCloneCheckbox.isSelected();
            depthSpinner.setEnabled(selected);
            MainProject.setGitHubShallowCloneEnabled(selected);
        });

        // Save depth when changed
        depthSpinner.addChangeListener(e -> {
            MainProject.setGitHubShallowCloneDepth((Integer) depthSpinner.getValue());
        });

        var refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> {
            // Re-check token presence and update tab state accordingly
            if (GitHubAuth.tokenPresent()) {
                enableGitHubTab();
            } else {
                disableGitHubTab("No GitHub token configured. Go to Settings → GitHub to configure your token.");
            }
            loadRepositoriesAsync(tableModel, true);
        });

        var cloneButton = new JButton("Clone and Open");
        cloneButton.addActionListener(e -> cloneSelectedRepository(
                table,
                tableModel,
                dirField.getText(),
                httpsRadio.isSelected(),
                shallowCloneCheckbox.isSelected(),
                (Integer) depthSpinner.getValue()));

        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(refreshButton);
        buttonPanel.add(cloneButton);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.EAST;
        gbc.fill = GridBagConstraints.NONE;
        gbc.weightx = 0.0;
        panel.add(buttonPanel, gbc);

        return panel;
    }

    private Component renderTypeColumn(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        return createStyledLabel(value.toString(), table, isSelected);
    }

    private Component renderDateColumn(
            JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        var dateText = GitUiUtil.formatRelativeDate((Instant) value, LocalDate.now(ZoneId.systemDefault()));
        return createStyledLabel(dateText, table, isSelected);
    }

    private JLabel createStyledLabel(String text, JTable table, boolean isSelected) {
        var label = new JLabel(text);
        label.setOpaque(true);
        if (isSelected) {
            label.setBackground(table.getSelectionBackground());
            label.setForeground(table.getSelectionForeground());
        } else {
            label.setBackground(table.getBackground());
            label.setForeground(table.getForeground());
        }
        label.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        return label;
    }

    private void enableGitHubTab() {
        if (gitHubTabIndex == -1) {
            // Tab needs to be added
            gitHubTabIndex = tabbedPane.getTabCount();
            tabbedPane.addTab("GitHub Repositories", gitHubReposPanel);
        }
        tabbedPane.setEnabledAt(gitHubTabIndex, true);
        tabbedPane.setToolTipTextAt(gitHubTabIndex, "Browse your GitHub repositories");
        logger.debug("Enabled GitHub repositories tab");
    }

    private void disableGitHubTab(String reason) {
        if (gitHubTabIndex != -1) {
            tabbedPane.setEnabledAt(gitHubTabIndex, false);
            tabbedPane.setToolTipTextAt(gitHubTabIndex, reason);
            logger.debug("Disabled GitHub repositories tab: {}", reason);
        }
    }

    private void cloneAndOpen(String url, String dir, boolean shallow, int depth) {
        if (url.isBlank() || dir.isBlank()) {
            JOptionPane.showMessageDialog(
                    this, "URL and Directory must be provided.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        final var normalizedUrl = normalizeGitUrl(url);
        final var directory = Paths.get(dir);

        // 1. Build the modal progress dialog on the EDT
        final var progressDialog = new JDialog(parentFrame, "Cloning...", true);
        var progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressDialog.add(new JLabel("Cloning repository from " + normalizedUrl), BorderLayout.NORTH);
        progressDialog.add(progressBar, BorderLayout.CENTER);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(parentFrame);

        // 2. Start background clone
        var worker = new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                // Heavy-weight Git operation happens off the EDT
                GitRepoFactory.cloneRepo(normalizedUrl, directory, shallow ? depth : 0);
                return directory;
            }

            @Override
            protected void done() {
                progressDialog.dispose();
                if (!isDisplayable()) {
                    return;
                }
                try {
                    Path projectPath = get();
                    if (projectPath != null) {
                        openProject(projectPath);
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(
                            OpenProjectDialog.this,
                            "Failed to clone repository: " + e.getMessage(),
                            "Clone Failed",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();

        // 3. Show the dialog (modal) – this blocks the EDT but continues to
        //    dispatch events, so the SwingWorker can complete in background.
        progressDialog.setVisible(true);
    }

    private static String normalizeGitUrl(String url) {
        Matcher matcher = GITHUB_URL_PATTERN.matcher(url.trim());
        if (matcher.matches()) {
            String repo = matcher.group(2);
            if (repo.endsWith(".git")) {
                return url;
            }
            return url + ".git";
        }
        return url;
    }

    private void validateTokenAndLoadRepositories() {
        if (!GitHubAuth.tokenPresent()) {
            disableGitHubTab("No GitHub token configured. Go to Settings → GitHub to configure your token.");
            return;
        }

        logger.info("Validating GitHub token and loading repositories");
        var tableModel = (DefaultTableModel) ((JTable) ((JScrollPane) gitHubReposPanel.getComponent(0))
                        .getViewport()
                        .getView())
                .getModel();

        var worker = new SwingWorker<List<GitHubRepoInfo>, Void>() {
            @Override
            protected List<GitHubRepoInfo> doInBackground() throws Exception {
                // First validate the token with a simple API call
                var token = GitHubAuth.getStoredToken();
                var github = new GitHubBuilder().withOAuthToken(token).build();
                github.getMyself(); // This will throw if token is invalid

                // Token is valid, now load repositories
                return getUserRepositories();
            }

            @Override
            protected void done() {
                if (!isDisplayable()) {
                    return;
                }
                try {
                    var repositories = get();
                    logger.info("Successfully loaded {} GitHub repositories", repositories.size());
                    loadedRepositories = repositories;
                    populateTable(tableModel, repositories);
                    enableGitHubTab();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("GitHub repository loading was interrupted");
                    disableGitHubTab("GitHub repository loading was interrupted.");
                    clearTable(tableModel);
                } catch (ExecutionException e) {
                    var cause = e.getCause();
                    if (cause instanceof HttpException httpEx && httpEx.getResponseCode() == 401) {
                        logger.warn("GitHub token is invalid, clearing stored token");
                        GitHubAuth.invalidateInstance();
                        disableGitHubTab(
                                "GitHub token is invalid or expired. Go to Settings → GitHub to update your token.");
                        clearTable(tableModel);
                    } else {
                        var errorMessage = cause != null ? cause.getMessage() : e.getMessage();
                        logger.error("Failed to load GitHub repositories", cause != null ? cause : e);
                        disableGitHubTab("Failed to load GitHub repositories: " + errorMessage);
                        clearTable(tableModel);
                    }
                }
            }
        };

        showLoadingState(tableModel);
        worker.execute();
    }

    private void loadRepositoriesAsync(DefaultTableModel tableModel, boolean forceRefresh) {
        if (!forceRefresh && !loadedRepositories.isEmpty()) {
            logger.debug("Using cached repositories ({} repos)", loadedRepositories.size());
            populateTable(tableModel, loadedRepositories);
            enableGitHubTab();
            return;
        }

        if (!GitHubAuth.tokenPresent()) {
            disableGitHubTab("No GitHub token configured. Go to Settings → GitHub to configure your token.");
            clearTable(tableModel);
            return;
        }

        logger.info("Starting GitHub repository load (force refresh: {})", forceRefresh);
        var worker = new SwingWorker<List<GitHubRepoInfo>, Void>() {
            @Override
            protected List<GitHubRepoInfo> doInBackground() throws Exception {
                // Validate token first
                var token = GitHubAuth.getStoredToken();
                var github = new GitHubBuilder().withOAuthToken(token).build();
                github.getMyself(); // This will throw if token is invalid

                return getUserRepositories();
            }

            @Override
            protected void done() {
                if (!isDisplayable()) {
                    return;
                }
                try {
                    var repositories = get();
                    logger.info("Successfully loaded {} GitHub repositories", repositories.size());
                    loadedRepositories = repositories;
                    populateTable(tableModel, repositories);
                    enableGitHubTab();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("GitHub repository loading was interrupted");
                    disableGitHubTab("GitHub repository loading was interrupted.");
                    clearTable(tableModel);
                } catch (ExecutionException e) {
                    var cause = e.getCause();
                    if (cause instanceof HttpException httpEx && httpEx.getResponseCode() == 401) {
                        logger.warn("GitHub token is invalid, clearing stored token");
                        GitHubAuth.invalidateInstance();
                        disableGitHubTab(
                                "GitHub token is invalid or expired. Go to Settings → GitHub to update your token.");
                    } else {
                        var errorMessage = cause != null ? cause.getMessage() : e.getMessage();
                        logger.error("Failed to load GitHub repositories", cause != null ? cause : e);
                        disableGitHubTab("Failed to load GitHub repositories: " + errorMessage);
                    }
                    clearTable(tableModel);
                }
            }
        };

        showLoadingState(tableModel);
        worker.execute();
    }

    private void populateTable(DefaultTableModel tableModel, List<GitHubRepoInfo> repositories) {
        tableModel.setRowCount(0);

        for (var repo : repositories) {
            tableModel.addRow(new Object[] {
                repo.fullName(),
                truncateDescription(repo.description()),
                repo.isPrivate() ? "Private" : "Public",
                repo.lastUpdated()
            });
        }
    }

    private void showLoadingState(DefaultTableModel tableModel) {
        tableModel.setRowCount(0);
        tableModel.addRow(new Object[] {"Loading repositories...", "", "", Instant.now()});
    }

    private void clearTable(DefaultTableModel tableModel) {
        tableModel.setRowCount(0);
    }

    private String truncateDescription(String description) {
        if (description.isBlank()) return "";
        return description.length() > 50 ? description.substring(0, 47) + "..." : description;
    }

    private void cloneSelectedRepository(
            JTable table,
            DefaultTableModel tableModel,
            String directory,
            boolean useHttps,
            boolean shallow,
            int depth) {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(this, "Please select a repository to clone.");
            return;
        }

        int modelRow = table.convertRowIndexToModel(viewRow);
        var repoFullName = (String) tableModel.getValueAt(modelRow, 0);

        var repoInfoOpt = findRepositoryByName(repoFullName);
        if (repoInfoOpt.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Repository information not found.");
            return;
        }
        var repoInfo = repoInfoOpt.get();

        // Validate directory before proceeding
        if (directory.isBlank()) {
            JOptionPane.showMessageDialog(this, "Directory must be provided.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        var targetPath = Paths.get(directory);
        if (!Files.exists(targetPath)) {
            try {
                Files.createDirectories(targetPath);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(
                        this,
                        "Cannot create target directory: " + e.getMessage(),
                        "Directory Error",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        if (!Files.isDirectory(targetPath) || !Files.isWritable(targetPath)) {
            JOptionPane.showMessageDialog(
                    this,
                    "Target path is not a writable directory: " + directory,
                    "Directory Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        var cloneUrl = useHttps ? repoInfo.httpsUrl() : repoInfo.sshUrl();
        var protocol = useHttps ? "HTTPS" : "SSH";

        logger.info(
                "User initiated clone: repository={}, protocol={}, targetDir={}, shallow={}, depth={}",
                repoFullName,
                protocol,
                directory,
                shallow,
                depth);

        cloneAndOpen(cloneUrl, directory, shallow, depth);
    }

    private Optional<GitHubRepoInfo> findRepositoryByName(String fullName) {
        return loadedRepositories.stream()
                .filter(repo -> repo.fullName().equals(fullName))
                .findFirst();
    }

    private static List<GitHubRepoInfo> getUserRepositories() throws Exception {
        var token = GitHubAuth.getStoredToken();
        if (token.isBlank()) {
            throw new IllegalStateException("No GitHub token available");
        }

        try {
            var github = new GitHubBuilder().withOAuthToken(token).build();
            var repositories = new ArrayList<GHRepository>();
            int count = 0;
            for (var repo : github.getMyself().listRepositories()) {
                repositories.add(repo);
                if (++count >= 100) break;
            }
            return repositories.stream()
                    .map(repo -> {
                        try {
                            return new GitHubRepoInfo(
                                    repo.getFullName(),
                                    repo.getDescription() != null ? repo.getDescription() : "",
                                    repo.getHttpTransportUrl(),
                                    repo.getSshUrl(),
                                    repo.getUpdatedAt() != null
                                            ? repo.getUpdatedAt().toInstant()
                                            : Instant.now(),
                                    repo.isPrivate());
                        } catch (Exception e) {
                            logger.warn("Failed to process repository {}: {}", repo.getFullName(), e.getMessage());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(GitHubRepoInfo::lastUpdated).reversed())
                    .toList();
        } catch (HttpException e) {
            if (e.getResponseCode() == 401) {
                throw new IllegalStateException("GitHub token is invalid or expired");
            } else if (e.getResponseCode() == 403) {
                throw new IllegalStateException("GitHub API rate limit exceeded or access forbidden");
            } else {
                throw new IOException("GitHub API error (HTTP " + e.getResponseCode() + "): " + e.getMessage(), e);
            }
        } catch (ConnectException | UnknownHostException e) {
            throw new IOException("Network connection failed. Please check your internet connection.", e);
        } catch (SocketTimeoutException e) {
            throw new IOException("GitHub API request timed out. Please try again.", e);
        }
    }

    private void openProject(Path projectPath) {
        if (!Files.isDirectory(projectPath)) {
            var message = "The selected path is not a directory.";
            JOptionPane.showMessageDialog(this, message, "Invalid Project", JOptionPane.ERROR_MESSAGE);
            return;
        }

        selectedProjectPath = projectPath;
        dispose();
    }

    /**
     * Shows a modal dialog letting the user pick a project and returns it.
     *
     * @param owner the parent frame (may be {@code null})
     * @return Optional containing the selected project path; empty if the user cancelled
     */
    public static Optional<Path> showDialog(@Nullable Frame owner) {
        var selectedPath = SwingUtil.runOnEdt(
                () -> {
                    var dlg = new OpenProjectDialog(owner);
                    dlg.setVisible(true); // modal; blocks
                    return dlg.selectedProjectPath;
                },
                null);
        return Optional.ofNullable(selectedPath);
    }
}
