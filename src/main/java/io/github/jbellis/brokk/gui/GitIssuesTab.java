package io.github.jbellis.brokk.gui;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.CustomMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.issues.*;
import io.github.jbellis.brokk.util.HtmlUtil;
import io.github.jbellis.brokk.util.ImageUtil;
import io.github.jbellis.brokk.gui.components.GitHubTokenMissingPanel;
import io.github.jbellis.brokk.gui.components.LoadingTextBox;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import javax.swing.Timer;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.jbellis.brokk.util.Environment;
import org.jetbrains.annotations.Nullable;

public class GitIssuesTab extends JPanel implements SettingsChangeListener {
    private static final Logger logger = LogManager.getLogger(GitIssuesTab.class);

    // Issue Table Column Indices
    private static final int ISSUE_COL_NUMBER = 0;
    private static final int ISSUE_COL_TITLE = 1;
    private static final int ISSUE_COL_AUTHOR = 2;
    private static final int ISSUE_COL_UPDATED = 3;
    private static final int ISSUE_COL_LABELS = 4;
    private static final int ISSUE_COL_ASSIGNEES = 5;
    private static final int ISSUE_COL_STATUS = 6;

    private final Chrome chrome;
    private final ContextManager contextManager;
    private final GitPanel gitPanel;

    private JTable issueTable;
    private DefaultTableModel issueTableModel;
    private JTextPane issueBodyTextPane;
    private JButton copyIssueDescriptionButton;
    private JButton openInBrowserButton;
    private JButton captureButton;

    private FilterBox statusFilter;
    @Nullable
    private FilterBox resolutionFilter; // Initialized conditionally
    private FilterBox authorFilter;
    private FilterBox labelFilter;
    private FilterBox assigneeFilter;
    private LoadingTextBox searchBox;
    private Timer searchDebounceTimer;
    private static final int SEARCH_DEBOUNCE_DELAY = 400; // ms for search debounce
    private String lastSearchQuery = "";

    // Debouncing for issue description loading
    private static final int DESCRIPTION_DEBOUNCE_DELAY = 250; // ms
    private final Timer descriptionDebounceTimer;
    @Nullable
    private IssueHeader pendingHeaderForDescription;
    @Nullable
    private Future<?> currentDescriptionFuture;


    // Context Menu for Issue Table
    private JPopupMenu issueContextMenu;

    // Shared actions for buttons and menu items
    private Action copyDescriptionAction;
    private Action openInBrowserAction;
    private Action captureAction;

    private List<IssueHeader> allIssuesFromApi = new ArrayList<>();
    private List<IssueHeader> displayedIssues = new ArrayList<>();

    // Store default options for static filters to easily reset them
    private static final List<String> STATUS_FILTER_OPTIONS = List.of("Open", "Closed"); // "All" is null selection
    private final List<String> actualStatusFilterOptions = new ArrayList<>(STATUS_FILTER_OPTIONS);

    private volatile Future<?> currentSearchFuture;
    private final GfmRenderer gfmRenderer;
    private final OkHttpClient httpClient;
    private final IssueService issueService;
    private final Set<Future<?>> futuresToBeCancelledOnGutHubTokenChange = ConcurrentHashMap.newKeySet();


    public GitIssuesTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel, IssueService issueService) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.issueService = issueService;
        this.gitPanel = gitPanel;
        this.gfmRenderer = new GfmRenderer();
        this.httpClient = initializeHttpClient();
        
        // Initialize nullable fields to avoid NullAway errors
        this.pendingHeaderForDescription = null;
        this.currentDescriptionFuture = null;
        
        // Load dynamic statuses after issueService and statusFilter are initialized
        var future = contextManager.submitBackgroundTask("Load Available Issue Statuses", () -> {
            List<String> fetchedStatuses = null;
            try {
                if (this.issueService != null) { // Ensure issueService is available
                    fetchedStatuses = this.issueService.listAvailableStatuses();
                } else {
                    logger.warn("IssueService is null, cannot load available statuses.");
                }
            } catch (IOException e) {
                logger.error("Failed to load available issue statuses. Falling back to defaults.", e);
            }

            final List<String> finalFetchedStatuses = fetchedStatuses;
            SwingUtilities.invokeLater(() -> {
                synchronized (actualStatusFilterOptions) {
                    actualStatusFilterOptions.clear();
                    if (finalFetchedStatuses != null && !finalFetchedStatuses.isEmpty()) {
                        actualStatusFilterOptions.addAll(finalFetchedStatuses);
                    } else {
                        actualStatusFilterOptions.addAll(STATUS_FILTER_OPTIONS); // Fallback
                    }
                }
                if (statusFilter != null) {
                    // If FilterBox needs an explicit update method, it should be called here.
                    // For now, assuming it might re-fetch from its optionsProvider when next opened.
                }
            });
            return null;
        });
        trackCancellableFuture(future);

        // Split panel with Issues on left (larger) and issue description on right (smaller)
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.6); // 60% for Issue list, 40% for details

        // --- Left side - Issues table and filters ---
        JPanel mainIssueAreaPanel = new JPanel(new BorderLayout(0, Constants.V_GAP)); // Main panel for left side
        mainIssueAreaPanel.setBorder(BorderFactory.createTitledBorder("Issues"));

        // Panel to hold token message (if any) and search bar
        JPanel topContentPanel = new JPanel();
        topContentPanel.setLayout(new BoxLayout(topContentPanel, BoxLayout.Y_AXIS));

        GitHubTokenMissingPanel gitHubTokenMissingPanel = new GitHubTokenMissingPanel(chrome);
        JPanel tokenPanelWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        tokenPanelWrapper.add(gitHubTokenMissingPanel);
        topContentPanel.add(tokenPanelWrapper);

        // Search Panel
        JPanel searchPanel = new JPanel(new BorderLayout(Constants.H_GAP, 0));
        // Removed setBorder for searchPanel as it will be part of issueTableAndButtonsPanel
        searchBox = new LoadingTextBox("Search", 20, chrome); // Placeholder text "Search"
        searchBox.asTextField().setToolTipText("Search issues (Ctrl+F to focus)"); // Set tooltip on the inner JTextField
        searchPanel.add(searchBox, BorderLayout.CENTER);

        // topContentPanel no longer contains searchPanel
        mainIssueAreaPanel.add(topContentPanel, BorderLayout.NORTH);

        searchDebounceTimer = new Timer(SEARCH_DEBOUNCE_DELAY, e -> {
            logger.debug("Search debounce timer triggered. Updating issue list with query: {}", searchBox.getText());
            updateIssueList();
        });
        searchDebounceTimer.setRepeats(false);

        descriptionDebounceTimer = new Timer(DESCRIPTION_DEBOUNCE_DELAY, e -> {
            if (pendingHeaderForDescription != null) {
                // Cancel any previously initiated description load if it's still running
                if (currentDescriptionFuture != null && !currentDescriptionFuture.isDone()) {
                    currentDescriptionFuture.cancel(true);
                }
                currentDescriptionFuture = loadAndRenderIssueBodyFromHeader(pendingHeaderForDescription);
            }
        });
        descriptionDebounceTimer.setRepeats(false);

        searchBox.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }

            private void changed() {
                var current = searchBox.getText().strip();
                if (Objects.equals(current, lastSearchQuery)) {
                    return;
                }
                lastSearchQuery = current;
                if (searchDebounceTimer.isRunning()) {
                    searchDebounceTimer.restart();
                } else {
                    searchDebounceTimer.start();
                }
            }
        });

        // Ctrl+F shortcut
        InputMap inputMap = mainIssueAreaPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = mainIssueAreaPanel.getActionMap();
        String ctrlFKey = "control F";
        inputMap.put(KeyStroke.getKeyStroke(ctrlFKey), "focusSearchField");
        actionMap.put("focusSearchField", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                searchBox.asTextField().requestFocusInWindow();
                searchBox.asTextField().selectAll();
            }
        });

        // Panel to hold filters (WEST) and table+buttons (CENTER)
        JPanel filtersAndTablePanel = new JPanel(new BorderLayout(Constants.H_GAP, 0));

        // Vertical Filter Panel with BorderLayout to keep filters at top
        JPanel verticalFilterPanel = new JPanel(new BorderLayout());

        // Container for the actual filters
        JPanel filtersContainer = new JPanel();
        filtersContainer.setLayout(new BoxLayout(filtersContainer, BoxLayout.Y_AXIS));
        filtersContainer.setBorder(BorderFactory.createEmptyBorder(0, Constants.H_PAD, 0, Constants.H_PAD));

        JLabel filterLabel = new JLabel("Filter:");
        filterLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        filtersContainer.add(filterLabel);
        filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP)); // Space after label

        if (this.issueService instanceof JiraIssueService) {
            resolutionFilter = new FilterBox(this.chrome, "Resolution", () -> List.of("Resolved", "Unresolved"), "Unresolved");
            resolutionFilter.setToolTipText("Filter by Jira issue resolution");
            resolutionFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
            // API call needed when resolution changes
            resolutionFilter.addPropertyChangeListener("value", e -> updateIssueList());
            filtersContainer.add(resolutionFilter); // Add to filtersContainer
            filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP));


            statusFilter = new FilterBox(this.chrome, "Status", () -> actualStatusFilterOptions, null); // No default for Jira status
            statusFilter.setToolTipText("Filter by Jira issue status");
        } else { // GitHub or default
            statusFilter = new FilterBox(this.chrome, "Status", () -> actualStatusFilterOptions, "Open"); // Default "Open" for GitHub
            statusFilter.setToolTipText("Filter by GitHub issue status");
        }
        statusFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusFilter.addPropertyChangeListener("value", e -> {
            // Status filter change triggers a new API fetch and subsequent processing.
            updateIssueList();
        });
        filtersContainer.add(statusFilter);
        filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP));

        authorFilter = new FilterBox(this.chrome, "Author", () -> generateFilterOptionsFromIssues(allIssuesFromApi, "author"));
        authorFilter.setToolTipText("Filter by issue author");
        authorFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        authorFilter.addPropertyChangeListener("value", e -> triggerClientSideFilterUpdate());
        filtersContainer.add(authorFilter);
        filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP));

        labelFilter = new FilterBox(this.chrome, "Label", () -> generateFilterOptionsFromIssues(allIssuesFromApi, "label"));
        labelFilter.setToolTipText("Filter by issue label");
        labelFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        labelFilter.addPropertyChangeListener("value", e -> triggerClientSideFilterUpdate());
        filtersContainer.add(labelFilter);
        filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP));

        assigneeFilter = new FilterBox(this.chrome, "Assignee", () -> generateFilterOptionsFromIssues(allIssuesFromApi, "assignee"));
        assigneeFilter.setToolTipText("Filter by issue assignee");
        assigneeFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        assigneeFilter.addPropertyChangeListener("value", e -> triggerClientSideFilterUpdate());
        filtersContainer.add(assigneeFilter);

        // Add the filters container to the north of the panel to keep them at the top
        verticalFilterPanel.add(filtersContainer, BorderLayout.NORTH);

        filtersAndTablePanel.add(verticalFilterPanel, BorderLayout.WEST);

        // Panel for Issue Table (CENTER) and Issue Buttons (SOUTH)
        JPanel issueTableAndButtonsPanel = new JPanel(new BorderLayout(0, Constants.V_GAP)); // Added V_GAP
        // Add search panel above the table, inside issueTableAndButtonsPanel
        // The searchPanel will now be constrained by the width of this panel, which is aligned with the table.
        issueTableAndButtonsPanel.add(searchPanel, BorderLayout.NORTH);

        // Issue Table
        issueTableModel = new DefaultTableModel(
                new Object[]{"#", "Title", "Author", "Updated", "Labels", "Assignees", "Status"}, 0)
        {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return String.class;
            }
        };
        issueTable = new JTable(issueTableModel);
        issueTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        issueTable.setRowHeight(18);
        issueTable.getColumnModel().getColumn(ISSUE_COL_NUMBER).setPreferredWidth(50);    // #
        issueTable.getColumnModel().getColumn(ISSUE_COL_TITLE).setPreferredWidth(350);   // Title
        issueTable.getColumnModel().getColumn(ISSUE_COL_AUTHOR).setPreferredWidth(100);   // Author
        issueTable.getColumnModel().getColumn(ISSUE_COL_UPDATED).setPreferredWidth(120); // Updated
        issueTable.getColumnModel().getColumn(ISSUE_COL_LABELS).setPreferredWidth(150);    // Labels
        issueTable.getColumnModel().getColumn(ISSUE_COL_ASSIGNEES).setPreferredWidth(150); // Assignees
        issueTable.getColumnModel().getColumn(ISSUE_COL_STATUS).setPreferredWidth(70);    // Status

        // Ensure tooltips are enabled for the table
        ToolTipManager.sharedInstance().registerComponent(issueTable);

        issueTableAndButtonsPanel.add(new JScrollPane(issueTable), BorderLayout.CENTER);

        // Create shared actions
        copyDescriptionAction = new AbstractAction("Copy Description") {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelectedIssueDescription();
            }
        };
        copyDescriptionAction.putValue(Action.SHORT_DESCRIPTION, "Copy the selected issue's description to the clipboard");
        copyDescriptionAction.setEnabled(false);

        openInBrowserAction = new AbstractAction("Open in Browser") {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSelectedIssueInBrowser();
            }
        };
        openInBrowserAction.putValue(Action.SHORT_DESCRIPTION, "Open the selected issue in your web browser");
        openInBrowserAction.setEnabled(false);

        captureAction = new AbstractAction("Capture") {
            @Override
            public void actionPerformed(ActionEvent e) {
                captureSelectedIssue();
            }
        };
        captureAction.putValue(Action.SHORT_DESCRIPTION, "Capture details of the selected issue");
        captureAction.setEnabled(false);

        // Button panel for Issues
        JPanel issueButtonPanel = new JPanel();
        issueButtonPanel.setBorder(BorderFactory.createEmptyBorder(Constants.V_GLUE, 0, 0, 0));
        issueButtonPanel.setLayout(new BoxLayout(issueButtonPanel, BoxLayout.X_AXIS));

        copyIssueDescriptionButton = new JButton(copyDescriptionAction);
        issueButtonPanel.add(copyIssueDescriptionButton);
        issueButtonPanel.add(Box.createHorizontalStrut(Constants.H_GAP));

        openInBrowserButton = new JButton(openInBrowserAction);
        issueButtonPanel.add(openInBrowserButton);
        issueButtonPanel.add(Box.createHorizontalStrut(Constants.H_GAP));

        captureButton = new JButton(captureAction);
        issueButtonPanel.add(captureButton);

        issueButtonPanel.add(Box.createHorizontalGlue()); // Pushes refresh button to the right

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> updateIssueList());
        issueButtonPanel.add(refreshButton);

        issueTableAndButtonsPanel.add(issueButtonPanel, BorderLayout.SOUTH);
        filtersAndTablePanel.add(issueTableAndButtonsPanel, BorderLayout.CENTER);
        mainIssueAreaPanel.add(filtersAndTablePanel, BorderLayout.CENTER);


        // Right side - Details of the selected issue
        JPanel issueDetailPanel = new JPanel(new BorderLayout());
        issueDetailPanel.setBorder(BorderFactory.createTitledBorder("Issue Description"));

        issueBodyTextPane = new JTextPane();
        issueBodyTextPane.setEditorKit(new AutoScalingHtmlPane.ScalingHTMLEditorKit());
        issueBodyTextPane.setEditable(false);
        issueBodyTextPane.setContentType("text/html"); // For rendering HTML
        // JTextPane handles line wrapping and word wrapping by default with HTML.
        // Basic HTML like <p> and <br> will control flow.
        // For plain text, setContentType("text/plain") would make setLineWrap relevant if it were a JTextArea.
        JScrollPane issueBodyScrollPane = new JScrollPane(issueBodyTextPane);
        issueBodyScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        issueDetailPanel.add(issueBodyScrollPane, BorderLayout.CENTER);

        // Add the panels to the split pane
        splitPane.setLeftComponent(mainIssueAreaPanel);
        splitPane.setRightComponent(issueDetailPanel);

        add(splitPane, BorderLayout.CENTER);

        // Initialize context menu and items
        issueContextMenu = new JPopupMenu();
        if (chrome.themeManager != null) {
            chrome.themeManager.registerPopupMenu(issueContextMenu);
        } else {
            SwingUtilities.invokeLater(() -> {
                if (chrome.themeManager != null) {
                    chrome.themeManager.registerPopupMenu(issueContextMenu);
                }
            });
        }

        issueContextMenu.add(new JMenuItem(copyDescriptionAction));
        issueContextMenu.add(new JMenuItem(openInBrowserAction));
        issueContextMenu.add(new JMenuItem(captureAction));

        // Add mouse listener for context menu on issue table
        issueTable.addMouseListener(new MouseAdapter() {
            private void showPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = issueTable.rowAtPoint(e.getPoint());
                    if (row >= 0 && row < issueTable.getRowCount()) {
                        // Select the row under the mouse pointer before showing the context menu.
                        // This ensures that getSelectedRow() in action listeners returns the correct row,
                        // and triggers the ListSelectionListener to update enable/disable states.
                        issueTable.setRowSelectionInterval(row, row);
                        issueContextMenu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                showPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopup(e);
            }
        });

        // Listen for Issue selection changes
        issueTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && issueTable.getSelectedRow() != -1) {
                int viewRow = issueTable.getSelectedRow();
                if (viewRow != -1) {
                    int modelRow = issueTable.convertRowIndexToModel(viewRow);
                    if (modelRow < 0 || modelRow >= displayedIssues.size()) {
                        logger.warn("Selected row {} (model {}) is out of bounds for displayed issues size {}", viewRow, modelRow, displayedIssues.size());
                        disableIssueActionsAndClearDetails();
                        pendingHeaderForDescription = null;
                        if (descriptionDebounceTimer.isRunning()) {
                            descriptionDebounceTimer.stop();
                        }
                        return;
                    }
                    IssueHeader selectedHeader = displayedIssues.get(modelRow);

                    // Enable actions immediately for responsiveness
                    copyDescriptionAction.setEnabled(true);
                    openInBrowserAction.setEnabled(true);
                    captureAction.setEnabled(true);

                    // Debounce loading of the issue body
                    pendingHeaderForDescription = selectedHeader;
                    descriptionDebounceTimer.restart();

                } else { // No selection or invalid row
                    disableIssueActionsAndClearDetails();
                    pendingHeaderForDescription = null;
                    if (descriptionDebounceTimer.isRunning()) {
                        descriptionDebounceTimer.stop();
                    }
                }
            } else if (issueTable.getSelectedRow() == -1) { // if selection is explicitly cleared
                disableIssueActionsAndClearDetails();
                pendingHeaderForDescription = null;
                if (descriptionDebounceTimer.isRunning()) {
                    descriptionDebounceTimer.stop();
                }
            }
        });

        MainProject.addSettingsChangeListener(this);
        updateIssueList(); // async
    }

    /**
     * Tracks a Future that might contain calls to GitHub API, so that it can be cancelled if GitHub access token changes.
     */
    private void trackCancellableFuture(Future<?> future) {
        futuresToBeCancelledOnGutHubTokenChange.removeIf(Future::isDone);
        if (future != null) {
            futuresToBeCancelledOnGutHubTokenChange.add(future);
        }
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        MainProject.removeSettingsChangeListener(this);
        if (searchDebounceTimer != null) {
            searchDebounceTimer.stop();
        }
        if (descriptionDebounceTimer != null) {
            descriptionDebounceTimer.stop();
        }
    }

    @Override
    public void gitHubTokenChanged() {
        SwingUtilities.invokeLater(() -> {
            logger.debug("GitHub token changed. Initiating cancellation of active issue tasks and scheduling refresh.");

            if (searchDebounceTimer != null && searchDebounceTimer.isRunning()) {
                searchDebounceTimer.stop();
            }
            if (descriptionDebounceTimer != null && descriptionDebounceTimer.isRunning()) {
                descriptionDebounceTimer.stop();
            }
            pendingHeaderForDescription = null;

            List<Future<?>> futuresToCancelAndAwait = new ArrayList<>(futuresToBeCancelledOnGutHubTokenChange);

            logger.debug("Attempting to cancel {} issue-related futures.", futuresToCancelAndAwait.size());
            for (Future<?> f : futuresToCancelAndAwait) {
                if (!f.isDone()) {
                    f.cancel(true);
                    logger.trace("Requested cancellation for issue-related future: {}", f.toString());
                }
            }

            if (futuresToCancelAndAwait.isEmpty()) {
                logger.debug("No active issue tasks to wait for. Proceeding with issue list refresh directly.");
                updateIssueList();
                return;
            }

            // Wait for the futures to complete or be cancelled to avoid potential race conditions
            contextManager.submitBackgroundTask("Finalizing issue task cancellations and refreshing data", () -> {
                logger.debug("Waiting for {} issue-related futures to complete cancellation.", futuresToCancelAndAwait.size());
                for (Future<?> f : futuresToCancelAndAwait) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        logger.trace("Issue task cancellation confirmed for: {}", f.toString());
                    }
                }
                logger.debug("All identified issue tasks have completed cancellation. Scheduling issue list refresh.");
                SwingUtilities.invokeLater(this::updateIssueList);
                return null;
            });
        });
    }

    public GitIssuesTab(Chrome chrome, ContextManager contextManager, GitPanel gitPanel) {
        this(chrome, contextManager, gitPanel, createDefaultIssueService(contextManager));
    }

    private static IssueService createDefaultIssueService(ContextManager contextManager) {
        IProject project = contextManager.getProject();
        // This line will cause a compile error until IProject.getIssuesProvider() is added. This is expected.
        io.github.jbellis.brokk.issues.IssueProviderType providerType = project.getIssuesProvider().type();
        Logger staticLogger = LogManager.getLogger(GitIssuesTab.class);

        switch (providerType) {
            case JIRA:
                staticLogger.info("Using JiraIssueService for project {} (provider: JIRA)", project.getRoot().getFileName());
                return new JiraIssueService(project);
            case GITHUB:
            case NONE: // Explicitly handle NONE, though it might default to GitHub or a NoOp service later
            default: // Default to GitHub if enum is somehow null or unexpected value, or NONE
                staticLogger.info("Using GitHubIssueService for project {} (provider: {})", project.getRoot().getFileName(), providerType);
                return new GitHubIssueService(project);
        }
    }

    private OkHttpClient initializeHttpClient() {
        OkHttpClient client;
        try {
            // Attempt to get the client from the already initialized issueService
            client = this.issueService.httpClient(); // This can throw IOException
            logger.info("Successfully initialized HTTP client from IssueService: {}", this.issueService.getClass().getSimpleName());
        } catch (IOException e) {
            logger.error("Failed to initialize authenticated client from IssueService ({}) for GitIssuesTab, falling back to unauthenticated client. Error: {}",
                         this.issueService.getClass().getSimpleName(), e.getMessage(), e);
            client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build();
            // Avoid calling chrome.toolErrorRaw if chrome might not be fully initialized or if on a background thread.
            // The caller or a more appropriate UI update mechanism should handle user notification if needed.
            // For now, logging is sufficient here.
            // Consider if a generic "Issue provider http client setup failed" message is needed for the user via chrome.toolErrorRaw if appropriate.
        }
        return client;
    }

    private void disableIssueActionsAndClearDetails() {
        copyDescriptionAction.setEnabled(false);
        openInBrowserAction.setEnabled(false);
        captureAction.setEnabled(false);
        issueBodyTextPane.setContentType("text/html");
        issueBodyTextPane.setText("");
    }

    private Future<?> loadAndRenderIssueBodyFromHeader(IssueHeader header) {
        assert SwingUtilities.isEventDispatchThread();

        issueBodyTextPane.setContentType("text/html");
        issueBodyTextPane.setText("<html><body><p><i>Loading description for " + header.id() + "...</i></p></body></html>");

        var future = contextManager.submitBackgroundTask("Fetching/Rendering Issue Details for " + header.id(), () -> {
            try {
                IssueDetails details = issueService.loadDetails(header.id());
                String rawBody = details.markdownBody();

                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }

                if (rawBody == null || rawBody.isBlank()) {
                    SwingUtilities.invokeLater(() -> {
                        issueBodyTextPane.setContentType("text/html");
                        issueBodyTextPane.setText("<html><body><p>No description provided.</p></body></html>");
                    });
                    return null;
                }

                if (this.issueService instanceof JiraIssueService) {
                    // For Jira, rawBody is HTML.
                    SwingUtilities.invokeLater(() -> {
                        issueBodyTextPane.setContentType("text/html");
                        issueBodyTextPane.setText(rawBody);
                        issueBodyTextPane.setCaretPosition(0); // Scroll to top
                    });
                } else {
                    // For GitHub or other Markdown-based services, render Markdown to HTML.
                    String htmlBody = this.gfmRenderer.render(rawBody);
                    SwingUtilities.invokeLater(() -> {
                        issueBodyTextPane.setContentType("text/html");
                        issueBodyTextPane.setText(htmlBody);
                        issueBodyTextPane.setCaretPosition(0); // Scroll to top
                    });
                }

            } catch (Exception e) {
                if (!wasCancellation(e)) {
                    logger.error("Failed to load/render details for issue {}: {}", header.id(), e.getMessage(), e);
                    SwingUtilities.invokeLater(() -> {
                        issueBodyTextPane.setContentType("text/plain");
                        issueBodyTextPane.setText("Failed to load/render description for " + header.id() + ":\n" + e.getMessage());
                        issueBodyTextPane.setCaretPosition(0);
                    });
                }
            }
            return null;
        });
        trackCancellableFuture(future);
        return future;
    }

    private static boolean wasCancellation(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedException || t instanceof InterruptedIOException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Fetches open GitHub issues and populates the issue table.
     */
    private void updateIssueList() {
        if (currentSearchFuture != null && !currentSearchFuture.isDone()) {
            currentSearchFuture.cancel(true);
        }
        searchBox.setLoading(true, "Searching issues");
        currentSearchFuture = contextManager.submitBackgroundTask("Fetching GitHub Issues", () -> {
            List<IssueHeader> fetchedIssueHeaders;
            try {
                // Read filter values on EDT or before submitting task. searchBox can be null during early init.
                final String currentSearchQuery = (searchBox != null) ? searchBox.getText().strip() : "";
                final String queryForApi = currentSearchQuery.isBlank() ? null : currentSearchQuery;

                final String statusVal = getBaseFilterValue(statusFilter.getSelected());
                final String authorVal = getBaseFilterValue(authorFilter.getSelected()); // For GitHub server-side search
                final String labelVal = getBaseFilterValue(labelFilter.getSelected());   // For GitHub server-side search
                final String assigneeVal = getBaseFilterValue(assigneeFilter.getSelected());// For GitHub server-side search


                io.github.jbellis.brokk.issues.FilterOptions apiFilterOptions;
                if (this.issueService instanceof JiraIssueService) {
                    String resolutionVal = (resolutionFilter != null) ? getBaseFilterValue(resolutionFilter.getSelected()) : "Unresolved";
                    // For Jira, author/label/assignee are client-filtered. Query is passed for server-side text search.
                    apiFilterOptions = new io.github.jbellis.brokk.issues.JiraFilterOptions(statusVal, resolutionVal, null, null, null, queryForApi);
                    logger.debug("Jira API filters: Status='{}', Resolution='{}', Query='{}'", statusVal, resolutionVal, queryForApi);
                } else { // GitHub or default
                    // For GitHub, all filters including query are passed for server-side search if query is present.
                    // If query is null, service handles client-side filtering for author/label/assignee.
                    apiFilterOptions = new io.github.jbellis.brokk.issues.GitHubFilterOptions(statusVal, authorVal, labelVal, assigneeVal, queryForApi);
                    logger.debug("GitHub API filters: Status='{}', Author='{}', Label='{}', Assignee='{}', Query='{}'",
                                 statusVal, authorVal, labelVal, assigneeVal, queryForApi);
                }

                fetchedIssueHeaders = this.issueService.listIssues(apiFilterOptions);
                logger.debug("Fetched {} issue headers via IssueService.", fetchedIssueHeaders.size());
            } catch (Exception ex) {
                if (wasCancellation(ex)) {
                    // Ensure loading indicator is turned off, but don't show an error row or log as ERROR.
                    SwingUtilities.invokeLater(() -> searchBox.setLoading(false, ""));
                } else {
                    logger.error("Failed to fetch issues via IssueService", ex);
                    SwingUtilities.invokeLater(() -> {
                        allIssuesFromApi.clear();
                        displayedIssues.clear();
                        issueTableModel.setRowCount(0);
                        issueTableModel.addRow(new Object[]{
                                "", "Error fetching issues: " + ex.getMessage(), "", "", "", "", ""
                        });
                        disableIssueActionsAndClearDetails();
                        searchBox.setLoading(false, ""); // Stop loading on error
                    });
                }
                return null;
            }

            if (Thread.currentThread().isInterrupted()) {
                // If interrupted after successful fetch but before processing, ensure loading is stopped.
                SwingUtilities.invokeLater(() -> searchBox.setLoading(false, ""));
                return null;
            }
            // Perform filtering and display processing in the background
            processAndDisplayWorker(fetchedIssueHeaders, true);
            return null;
        });
        trackCancellableFuture(currentSearchFuture);
    }

    private void triggerClientSideFilterUpdate() {
        // This method is called when author, label, or assignee filters change.
        // It re-filters the existing 'allIssuesFromApi' list.
        if (allIssuesFromApi == null || (allIssuesFromApi.isEmpty() && issueTableModel.getRowCount() > 0 && issueTableModel.getValueAt(0, ISSUE_COL_TITLE).toString().startsWith("Error fetching issues"))) {
            logger.debug("Skipping client-side filter update: allIssuesFromApi is not ready or an error is displayed.");
            return;
        }
        searchBox.setLoading(true, "Filtering issues");
        final List<IssueHeader> currentIssuesToFilter = new ArrayList<>(allIssuesFromApi); // Use a snapshot

        contextManager.submitBackgroundTask("Applying Client-Side Filters", () -> {
            logger.debug("Client-side filter update triggered. Processing {} issues from current API list.", currentIssuesToFilter.size());
            processAndDisplayWorker(currentIssuesToFilter, false); // 'false' means don't update allIssuesFromApi, just displayedIssues
            return null;
        });
    }


    private void processAndDisplayWorker(List<IssueHeader> sourceList, boolean isFullUpdate) {
        if (Thread.currentThread().isInterrupted()) {
            // Ensure searchBox loading state is reset correctly on the EDT.
            SwingUtilities.invokeLater(() -> searchBox.setLoading(false, ""));
            return;
        }
        // This method runs on a background thread.
        logger.debug("processAndDisplayWorker: Starting. Source list size: {}. isFullUpdate: {}", sourceList.size(), isFullUpdate);

        // Read filter values. These are assumed to be safe to read from a background thread
        // as FilterBox.getSelected() should be a simple getter.
        String selectedAuthorActual = getBaseFilterValue(authorFilter.getSelected());
        String selectedLabelActual = getBaseFilterValue(labelFilter.getSelected());
        String selectedAssigneeActual = getBaseFilterValue(assigneeFilter.getSelected());
        logger.debug("processAndDisplayWorker: Filters - Author: '{}', Label: '{}', Assignee: '{}'",
                     selectedAuthorActual, selectedLabelActual, selectedAssigneeActual);

        List<IssueHeader> filteredIssues = new ArrayList<>();
        if (sourceList != null) { // Guard against null sourceList
            for (var header : sourceList) {
                boolean matches = true;
                if (selectedAuthorActual != null && !selectedAuthorActual.equals(header.author())) {
                    matches = false;
                }
                if (matches && selectedLabelActual != null) {
                    if (header.labels() == null || header.labels().stream().noneMatch(l -> selectedLabelActual.equals(l))) {
                        matches = false;
                    }
                }
                if (matches && selectedAssigneeActual != null) {
                    if (header.assignees() == null || header.assignees().stream().noneMatch(a -> selectedAssigneeActual.equals(a))) {
                        matches = false;
                    }
                }
                if (matches) {
                    filteredIssues.add(header);
                }
            }
        }
        logger.debug("processAndDisplayWorker: After filtering, {} issues remain.", filteredIssues.size());

        // Sort issues by update date, newest first
        filteredIssues.sort(Comparator.comparing(IssueHeader::updated, Comparator.nullsLast(Comparator.reverseOrder())));
        logger.debug("processAndDisplayWorker: Sorted the {} filtered issues.", filteredIssues.size());

        // Data for EDT update
        final List<IssueHeader> finalSourceListForApiField = (isFullUpdate && sourceList != null) ? new ArrayList<>(sourceList) : null;
        final List<IssueHeader> finalFilteredIssuesForDisplay = filteredIssues; // Already a new list

        SwingUtilities.invokeLater(() -> {
            // This part runs on the EDT
            logger.debug("processAndDisplayWorker (EDT): Starting UI updates.");
            if (isFullUpdate && finalSourceListForApiField != null) {
                allIssuesFromApi = finalSourceListForApiField;
                logger.debug("processAndDisplayWorker (EDT): Updated allIssuesFromApi with {} issues.", allIssuesFromApi.size());
                // FilterBoxes will lazily re-generate options using the new allIssuesFromApi
                // when the user interacts with them.
            }
            displayedIssues = finalFilteredIssuesForDisplay;
            logger.debug("processAndDisplayWorker (EDT): Set displayedIssues with {} issues.", displayedIssues.size());

            // Update table model
            issueTableModel.setRowCount(0);
            var today = LocalDate.now(java.time.ZoneId.systemDefault());
            if (displayedIssues.isEmpty()) {
                issueTableModel.addRow(new Object[]{"", "No matching issues found", "", "", "", "", ""});
                disableIssueActions();
            } else {
                for (var header : displayedIssues) {
                    String formattedUpdated = (header.updated() != null) ? gitPanel.formatCommitDate(header.updated(), today) : "";
                    String labelsStr = (header.labels() != null) ? String.join(", ", header.labels()) : "";
                    String assigneesStr = (header.assignees() != null) ? String.join(", ", header.assignees()) : "";

                    issueTableModel.addRow(new Object[]{
                            header.id(), header.title(), header.author(), formattedUpdated,
                            labelsStr, assigneesStr, header.status()
                    });
                }
            }
            logger.debug("processAndDisplayWorker (EDT): Table model updated.");

            // Manage button states based on selection
            if (issueTable.getSelectedRow() == -1) {
                disableIssueActions();
            } else {
                // Trigger selection listener to update button states correctly for the (potentially new) selection
                // This ensures that if the selection is still valid, actions are enabled.
                issueTable.getSelectionModel().setValueIsAdjusting(true);
                issueTable.getSelectionModel().setValueIsAdjusting(false);
            }
            searchBox.setLoading(false, ""); // Stop loading after UI updates
            logger.debug("processAndDisplayWorker (EDT): UI updates complete.");
        });
    }

    private List<String> generateFilterOptionsFromIssues(List<IssueHeader> issueHeaders, String filterType) {
        if (issueHeaders == null || issueHeaders.isEmpty()) { // Added null check
            return List.of();
        }

        Map<String, Integer> counts = new HashMap<>();

        switch (filterType) {
            case "author" -> {
                for (var header : issueHeaders) {
                    if (header.author() != null && !header.author().isBlank() && !"N/A".equalsIgnoreCase(header.author())) {
                        counts.merge(header.author(), 1, Integer::sum);
                    }
                }
            }
            case "label" -> {
                for (var header : issueHeaders) {
                    if (header.labels() != null) { // Added null check
                        for (String label : header.labels()) {
                            if (!label.isBlank()) {
                                counts.merge(label, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
            case "assignee" -> {
                for (var header : issueHeaders) {
                    if (header.assignees() != null) { // Added null check
                        for (String assignee : header.assignees()) {
                            if (assignee != null && !assignee.isBlank() && !"N/A".equalsIgnoreCase(assignee)) {
                                counts.merge(assignee, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
        }
        return generateFilterOptionsList(counts);
    }

    private List<String> generateFilterOptionsList(Map<String, Integer> counts) {
        List<String> options = new ArrayList<>();
        List<String> sortedItems = new ArrayList<>(counts.keySet());
        Collections.sort(sortedItems, String.CASE_INSENSITIVE_ORDER);

        for (String item : sortedItems) {
            options.add(String.format("%s (%d)", item, counts.get(item)));
        }
        return options;
    }

    @Nullable
    private String getBaseFilterValue(@Nullable String displayOptionWithCount) {
        if (displayOptionWithCount == null) {
            return null; // This is the "All" case (FilterBox name shown)
        }
        // For dynamic items like "John Doe (5)"
        int parenthesisIndex = displayOptionWithCount.lastIndexOf(" (");
        if (parenthesisIndex != -1) {
            // Ensure the part in parenthesis is a number to avoid stripping part of a name
            String countPart = displayOptionWithCount.substring(parenthesisIndex + 2, displayOptionWithCount.length() - 1);
            if (countPart.matches("\\d+")) {
                return displayOptionWithCount.substring(0, parenthesisIndex);
            }
        }
        return displayOptionWithCount; // For simple string options like "Open", "Closed", or names without counts
    }

    private void disableIssueActions() {
        if (copyDescriptionAction != null) {
            copyDescriptionAction.setEnabled(false);
            openInBrowserAction.setEnabled(false);
            captureAction.setEnabled(false);
        }
    }

    private void captureSelectedIssue() {
        int selectedRow = issueTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedIssues.size()) {
            return;
        }
        IssueHeader header = displayedIssues.get(selectedRow);
        captureIssueHeader(header);
    }

    private void captureIssueHeader(IssueHeader header) {
        var future = contextManager.submitContextTask("Capturing Issue " + header.id(), () -> {
            try {
                IssueDetails details = issueService.loadDetails(header.id());
                if (details == null) {
                    logger.error("Failed to load details for issue {}", header.id());
                    chrome.toolError("Failed to load details for issue " + header.id());
                    return;
                }

                List<ChatMessage> issueTextMessages = buildIssueTextContentFromDetails(details);
                ContextFragment.TaskFragment issueTextFragment = createIssueTextFragmentFromDetails(details, issueTextMessages);
                contextManager.addVirtualFragment(issueTextFragment);

                List<ChatMessage> commentChatMessages = buildChatMessagesFromDtoComments(details.comments());
                if (!commentChatMessages.isEmpty()) {
                    contextManager.addVirtualFragment(createCommentsFragmentFromDetails(details, commentChatMessages));
                }

                int capturedImageCount = processAndCaptureImagesFromDetails(details);

                String commentMessage = details.comments().isEmpty() ? "" : " with " + details.comments().size() + " comment(s)";
                String imageMessage = capturedImageCount == 0 ? "" : " and " + capturedImageCount + " image(s)";
                chrome.systemOutput("Issue " + header.id() + " captured to workspace" + commentMessage + imageMessage + ".");

            } catch (Exception e) { // General catch for robustness
                logger.error("Failed to capture all details for issue {}: {}", header.id(), e.getMessage(), e);
                chrome.toolError("Failed to capture all details for issue " + header.id() + ": " + e.getMessage());
            }
        });
        trackCancellableFuture(future);
    }

    private List<ChatMessage> buildIssueTextContentFromDetails(IssueDetails details) {
        IssueHeader header = details.header();
        String bodyForCapture = details.markdownBody(); // This is HTML from Jira, Markdown from GitHub
        if (this.issueService instanceof JiraIssueService && bodyForCapture != null) {
            bodyForCapture = HtmlUtil.convertToMarkdown(bodyForCapture);
        }
        bodyForCapture = (bodyForCapture == null || bodyForCapture.isBlank()) ? "*No description provided.*" : bodyForCapture;
        String content = String.format("""
                                       # Issue #%s: %s
                                       
                                       **Author:** %s
                                       **Status:** %s
                                       **URL:** %s
                                       **Labels:** %s
                                       **Assignees:** %s
                                       
                                       ---
                                       
                                       %s
                                       """.stripIndent(),
                                       header.id(),
                                       header.title(),
                                       header.author(),
                                       header.status(),
                                       header.htmlUrl(),
                                       header.labels().isEmpty() ? "None" : String.join(", ", header.labels()),
                                       header.assignees().isEmpty() ? "None" : String.join(", ", header.assignees()),
                                       bodyForCapture
        );
        return List.of(new CustomMessage(Map.of("text", content)));
    }

    private ContextFragment.TaskFragment createIssueTextFragmentFromDetails(IssueDetails details, List<ChatMessage> messages) {
        IssueHeader header = details.header();
        String description = String.format("Issue %s: %s", header.id(), header.title());
        return new ContextFragment.TaskFragment(
                this.contextManager,
                messages,
                description,
                false // some issues contain HTML
        );
    }

    private List<ChatMessage> buildChatMessagesFromDtoComments(List<Comment> dtoComments) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        if (dtoComments == null) return chatMessages;

        for (io.github.jbellis.brokk.issues.Comment comment : dtoComments) {
            String author = (comment.author() == null || comment.author().isBlank()) ? "unknown" : comment.author();
            String originalCommentBody = comment.markdownBody(); // HTML from Jira, Markdown from GitHub
            String commentBodyForCapture = originalCommentBody;
            if (this.issueService instanceof JiraIssueService && originalCommentBody != null) {
                commentBodyForCapture = HtmlUtil.convertToMarkdown(originalCommentBody);
            }

            if (commentBodyForCapture != null && !commentBodyForCapture.isBlank()) {
                chatMessages.add(UserMessage.from(author, commentBodyForCapture));
            }
        }
        return chatMessages;
    }

    private ContextFragment.TaskFragment createCommentsFragmentFromDetails(IssueDetails details, List<ChatMessage> commentMessages) {
        IssueHeader header = details.header();
        String description = String.format("Issue %s: Comments", header.id());
        return new ContextFragment.TaskFragment(
                this.contextManager,
                commentMessages,
                description,
                false // some comments contain HTML
        );
    }

    private int processAndCaptureImagesFromDetails(IssueDetails details) {
        IssueHeader header = details.header();
        List<URI> attachmentUris = details.attachmentUrls(); // Already extracted by IssueService
        if (attachmentUris == null || attachmentUris.isEmpty()) {
            return 0;
        }

        int capturedImageCount = 0;
        OkHttpClient clientToUse;
        try {
            clientToUse = issueService.httpClient(); // Use authenticated client from service
        } catch (IOException e) {
            logger.error("Failed to get authenticated client from IssueService for image download, falling back. Error: {}", e.getMessage());
            // Fallback to the one initialized in GitIssuesTab constructor (might be unauthenticated)
            clientToUse = this.httpClient; // Assumes this.httpClient is still available and initialized
            chrome.systemOutput("Could not get authenticated client for image download. Private images might not load. Error: " + e.getMessage());
        }


        for (URI imageUri : attachmentUris) {
            try {
                if (ImageUtil.isImageUri(imageUri, clientToUse)) {
                    chrome.systemOutput("Downloading image: " + imageUri.toString());
                    java.awt.Image image = ImageUtil.downloadImage(imageUri, clientToUse);
                    if (image != null) {
                        String description = String.format("Issue %s: Image", header.id());
                        contextManager.addPastedImageFragment(image, description);
                        capturedImageCount++;
                    } else {
                        logger.warn("Failed to download image identified by ImageUtil: {}", imageUri.toString());
                        chrome.toolError("Failed to download image: " + imageUri.toString());
                    }
                }
            } catch (Exception e) {
                logger.error("Unexpected error processing image {}: {}", imageUri.toString(), e.getMessage(), e);
                chrome.toolError("Error processing image " + imageUri.toString() + ": " + e.getMessage());
            }
        }
        return capturedImageCount;
    }

    private void copySelectedIssueDescription() {
        int selectedRow = issueTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedIssues.size()) {
            return;
        }
        IssueHeader header = displayedIssues.get(selectedRow);

        var future = contextManager.submitBackgroundTask("Fetching issue details for copy: " + header.id(), () -> {
            try {
                IssueDetails details = issueService.loadDetails(header.id());
                String body = details.markdownBody();
                if (body != null && !body.isBlank()) {
                    StringSelection stringSelection = new StringSelection(body);
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, null);
                    chrome.systemOutput("Issue " + header.id() + " description copied to clipboard.");
                } else {
                    chrome.systemOutput("Issue " + header.id() + " has no description to copy.");
                }
            } catch (IOException e) {
                logger.error("Failed to load issue details for copy: {}", header.id(), e);
                chrome.toolError("Failed to load issue " + header.id() + " details for copy: " + e.getMessage());
            }
            return null;
        });
        trackCancellableFuture(future);
    }

    private void openSelectedIssueInBrowser() {
        int selectedRow = issueTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedIssues.size()) {
            return;
        }
        IssueHeader header = displayedIssues.get(selectedRow);
        URI url = header.htmlUrl();
        if (url != null) {
            Environment.openInBrowser(url.toString(), SwingUtilities.getWindowAncestor(chrome.getFrame()));
        } else {
            var msg = "Cannot open issue %s in browser: URL is missing".formatted(header.id());
            logger.warn(msg);
            chrome.toolError(msg, "Error");
        }
    }
}
