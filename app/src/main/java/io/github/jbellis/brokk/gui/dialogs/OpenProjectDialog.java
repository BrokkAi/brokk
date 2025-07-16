package io.github.jbellis.brokk.gui.dialogs;

import io.github.jbellis.brokk.Brokk;
import io.github.jbellis.brokk.MainProject;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.GitUiUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Optional;

public class OpenProjectDialog extends JDialog
{
    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile("https://github.com/([^/]+)/([^/\\s]+)");
    private final @Nullable Frame parentFrame;
    private @Nullable Path selectedProjectPath = null;

    public OpenProjectDialog(@Nullable Frame parent)
    {
        super(parent, "Open Project", true);
        this.parentFrame = parent;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        initComponents();
        pack();
        setLocationRelativeTo(parent);
    }

    private void initComponents()
    {
        var mainPanel = new JPanel(new BorderLayout());

        var leftPanel = new JPanel();
        leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
        leftPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        var iconUrl = Brokk.class.getResource(Brokk.ICON_RESOURCE);
        if (iconUrl != null)
        {
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

            var versionLabel = new JLabel("Brokk " + io.github.jbellis.brokk.BuildInfo.version);
            versionLabel.setFont(versionLabel.getFont().deriveFont(Font.PLAIN, 12f));
            versionLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            leftPanel.add(Box.createVerticalStrut(10)); // Add some space
            leftPanel.add(versionLabel);
        }

        var tabbedPane = new JTabbedPane();
        var knownProjectsPanel = createKnownProjectsPanel();
        if (knownProjectsPanel != null)
        {
            tabbedPane.addTab("Known Projects", knownProjectsPanel);
        }
        tabbedPane.addTab("Open Local", createOpenLocalPanel());
        tabbedPane.addTab("Clone from Git", createClonePanel());

        mainPanel.add(leftPanel, BorderLayout.WEST);
        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        setContentPane(mainPanel);
    }

    @Nullable
    private JPanel createKnownProjectsPanel()
    {
        var panel = new JPanel(new BorderLayout());
        String[] columnNames = { "Project", "Last Opened" };

        var tableModel = new DefaultTableModel(columnNames, 0)
        {
            @Override
            public boolean isCellEditable(int row, int column)
            {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex)
            {
                return columnIndex == 1 ? Instant.class : String.class;
            }
        };

        var recentProjects = MainProject.loadRecentProjects();
        if (recentProjects.isEmpty())
        {
            return null;
        }
        var today = LocalDate.now(ZoneId.systemDefault());
        for (var entry : recentProjects.entrySet())
        {
            var path = entry.getKey();
            var metadata = entry.getValue();
            var lastOpenedInstant = Instant.ofEpochMilli(metadata.lastOpened());
            tableModel.addRow(new Object[]{path.toString(), lastOpenedInstant});
        }

        var table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(1).setCellRenderer((table1, value, isSelected, hasFocus, row, column) -> {
            var label = new JLabel(GitUiUtil.formatRelativeDate((Instant) value, today));
            label.setOpaque(true);
            if (isSelected)
            {
                label.setBackground(table1.getSelectionBackground());
                label.setForeground(table1.getSelectionForeground());
            }
            else
            {
                label.setBackground(table1.getBackground());
                label.setForeground(table1.getForeground());
            }
            label.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
            return label;
        });

        var sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
        sorter.setComparator(0, Comparator.comparing(Object::toString));

        table.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt)
            {
                if (evt.getClickCount() == 2)
                {
                    int viewRow = table.getSelectedRow();
                    if (viewRow >= 0)
                    {
                        int modelRow = table.convertRowIndexToModel(viewRow);
                        String pathString = (String) tableModel.getValueAt(modelRow, 0);
                        openProject(Paths.get(pathString));
                    }
                }
            }
        });

        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        var openButton = new JButton("Open Selected");
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(openButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        openButton.addActionListener(e -> {
            int viewRow = table.getSelectedRow();
            if (viewRow >= 0)
            {
                int modelRow = table.convertRowIndexToModel(viewRow);
                String pathString = (String) tableModel.getValueAt(modelRow, 0);
                openProject(Paths.get(pathString));
            }
        });

        return panel;
    }

    private JPanel createOpenLocalPanel()
    {
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
            if (selectedFile != null)
            {
                openProject(selectedFile.toPath());
            }
        });

        return panel;
    }

    private JPanel createClonePanel()
    {
        var panel = new JPanel(new GridBagLayout());
        var gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; panel.add(new JLabel("Repository URL:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.gridwidth = 2; gbc.weightx = 1.0;
        var urlField = new JTextField(40);
        panel.add(urlField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.0; panel.add(new JLabel("Directory:"), gbc);
        var dirField = new JTextField(System.getProperty("user.home"));

        var chooseIcon = UIManager.getIcon("FileChooser.directoryIcon");
        if (chooseIcon == null)
        {
            chooseIcon = UIManager.getIcon("FileView.directoryIcon");
        }
        var chooseButton = chooseIcon != null ? new JButton(chooseIcon) : new JButton("...");
        if (chooseIcon != null)
        {
            var iconDim = new Dimension(chooseIcon.getIconWidth(), chooseIcon.getIconHeight());
            chooseButton.setPreferredSize(iconDim);
            chooseButton.setMinimumSize(iconDim);
            chooseButton.setMaximumSize(iconDim);
            chooseButton.setMargin(new Insets(0, 0, 0, 0));
        }
        else
        {
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

        gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(directoryInputPanel, gbc);

        var shallowCloneCheckbox = new JCheckBox("Shallow clone with");
        var depthSpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1));
        depthSpinner.setEnabled(false);
        ((JSpinner.DefaultEditor) depthSpinner.getEditor()).getTextField().setColumns(3);
        var commitsLabel = new JLabel("commits");

        var shallowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        shallowPanel.add(shallowCloneCheckbox);
        shallowPanel.add(depthSpinner);
        shallowPanel.add(commitsLabel);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(shallowPanel, gbc);

        shallowCloneCheckbox.addActionListener(e -> depthSpinner.setEnabled(shallowCloneCheckbox.isSelected()));

        chooseButton.addActionListener(e -> {
            var chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Directory to Clone Into");
            chooser.setCurrentDirectory(new File(System.getProperty("user.home")));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            {
                dirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        var cloneButton = new JButton("Clone and Open");
        var buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cloneButton);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 3; gbc.anchor = GridBagConstraints.SOUTHEAST;
gbc.fill = GridBagConstraints.NONE;
gbc.weightx = 1.0; gbc.weighty = 1.0;
panel.add(buttonPanel, gbc);

        cloneButton.addActionListener(e -> cloneAndOpen(urlField.getText(),
                                                        dirField.getText(),
                                                        shallowCloneCheckbox.isSelected(),
                                                        (Integer) depthSpinner.getValue()));
        return panel;
    }

    private void cloneAndOpen(String url, String dir, boolean shallow, int depth)
    {
        if (url.isBlank() || dir.isBlank())
        {
            JOptionPane.showMessageDialog(this, "URL and Directory must be provided.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        var normalizedUrl = normalizeGitUrl(url);
        var directory = Paths.get(dir);

        var worker = new SwingWorker<Path, Void>()
        {
            @Override
            protected @Nullable Path doInBackground() throws Exception
            {
                var progressDialog = new JDialog(parentFrame, "Cloning...", true);
                var progressBar = new JProgressBar();
                progressBar.setIndeterminate(true);
                progressDialog.add(new JLabel("Cloning repository from " + normalizedUrl), BorderLayout.NORTH);
                progressDialog.add(progressBar, BorderLayout.CENTER);
                progressDialog.pack();
                progressDialog.setLocationRelativeTo(parentFrame);
                var dialogClosed = new AtomicBoolean(false);
                progressDialog.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                        dialogClosed.set(true);
                    }
                });

                var cloneTask = new Thread(() -> {
                    try
                    {
                        GitRepo.cloneRepo(normalizedUrl, directory, shallow ? depth : 0);
                    }
                    catch (Exception e)
                    {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(OpenProjectDialog.this,
                                                                                       "Failed to clone repository: " + e.getMessage(),
                                                                                       "Clone Failed",
                                                                                       JOptionPane.ERROR_MESSAGE));
                        throw new RuntimeException(e);
                    }
                    finally
                    {
                        SwingUtilities.invokeLater(progressDialog::dispose);
                    }
                });
                cloneTask.start();
                progressDialog.setVisible(true); // blocks until dispose() is called

                if (dialogClosed.get()) {
                    // It is not simple to interrupt JGit clone, so we just let it finish in background but don't open project.
                    return null;
                }

                return directory;
            }

            @Override
            protected void done()
            {
                try
                {
                    Path projectPath = get();
                    if (projectPath != null)
                    {
                        openProject(projectPath);
                    }
                }
                catch (Exception e)
                {
                    // Error was already shown in doInBackground
                }
            }
        };
        worker.execute();
    }

    private static String normalizeGitUrl(String url)
    {
        Matcher matcher = GITHUB_URL_PATTERN.matcher(url.trim());
        if (matcher.matches())
        {
            String repo = matcher.group(2);
            if (repo.endsWith(".git"))
            {
                return url;
            }
            return url + ".git";
        }
        return url;
    }

    private void openProject(Path projectPath)
    {
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
    public static Optional<Path> showDialog(@Nullable Frame owner)
    {
        var dlg = new OpenProjectDialog(owner);
        dlg.setVisible(true);   // modal; blocks
        return Optional.ofNullable(dlg.selectedProjectPath);
    }
}
