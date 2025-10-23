package io.github.jbellis.brokk.gui.git;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.*;
import io.github.jbellis.brokk.ExceptionReporter;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.gui.*;
import io.github.jbellis.brokk.gui.components.GitHubTokenMissingPanel;
import io.github.jbellis.brokk.gui.components.IssueHeaderCellRenderer;
import io.github.jbellis.brokk.gui.components.LoadingTextBox;
import io.github.jbellis.brokk.gui.components.MaterialButton;
import io.github.jbellis.brokk.gui.components.WrapLayout;
import io.github.jbellis.brokk.gui.util.GitUiUtil;
import io.github.jbellis.brokk.gui.util.Icons;
import io.github.jbellis.brokk.issues.*;
import io.github.jbellis.brokk.util.Environment;
import io.github.jbellis.brokk.util.HtmlUtil;
import io.github.jbellis.brokk.util.ImageUtil;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class GitIssuesTab extends JPanel implements SettingsChangeListener {
    private static final Logger logger = LogManager.getLogger(GitIssuesTab.class);

    private final Chrome chrome;
    private final ContextManager contextManager;

    private JTable issueTable;
    private DefaultTableModel issueTableModel;
    private JTextPane issueBodyTextPane;
    /** Panel that shows the selected issue’s description; hidden until needed. */
    private final JPanel issueDetailPanel;

    private MaterialButton copyIssueDescriptionButton;
    private MaterialButton openInBrowserButton;
    private MaterialButton captureButton;

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

    private volatile @Nullable Future<?> currentSearchFuture;
    private final GfmRenderer gfmRenderer;
    private final OkHttpClient httpClient;
    private final IssueService issueService;
    private final Set<Future<?>> activeFutures = ConcurrentHashMap.newKeySet();

    public GitIssuesTab(Chrome chrome, ContextManager contextManager, IssueService issueService) {
        super(new BorderLayout());
        this.chrome = chrome;
        this.contextManager = contextManager;
        this.issueService = issueService;
        this.gfmRenderer = new GfmRenderer();
        this.httpClient = initializeHttpClient();

        // Initialize nullable fields to avoid NullAway errors
        this.pendingHeaderForDescription = null;
        this.currentDescriptionFuture = null;

        // Load dynamic statuses after issueService and statusFilter are initialized
        var future = contextManager.submitBackgroundTask("Load Available Issue Statuses", () -> {
            List<String> fetchedStatuses = null;
            try {
                // Ensure issueService is available
                fetchedStatuses = this.issueService.listAvailableStatuses();
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
            });
            return null;
        });
        trackCancellableFuture(future);

        // Issue Description panel will be added below the issues table; no split pane is needed

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
        searchBox
                .asTextField()
                .setToolTipText("Search issues (Ctrl+F to focus)"); // Set tooltip on the inner JTextField
        searchPanel.add(searchBox, BorderLayout.CENTER);

        // ── Refresh button ──────────────────────────────────────────────────────
        // Use a clockwise-arrow glyph directly; the old Tree icon looked like a down-arrow
        MaterialButton refreshButton = new MaterialButton(); // Unicode clockwise arrow
        final Icon refreshIcon = Icons.REFRESH;
        refreshButton.setIcon(refreshIcon);
        refreshButton.setText("");
        refreshButton.setMargin(new Insets(2, 2, 2, 2)); // small padding
        // Slightly enlarge the glyph so it is more legible than default-size text
        refreshButton.setFont(
                refreshButton.getFont().deriveFont(refreshButton.getFont().getSize() * 1.25f));
        refreshButton.setToolTipText("Refresh");
        refreshButton.addActionListener(e -> updateIssueList());
        searchPanel.add(refreshButton, BorderLayout.EAST);

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
        // Show filters horizontally and wrap; WrapLayout grows vertically as needed
        filtersContainer.setLayout(new WrapLayout(FlowLayout.LEFT, Constants.H_GAP, Constants.V_GAP));
        // add bottom padding so the filters are not obscured when the horizontal
        // scrollbar appears
        filtersContainer.setBorder(
                BorderFactory.createEmptyBorder(0, Constants.H_PAD, Constants.V_GAP, Constants.H_PAD));

        JLabel filterLabel = new JLabel("Filter:");
        filterLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        filtersContainer.add(filterLabel);
        filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP)); // Space after label

        if (this.issueService instanceof JiraIssueService) {
            resolutionFilter =
                    new FilterBox(this.chrome, "Resolution", () -> List.of("Resolved", "Unresolved"), "Unresolved");
            resolutionFilter.setToolTipText("Filter by Jira issue resolution");
            resolutionFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
            // API call needed when resolution changes
            resolutionFilter.addPropertyChangeListener("value", e -> updateIssueList());
            filtersContainer.add(resolutionFilter); // Add to filtersContainer
            filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP));

            statusFilter = new FilterBox(
                    this.chrome, "Status", () -> actualStatusFilterOptions, null); // No default for Jira status
            statusFilter.setToolTipText("Filter by Jira issue status");
        } else { // GitHub or default
            statusFilter = new FilterBox(
                    this.chrome, "Status", () -> actualStatusFilterOptions, "Open"); // Default "Open" for GitHub
            statusFilter.setToolTipText("Filter by GitHub issue status");
        }
        statusFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusFilter.addPropertyChangeListener("value", e -> {
            // Status filter change triggers a new API fetch and subsequent processing.
            updateIssueList();
        });
        filtersContainer.add(statusFilter);
        filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP));

        authorFilter =
                new FilterBox(this.chrome, "Author", () -> generateFilterOptionsFromIssues(allIssuesFromApi, "author"));
        authorFilter.setToolTipText("Filter by issue author");
        authorFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        authorFilter.addPropertyChangeListener("value", e -> triggerClientSideFilterUpdate());
        filtersContainer.add(authorFilter);
        filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP));

        labelFilter =
                new FilterBox(this.chrome, "Label", () -> generateFilterOptionsFromIssues(allIssuesFromApi, "label"));
        labelFilter.setToolTipText("Filter by issue label");
        labelFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        labelFilter.addPropertyChangeListener("value", e -> triggerClientSideFilterUpdate());
        filtersContainer.add(labelFilter);
        filtersContainer.add(Box.createVerticalStrut(Constants.V_GAP));

        assigneeFilter = new FilterBox(
                this.chrome, "Assignee", () -> generateFilterOptionsFromIssues(allIssuesFromApi, "assignee"));
        assigneeFilter.setToolTipText("Filter by issue assignee");
        assigneeFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        assigneeFilter.addPropertyChangeListener("value", e -> triggerClientSideFilterUpdate());
        filtersContainer.add(assigneeFilter);

        // Put the horizontal filter bar in a scroll pane so it can overflow cleanly
        // Add bottom padding and attach the container directly; FlowLayout wraps rows automatically
        filtersContainer.setBorder(BorderFactory.createEmptyBorder(0, 0, Constants.V_GAP, 0));
        verticalFilterPanel.add(filtersContainer, BorderLayout.CENTER);

        // Panel for Issue Table (CENTER) and Issue Buttons (SOUTH)
        JPanel issueTableAndButtonsPanel = new JPanel(new BorderLayout(0, Constants.V_GAP)); // Added V_GAP
        // Create a container that stacks the Search bar and the Filters one above
        // the other so they live in a single vertical column.
        JPanel searchAndFiltersPanel = new JPanel();
        searchAndFiltersPanel.setLayout(new BoxLayout(searchAndFiltersPanel, BoxLayout.Y_AXIS));
        searchAndFiltersPanel.add(searchPanel);
        searchAndFiltersPanel.add(Box.createVerticalStrut(Constants.V_GAP)); // small gap
        searchAndFiltersPanel.add(verticalFilterPanel); // contains all filter boxes

        // Position this combined panel at the top of the issues area
        issueTableAndButtonsPanel.add(searchAndFiltersPanel, BorderLayout.NORTH);

        // ── Issue JTable ─────────────────────────────────────────────────────
        issueTableModel = new DefaultTableModel(new Object[] {"ID", "Title", "Author", "Updated"}, 0) {
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
        issueTable.setTableHeader(null); // hide headers
        issueTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        issueTable.setRowHeight(48); // give breathing room
        issueTable.setIntercellSpacing(new Dimension(0, Constants.V_GAP));

        // Hide helper columns (ID, Author, Updated)
        int[] helperCols = {0, 2, 3};
        for (int c : helperCols) {
            issueTable.getColumnModel().getColumn(c).setMinWidth(0);
            issueTable.getColumnModel().getColumn(c).setMaxWidth(0);
            issueTable.getColumnModel().getColumn(c).setPreferredWidth(0);
        }

        // Title renderer
        issueTable.getColumnModel().getColumn(1).setCellRenderer(new IssueHeaderCellRenderer());

        ToolTipManager.sharedInstance().registerComponent(issueTable);

        // Issue Description panel (initially hidden – shown when a row is selected)
        this.issueDetailPanel = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getPreferredSize() {
                Dimension pref = super.getPreferredSize();
                Container parent = getParent();
                if (parent != null) {
                    int maxHeight = parent.getHeight() / 3; // ≈ 33 % of parent
                    if (maxHeight > 0) {
                        return new Dimension(pref.width, Math.min(pref.height, maxHeight));
                    }
                }
                return pref;
            }

            @Override
            public Dimension getMaximumSize() {
                Dimension max = super.getMaximumSize();
                Container parent = getParent();
                if (parent != null) {
                    int maxHeight = parent.getHeight() / 3;
                    if (maxHeight > 0) {
                        return new Dimension(max.width, maxHeight);
                    }
                }
                return max;
            }
        };
        issueDetailPanel.setBorder(BorderFactory.createTitledBorder("Issue Description"));

        JScrollPane issueTableScrollPane = new JScrollPane(issueTable);

        // vertical split: issues table (top)  |  description panel (bottom)
        final JSplitPane tableDetailsSplitPane =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, issueTableScrollPane, issueDetailPanel);
        tableDetailsSplitPane.setResizeWeight(1.0); // keep table large until description is shown
        tableDetailsSplitPane.setDividerSize(3);

        issueTableAndButtonsPanel.add(tableDetailsSplitPane, BorderLayout.CENTER);

        // Create shared actions
        copyDescriptionAction = new AbstractAction("Copy Description") {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelectedIssueDescription();
            }
        };
        copyDescriptionAction.putValue(
                Action.SHORT_DESCRIPTION, "Copy the selected issue's description to the clipboard");
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

        // No separate bottom-button panel needed after redesign

        copyIssueDescriptionButton = new MaterialButton();
        copyIssueDescriptionButton.setAction(copyDescriptionAction);
        openInBrowserButton = new MaterialButton();
        openInBrowserButton.setAction(openInBrowserAction);
        captureButton = new MaterialButton();
        captureButton.setAction(captureAction);

        // ── compact icon-style buttons ───────────────────────────────────────
        final Icon copyIcon = Icons.CONTENT_COPY;
        copyIssueDescriptionButton.setIcon(copyIcon);
        copyIssueDescriptionButton.setText("");
        copyIssueDescriptionButton.setMargin(new Insets(2, 2, 2, 2));

        final Icon browserIcon = Icons.OPEN_IN_BROWSER;
        openInBrowserButton.setIcon(browserIcon);
        openInBrowserButton.setText("");
        openInBrowserButton.setMargin(new Insets(2, 2, 2, 2));

        final Icon captureIcon = Icons.CONTENT_CAPTURE;
        captureButton.setIcon(captureIcon);
        captureButton.setText("");
        captureButton.setMargin(new Insets(2, 2, 2, 2));

        filtersAndTablePanel.add(issueTableAndButtonsPanel, BorderLayout.CENTER);
        mainIssueAreaPanel.add(filtersAndTablePanel, BorderLayout.CENTER);

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

        // ── Action buttons bar inside the description panel ────────────────────
        JPanel issueActionPanel = new JPanel();
        issueActionPanel.setLayout(new BoxLayout(issueActionPanel, BoxLayout.X_AXIS));
        issueActionPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, Constants.V_GAP, 0));
        // Capture on the left, Copy/Open on the right
        issueActionPanel.add(captureButton);
        issueActionPanel.add(Box.createHorizontalGlue());
        issueActionPanel.add(copyIssueDescriptionButton);
        issueActionPanel.add(Box.createHorizontalStrut(Constants.H_GAP));
        issueActionPanel.add(openInBrowserButton);

        JScrollPane actionScrollPane = new JScrollPane(
                issueActionPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        actionScrollPane.setBorder(BorderFactory.createEmptyBorder());

        // place the bar at the bottom of the description area
        issueDetailPanel.add(actionScrollPane, BorderLayout.SOUTH);

        // Add the Issue-Description panel under the table and hide it until a row is chosen
        /* issueDetailPanel is now managed by the JSplitPane; no direct add() here */
        issueDetailPanel.setVisible(false);

        add(mainIssueAreaPanel, BorderLayout.CENTER);

        // Initialize context menu and items
        issueContextMenu = new JPopupMenu();
        chrome.getTheme().registerPopupMenu(issueContextMenu);

        issueContextMenu.add(new JMenuItem(copyDescriptionAction));
        issueContextMenu.add(new JMenuItem(openInBrowserAction));
        issueContextMenu.add(new JMenuItem(captureAction));

        // Add mouse listener for context menu on issue table
        issueTable.addMouseListener(new MouseAdapter() {
            private void showPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = issueTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        if (!issueTable.isRowSelected(row)) {
                            issueTable.setRowSelectionInterval(row, row);
                        }
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
            if (!e.getValueIsAdjusting()) {
                int viewRow = issueTable.getSelectedRow();
                int index = viewRow == -1 ? -1 : issueTable.convertRowIndexToModel(viewRow);
                if (index == -1 || index >= displayedIssues.size()) {
                    disableIssueActionsAndClearDetails();
                    pendingHeaderForDescription = null;
                    tableDetailsSplitPane.setDividerLocation(1.0); // collapse details
                    if (descriptionDebounceTimer.isRunning()) {
                        descriptionDebounceTimer.stop();
                    }
                    return;
                }
                IssueHeader selectedHeader = displayedIssues.get(index);

                // Enable actions immediately for responsiveness
                copyDescriptionAction.setEnabled(true);
                openInBrowserAction.setEnabled(true);
                captureAction.setEnabled(true);
                issueDetailPanel.setVisible(true);
                tableDetailsSplitPane.setDividerLocation(0.75); // reveal details (~25 % height)

                // Debounce loading of the issue body
                pendingHeaderForDescription = selectedHeader;
                descriptionDebounceTimer.restart();

                // No selection or invalid row handled above; redundant else removed
            }
        });

        MainProject.addSettingsChangeListener(this);
        updateIssueList(); // async
    }

    @Override
    public void issueProviderChanged() {
        SwingUtilities.invokeLater(() -> {
            logger.debug(
                    "Issue provider changed notification received. Requesting GitPanel to recreate this issues tab.");
            cancelActiveFutures();
            // Ask GitPanel to recreate this tab.
            // GitPanel is final and assigned in constructor, so it won't be null here.
            chrome.recreateIssuesPanel();
        });
    }

    private void cancelActiveFutures() {
        if (searchDebounceTimer.isRunning()) {
            searchDebounceTimer.stop();
        }
        if (descriptionDebounceTimer.isRunning()) {
            descriptionDebounceTimer.stop();
        }
        pendingHeaderForDescription = null;

        List<Future<?>> futuresToCancel = new ArrayList<>(activeFutures);
        // currentSearchFuture and currentDescriptionFuture are added to activeFutures
        // when they are created, so they will be included in the futuresToCancel list here.

        logger.debug("Attempting to cancel {} active issue-related futures.", futuresToCancel.size());
        for (Future<?> f : futuresToCancel) {
            if (!f.isDone()) {
                f.cancel(true);
                logger.trace("Requested cancellation for active future: {}", f.toString());
            }
        }
        activeFutures.clear(); // Clear the set after attempting cancellation
    }

    /** Tracks a Future that might need to be cancelled if settings change (e.g. GitHub token, issue provider). */
    private void trackCancellableFuture(Future<?> future) {
        activeFutures.removeIf(Future::isDone);
        activeFutures.add(future);
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        MainProject.removeSettingsChangeListener(this);
        searchDebounceTimer.stop();
        descriptionDebounceTimer.stop();
    }

    @Override
    public void gitHubTokenChanged() {
        SwingUtilities.invokeLater(() -> {
            logger.debug("GitHub token changed. Initiating cancellation of active issue tasks and scheduling refresh.");

            if (searchDebounceTimer.isRunning()) {
                searchDebounceTimer.stop();
            }
            if (descriptionDebounceTimer.isRunning()) {
                descriptionDebounceTimer.stop();
            }
            pendingHeaderForDescription = null;

            // This stops timers and clears activeFutures set
            cancelActiveFutures();

            updateIssueList();
        });
    }

    public GitIssuesTab(Chrome chrome, ContextManager contextManager) {
        this(chrome, contextManager, createDefaultIssueService(contextManager));
    }

    private static IssueService createDefaultIssueService(ContextManager contextManager) {
        IProject project = contextManager.getProject();
        // This line will cause a compile error until IProject.getIssuesProvider() is added. This is expected.
        IssueProviderType providerType = project.getIssuesProvider().type();
        Logger staticLogger = LogManager.getLogger(GitIssuesTab.class);

        return switch (providerType) {
            case JIRA -> {
                staticLogger.info(
                        "Using JiraIssueService for project {} (provider: JIRA)",
                        project.getRoot().getFileName());
                yield new JiraIssueService(project);
            } // Explicitly handle NONE, though it might default to GitHub or a NoOp service later
            default -> {
                staticLogger.info(
                        "Using GitHubIssueService for project {} (provider: {})",
                        project.getRoot().getFileName(),
                        providerType);
                yield new GitHubIssueService(project);
            }
        };
    }

    private OkHttpClient initializeHttpClient() {
        OkHttpClient client;
        try {
            // Attempt to get the client from the already initialized issueService
            client = this.issueService.httpClient(); // This can throw IOException
            logger.info(
                    "Successfully initialized HTTP client from IssueService: {}",
                    this.issueService.getClass().getSimpleName());
        } catch (IOException e) {
            logger.error(
                    "Failed to initialize authenticated client from IssueService ({}) for GitIssuesTab, falling back to unauthenticated client. Error: {}",
                    this.issueService.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            client = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build();
            // Avoid calling chrome.toolErrorRaw if chrome might not be fully initialized or if on a background thread.
            // The caller or a more appropriate UI update mechanism should handle user notification if needed.
            // For now, logging is sufficient here.
            // Consider if a generic "Issue provider http client setup failed" message is needed for the user via
            // chrome.toolErrorRaw if appropriate.
        }
        return client;
    }

    private void disableIssueActionsAndClearDetails() {
        copyDescriptionAction.setEnabled(false);
        openInBrowserAction.setEnabled(false);
        captureAction.setEnabled(false);
        issueBodyTextPane.setContentType("text/html");
        issueBodyTextPane.setText("");
        issueDetailPanel.setVisible(false);
    }

    private Future<?> loadAndRenderIssueBodyFromHeader(IssueHeader header) {
        assert SwingUtilities.isEventDispatchThread();

        issueBodyTextPane.setContentType("text/html");
        issueBodyTextPane.setText(
                "<html><body><p><i>Loading description for " + header.id() + "...</i></p></body></html>");

        var future = contextManager.submitBackgroundTask("Fetching/Rendering Issue Details for " + header.id(), () -> {
            try {
                IssueDetails details = issueService.loadDetails(header.id());
                String rawBody = details.markdownBody();

                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }

                if (rawBody.isBlank()) {
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
                        issueBodyTextPane.setText(
                                "Failed to load/render description for " + header.id() + ":\n" + e.getMessage());
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
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof InterruptedException || cause instanceof InterruptedIOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** Fetches open GitHub issues and populates the issue table. */
    private void updateIssueList() {
        if (currentSearchFuture != null && !currentSearchFuture.isDone()) {
            currentSearchFuture.cancel(true);
        }

        searchBox.setLoading(true, "Searching issues");
        currentSearchFuture = contextManager.submitBackgroundTask("Fetching GitHub Issues", () -> {
            List<IssueHeader> fetchedIssueHeaders;
            try {
                // Read filter values on EDT or before submitting task. searchBox can be null during early init.
                final String currentSearchQuery = searchBox.getText().strip();
                final String queryForApi = currentSearchQuery.isBlank() ? null : currentSearchQuery;

                final String statusVal = getBaseFilterValue(statusFilter.getSelected());
                final String authorVal =
                        getBaseFilterValue(authorFilter.getSelected()); // For GitHub server-side search
                final String labelVal = getBaseFilterValue(labelFilter.getSelected()); // For GitHub server-side search
                final String assigneeVal =
                        getBaseFilterValue(assigneeFilter.getSelected()); // For GitHub server-side search

                FilterOptions apiFilterOptions;
                if (this.issueService instanceof JiraIssueService) {
                    String resolutionVal = (resolutionFilter != null)
                            ? getBaseFilterValue(resolutionFilter.getSelected())
                            : "Unresolved";
                    // For Jira, author/label/assignee are client-filtered. Query is passed for server-side text search.
                    apiFilterOptions = new JiraFilterOptions(statusVal, resolutionVal, null, null, null, queryForApi);
                    logger.debug(
                            "Jira API filters: Status='{}', Resolution='{}', Query='{}'",
                            statusVal,
                            resolutionVal,
                            queryForApi);
                } else { // GitHub or default
                    // For GitHub, all filters including query are passed for server-side search if query is present.
                    // If query is null, service handles client-side filtering for author/label/assignee.
                    apiFilterOptions =
                            new GitHubFilterOptions(statusVal, authorVal, labelVal, assigneeVal, queryForApi);
                    logger.debug(
                            "GitHub API filters: Status='{}', Author='{}', Label='{}', Assignee='{}', Query='{}'",
                            statusVal,
                            authorVal,
                            labelVal,
                            assigneeVal,
                            queryForApi);
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
        if (allIssuesFromApi.isEmpty() && issueTableModel.getRowCount() == 0) {
            logger.debug("Skipping client-side filter update: allIssuesFromApi is not ready or an error is displayed.");
            return;
        }
        searchBox.setLoading(true, "Filtering issues");
        final List<IssueHeader> currentIssuesToFilter = new ArrayList<>(allIssuesFromApi); // Use a snapshot

        contextManager.submitBackgroundTask("Applying Client-Side Filters", () -> {
            logger.debug(
                    "Client-side filter update triggered. Processing {} issues from current API list.",
                    currentIssuesToFilter.size());
            processAndDisplayWorker(
                    currentIssuesToFilter, false); // 'false' means don't update allIssuesFromApi, just displayedIssues
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
        logger.debug(
                "processAndDisplayWorker: Starting. Source list size: {}. isFullUpdate: {}",
                sourceList.size(),
                isFullUpdate);

        // Read filter values. These are assumed to be safe to read from a background thread
        // as FilterBox.getSelected() should be a simple getter.
        String selectedAuthorActual = getBaseFilterValue(authorFilter.getSelected());
        String selectedLabelActual = getBaseFilterValue(labelFilter.getSelected());
        String selectedAssigneeActual = getBaseFilterValue(assigneeFilter.getSelected());
        logger.debug(
                "processAndDisplayWorker: Filters - Author: '{}', Label: '{}', Assignee: '{}'",
                selectedAuthorActual,
                selectedLabelActual,
                selectedAssigneeActual);

        List<IssueHeader> filteredIssues = new ArrayList<>();
        // Guard against null sourceList
        for (var header : sourceList) {
            boolean matches = true;
            if (selectedAuthorActual != null && !selectedAuthorActual.equals(header.author())) {
                matches = false;
            }
            if (matches && selectedLabelActual != null) {
                if (header.labels().stream().noneMatch(l -> selectedLabelActual.equals(l))) {
                    matches = false;
                }
            }
            if (matches && selectedAssigneeActual != null) {
                if (header.assignees().stream().noneMatch(a -> selectedAssigneeActual.equals(a))) {
                    matches = false;
                }
            }
            if (matches) {
                filteredIssues.add(header);
            }
        }
        logger.debug("processAndDisplayWorker: After filtering, {} issues remain.", filteredIssues.size());

        // Sort issues by update date, newest first
        filteredIssues.sort(
                Comparator.comparing(IssueHeader::updated, Comparator.nullsLast(Comparator.reverseOrder())));
        logger.debug("processAndDisplayWorker: Sorted the {} filtered issues.", filteredIssues.size());

        // Data for EDT update
        final List<IssueHeader> finalFilteredIssuesForDisplay = filteredIssues; // Already a new list

        SwingUtilities.invokeLater(() -> {
            // This part runs on the EDT
            logger.debug("processAndDisplayWorker (EDT): Starting UI updates.");
            if (isFullUpdate) {
                allIssuesFromApi = new ArrayList<>(sourceList);
                logger.debug(
                        "processAndDisplayWorker (EDT): Updated allIssuesFromApi with {} issues.",
                        allIssuesFromApi.size());
                // FilterBoxes will lazily re-generate options using the new allIssuesFromApi
                // when the user interacts with them.
            }
            displayedIssues = finalFilteredIssuesForDisplay;
            logger.debug("processAndDisplayWorker (EDT): Set displayedIssues with {} issues.", displayedIssues.size());

            // Update table model
            issueTableModel.setRowCount(0);
            if (displayedIssues.isEmpty()) {
                disableIssueActions();
            } else {
                var today = LocalDate.now(ZoneId.systemDefault());
                for (var header : displayedIssues) {
                    String updated = header.updated() == null
                            ? ""
                            : GitUiUtil.formatRelativeDate(header.updated().toInstant(), today);
                    issueTableModel.addRow(new Object[] {header.id(), header.title(), header.author(), updated});
                }
            }

            // Manage button states based on selection
            if (issueTable.getSelectedRow() == -1) {
                disableIssueActions();
            }
            searchBox.setLoading(false, ""); // Stop loading after UI updates
            logger.debug("processAndDisplayWorker (EDT): UI updates complete.");
        });
    }

    private List<String> generateFilterOptionsFromIssues(List<IssueHeader> issueHeaders, String filterType) {
        if (issueHeaders.isEmpty()) { // Added null check
            return List.of();
        }

        Map<String, Integer> counts = new HashMap<>();

        switch (filterType) {
            case "author" -> {
                for (var header : issueHeaders) {
                    if (!header.author().isBlank() && !"N/A".equalsIgnoreCase(header.author())) {
                        counts.merge(header.author(), 1, Integer::sum);
                    }
                }
            }
            case "label" -> {
                for (var header : issueHeaders) {
                    // Added null check
                    for (String label : header.labels()) {
                        if (!label.isBlank()) {
                            counts.merge(label, 1, Integer::sum);
                        }
                    }
                }
            }
            case "assignee" -> {
                for (var header : issueHeaders) {
                    // Added null check
                    for (String assignee : header.assignees()) {
                        if (!assignee.isBlank() && !"N/A".equalsIgnoreCase(assignee)) {
                            counts.merge(assignee, 1, Integer::sum);
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
            String countPart =
                    displayOptionWithCount.substring(parenthesisIndex + 2, displayOptionWithCount.length() - 1);
            if (countPart.matches("\\d+")) {
                return displayOptionWithCount.substring(0, parenthesisIndex);
            }
        }
        return displayOptionWithCount; // For simple string options like "Open", "Closed", or names without counts
    }

    private void disableIssueActions() {
        copyDescriptionAction.setEnabled(false);
        openInBrowserAction.setEnabled(false);
        captureAction.setEnabled(false);
    }

    private void captureSelectedIssue() {
        int selectedRow = issueTable.getSelectedRow();
        if (selectedRow == -1 || selectedRow >= displayedIssues.size()) {
            return;
        }
        captureIssue(displayedIssues.get(selectedRow));
    }

    private void captureIssue(IssueHeader header) {
        var future = contextManager.submitContextTask(() -> {
            try {
                IssueDetails details = issueService.loadDetails(header.id());

                List<ChatMessage> issueTextMessages = buildIssueTextContentFromDetails(details);
                ContextFragment.TaskFragment issueTextFragment =
                        createIssueTextFragmentFromDetails(details, issueTextMessages);
                contextManager.addVirtualFragment(issueTextFragment);

                List<ChatMessage> commentChatMessages = buildChatMessagesFromDtoComments(details.comments());
                if (!commentChatMessages.isEmpty()) {
                    contextManager.addVirtualFragment(createCommentsFragmentFromDetails(details, commentChatMessages));
                }

                int capturedImageCount = processAndCaptureImagesFromDetails(details);

                String commentMessage = details.comments().isEmpty()
                        ? ""
                        : " with " + details.comments().size() + " comment(s)";
                String imageMessage = capturedImageCount == 0 ? "" : " and " + capturedImageCount + " image(s)";
                chrome.showNotification(
                        IConsoleIO.NotificationRole.INFO,
                        "Issue " + header.id() + " captured to workspace" + commentMessage + imageMessage + ".");

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
        if (this.issueService instanceof JiraIssueService) {
            bodyForCapture = HtmlUtil.convertToMarkdown(bodyForCapture);
        }
        bodyForCapture = bodyForCapture.isBlank() ? "*No description provided.*" : bodyForCapture;
        String content = String.format(
                """
                                       # Issue #%s: %s

                                       **Author:** %s
                                       **Status:** %s
                                       **URL:** %s
                                       **Labels:** %s
                                       **Assignees:** %s

                                       ---

                                       %s
                                       """,
                header.id(),
                header.title(),
                header.author(),
                header.status(),
                header.htmlUrl(),
                header.labels().isEmpty() ? "None" : String.join(", ", header.labels()),
                header.assignees().isEmpty() ? "None" : String.join(", ", header.assignees()),
                bodyForCapture);
        return List.of(UserMessage.from(header.author(), content));
    }

    private ContextFragment.TaskFragment createIssueTextFragmentFromDetails(
            IssueDetails details, List<ChatMessage> messages) {
        IssueHeader header = details.header();
        String description = String.format("Issue %s: %s", header.id(), header.title());
        return new ContextFragment.TaskFragment(this.contextManager, messages, description, false);
    }

    private List<ChatMessage> buildChatMessagesFromDtoComments(List<Comment> dtoComments) {
        List<ChatMessage> chatMessages = new ArrayList<>();

        for (Comment comment : dtoComments) {
            String author = comment.author().isBlank() ? "unknown" : comment.author();
            String originalCommentBody = comment.markdownBody(); // HTML from Jira, Markdown from GitHub
            String commentBodyForCapture = originalCommentBody;
            if (this.issueService instanceof JiraIssueService) {
                commentBodyForCapture = HtmlUtil.convertToMarkdown(originalCommentBody);
            }

            if (!commentBodyForCapture.isBlank()) {
                chatMessages.add(UserMessage.from(author, commentBodyForCapture));
            }
        }
        return chatMessages;
    }

    private ContextFragment.TaskFragment createCommentsFragmentFromDetails(
            IssueDetails details, List<ChatMessage> commentMessages) {
        IssueHeader header = details.header();
        String description = String.format("Issue %s: Comments", header.id());
        return new ContextFragment.TaskFragment(this.contextManager, commentMessages, description, false);
    }

    private int processAndCaptureImagesFromDetails(IssueDetails details) {
        IssueHeader header = details.header();
        List<URI> attachmentUris = details.attachmentUrls(); // Already extracted by IssueService
        if (attachmentUris.isEmpty()) {
            return 0;
        }

        int capturedImageCount = 0;
        OkHttpClient clientToUse;
        try {
            clientToUse = issueService.httpClient(); // Use authenticated client from service
        } catch (IOException e) {
            logger.error(
                    "Failed to get authenticated client from IssueService for image download, falling back. Error: {}",
                    e.getMessage());
            // Fallback to the one initialized in GitIssuesTab constructor (might be unauthenticated)
            clientToUse = this.httpClient; // Assumes this.httpClient is still available and initialized
            chrome.showNotification(
                    IConsoleIO.NotificationRole.INFO,
                    "Could not get authenticated client for image download. Private images might not load. Error: "
                            + e.getMessage());
        }

        for (URI imageUri : attachmentUris) {
            try {
                if (ImageUtil.isImageUri(imageUri, clientToUse)) {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO, "Downloading image: " + imageUri.toString());
                    Image image = ImageUtil.downloadImage(imageUri, clientToUse);
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
                ExceptionReporter.tryReportException(e);
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
                if (!body.isBlank()) {
                    StringSelection stringSelection = new StringSelection(body);
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, null);
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO,
                            "Issue " + header.id() + " description copied to clipboard.");
                } else {
                    chrome.showNotification(
                            IConsoleIO.NotificationRole.INFO, "Issue " + header.id() + " has no description to copy.");
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
