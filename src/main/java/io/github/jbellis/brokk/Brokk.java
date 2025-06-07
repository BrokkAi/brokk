package io.github.jbellis.brokk;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import io.github.jbellis.brokk.gui.CheckThreadViolationRepaintManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.GuiTheme;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.StartupDialog;
import io.github.jbellis.brokk.git.GitRepo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import javax.swing.border.Border;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


public class Brokk {
    private static final Logger logger = LogManager.getLogger(Brokk.class);

    private static JWindow splashScreen = null;
    private static final ConcurrentHashMap<Path, Chrome> openProjectWindows = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<IProject, List<Chrome>> mainToWorktreeChromes = new ConcurrentHashMap<>();
    private static final Set<Path> reOpeningProjects = ConcurrentHashMap.newKeySet();
    public static final CompletableFuture<AbstractModel> embeddingModelFuture;

    public static final String ICON_RESOURCE = "/brokk-icon.png";

    static {
        embeddingModelFuture = CompletableFuture.supplyAsync(() -> {
            logger.info("Loading embedding model asynchronously...");
            var modelName = "sentence-transformers/all-MiniLM-L6-v2";
            File localModelPath;
            try {
                var cacheDir = System.getProperty("user.home") + "/.cache/brokk";
                if (!Files.exists(Path.of(cacheDir)) && !new File(cacheDir).mkdirs()) {
                    throw new IOException("Unable to create model-cache directory at " + cacheDir);
                }
                localModelPath = SafeTensorSupport.maybeDownloadModel(cacheDir, modelName);
            } catch (IOException e) {
                // InstructionsPanel will catch ExecutionException
                throw new UncheckedIOException(e);
            }
            // Assuming loadEmbeddingModel returns BertEmbeddingModel or a compatible type
            var model = ModelSupport.loadEmbeddingModel(localModelPath, DType.F32, DType.I8);
            logger.info("Embedding model loading complete.");
            // Cast to the specific type expected by the Future if necessary
            return model;
        });
    }

    /**
     * Main entry point: Start up Brokk with no project loaded,
     * then check if there's a "most recent" project to open.
     */
    public static void main(String[] args) {
        logger.debug("Brokk starting");

        // Set macOS system properties (non-UI, can be immediate)
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        if (System.getProperty("os.name").toLowerCase().contains("mac")) {
            System.setProperty("apple.awt.application.name", "Brokk");
        }

        // Set up thread violation checker for dev mode
        if (Boolean.getBoolean("brokk.devmode")) {
            RepaintManager.setCurrentManager(new CheckThreadViolationRepaintManager());
        }

        // Set application icon
        var iconUrl = Brokk.class.getResource(ICON_RESOURCE);
        if (iconUrl != null) {
            var icon = new ImageIcon(iconUrl);
            if (Taskbar.isTaskbarSupported()) {
                var taskbar = Taskbar.getTaskbar();
                try {
                    taskbar.setIconImage(icon.getImage());
                } catch (UnsupportedOperationException | SecurityException e) {
                    logger.warn("Unable to set taskbar icon: {}", e.getMessage());
                }
            }
        }

        // Ensure L&F is set on EDT before any UI is created, then show splash screen.
        var isDark = MainProject.getTheme().equals("dark");
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    if (isDark) {
                        com.formdev.flatlaf.FlatDarkLaf.setup();
                    } else {
                        com.formdev.flatlaf.FlatLightLaf.setup();
                    }
                } catch (Exception e) {
                    logger.warn("Failed to set LAF, using default", e);
                }
                showSplashScreen();
            });
        } catch (Exception e) { // Catches InterruptedException and InvocationTargetException
            logger.fatal("Failed to initialize Look and Feel or SplashScreen on EDT. Exiting.", e);
            System.exit(1);
        }

        // preload theme for rsyntaxtextarea, this is moderately slow so we want to get a head start before it gets called on EDT
        Thread.ofPlatform().start(() -> GuiTheme.loadRSyntaxTheme(isDark));

        // Argument parsing and initial validation (off-EDT / main thread)
        boolean noProjectFlag = false;
        boolean noKeyFlag = false;
        String projectPathArg = null;

        for (String arg : args) {
            if (arg.equals("--no-project")) {
                noProjectFlag = true;
            } else if (arg.equals("--no-key")) {
                noKeyFlag = true;
            } else if (!arg.startsWith("--")) {
                projectPathArg = arg;
            }
        }

        // Validate Brokk Key first
        boolean keyIsValid = false;
        if (!noKeyFlag) {
            String currentBrokkKey = MainProject.getBrokkKey();
            if (!currentBrokkKey.isEmpty()) {
                try {
                    Service.validateKey(currentBrokkKey);
                    keyIsValid = true;
                } catch (IOException e) {
                    logger.warn("Network error validating existing Brokk key at startup. Assuming valid for now.", e);
                    keyIsValid = true; // Allow offline use
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                                                                                  "Network error validating Brokk key. AI services may be unavailable.",
                                                                                  "Network Validation Warning", JOptionPane.WARNING_MESSAGE));
                } catch (IllegalArgumentException e) {
                    logger.warn("Existing Brokk key is invalid: {}", e.getMessage());
                }
            }
        }

        // Determine projects to open
        List<Path> projectsToAttemptOpen = new ArrayList<>();
        Path initialDialogPath = null; // For StartupDialog if needed

        if (projectPathArg != null) {
            Path pathFromArg = Path.of(projectPathArg).toAbsolutePath().normalize();
            if (isValidDirectory(pathFromArg)) {
                projectsToAttemptOpen.add(pathFromArg);
                initialDialogPath = pathFromArg;
            } else {
                SwingUtil.runOnEdt(Brokk::hideSplashScreen);
                System.err.printf("Specified project path `%s` does not appear to be a valid directory%n", projectPathArg);
                System.exit(1);
            }
        } else if (!noProjectFlag) {
            projectsToAttemptOpen.addAll(MainProject.getOpenProjects());
            if (!projectsToAttemptOpen.isEmpty()) {
                initialDialogPath = projectsToAttemptOpen.getFirst(); // Use first for dialog context if needed
            }
        }

        // Main application loop: process key, then projects
        boolean successfulOpenOccurred = false;
        while (true) {
            if (!keyIsValid) {
                SwingUtil.runOnEdt(Brokk::hideSplashScreen);
                final Path currentInitialDialogPathForKey = initialDialogPath; // Capture for lambda
                Path newKeyPath = SwingUtil.runOnEdt(() -> StartupDialog.showDialog(null, MainProject.getBrokkKey(), false, currentInitialDialogPathForKey, StartupDialog.DialogMode.REQUIRE_KEY_ONLY), null);
                if (newKeyPath == null) { // User quit dialog
                    logger.info("Startup dialog (key entry) was closed. Shutting down.");
                    System.exit(0);
                }
                keyIsValid = true; // Assume key was validated by dialog
                // newKeyPath might be a project path if user also selected one, use it for initialDialogPath next iteration
                initialDialogPath = newKeyPath; 
                // If projectsToAttemptOpen was empty and dialog provided one, add it.
                if (projectsToAttemptOpen.isEmpty() && initialDialogPath != null && isValidDirectory(initialDialogPath)) {
                    projectsToAttemptOpen.add(initialDialogPath);
                }
            }

            if (projectsToAttemptOpen.isEmpty()) {
                SwingUtil.runOnEdt(Brokk::hideSplashScreen);
                final Path currentInitialDialogPathForProject = initialDialogPath; // Capture for lambda
                Path selectedPath = SwingUtil.runOnEdt(() -> StartupDialog.showDialog(null, MainProject.getBrokkKey(), true, currentInitialDialogPathForProject, StartupDialog.DialogMode.REQUIRE_PROJECT_ONLY), null);
                if (selectedPath == null) { // User quit dialog
                    logger.info("Startup dialog (project selection) was closed. Shutting down.");
                    System.exit(0);
                }
                projectsToAttemptOpen.add(selectedPath);
            }
            
            // Partition projects into main and worktrees
            List<Path> mainRepoPaths = new ArrayList<>();
            List<Path> worktreePaths = new ArrayList<>();
            for (Path p : projectsToAttemptOpen) {
                if (!isValidDirectory(p)) {
                    logger.warn("Skipping invalid path from open projects list: {}", p);
                    continue;
                }
                boolean isWorktree = false;
                if (GitRepo.hasGitRepo(p)) {
                    try (GitRepo tempR = new GitRepo(p)) {
                        isWorktree = tempR.isWorktree();
                    } catch (Exception e) {
                        logger.warn("Error checking worktree status for {}: {}. Assuming not a worktree for ordering.", p, e.getMessage());
                    }
                }
                if (isWorktree) {
                    worktreePaths.add(p);
                } else {
                    mainRepoPaths.add(p);
                }
            }

            // Attempt to open main repositories first
            for (Path mainPath : mainRepoPaths) {
                try {
                    if (openProject(mainPath, null).get()) {
                        successfulOpenOccurred = true;
                    }
                } catch (Exception e) {
                    logger.error("Failed to open main project {}: {}", mainPath, e.getMessage(), e);
                }
            }

            // Then attempt to open worktrees
            for (Path worktreePath : worktreePaths) {
                MainProject parentProject = null;
                // Ensure it's a git repo and actually a worktree before trying to find its parent
                if (GitRepo.hasGitRepo(worktreePath)) {
                    try (GitRepo wtRepo = new GitRepo(worktreePath)) {
                        if (wtRepo.isWorktree()) {
                            Path gitTopLevel = wtRepo.getGitTopLevel();
                            parentProject = (MainProject) Brokk.findOpenProjectByPath(gitTopLevel);
                            if (parentProject == null) {
                                logger.warn("During startup, could not find an already open parent project for worktree {} (expected at {}). " +
                                            "The worktree will attempt to find its parent upon initialization, or may open as a standalone project if the parent isn't available.",
                                            worktreePath.getFileName(), gitTopLevel.getFileName());
                            } else {
                                logger.debug("Found explicit parent {} for worktree {} during startup.", parentProject.getRoot().getFileName(), worktreePath.getFileName());
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error determining parent project for worktree {} during startup: {}. Proceeding without explicit parent.", worktreePath.getFileName(), e.getMessage());
                        // parentProject remains null
                    }
                }

                try {
                    // Pass the found parentProject (which might be null if parent wasn't open or an error occurred)
                    if (openProject(worktreePath, parentProject).get()) {
                        successfulOpenOccurred = true;
                    }
                } catch (Exception e) {
                    logger.error("Failed to open worktree project {}: {}", worktreePath, e.getMessage(), e);
                }
            }

            if (successfulOpenOccurred) {
                break; // At least one project opened, exit the startup loop
            } else {
                // All attempts failed, clear lists and let loop restart for dialog
                projectsToAttemptOpen.clear();
                initialDialogPath = null; // Force full dialog
                keyIsValid = false; // Re-check key too, in case it was the issue indirectly
                logger.warn("No projects were successfully opened. Retrying with startup dialog.");
            }
        }
    }

    private static void showSplashScreen() {
        assert SwingUtilities.isEventDispatchThread();
        if (splashScreen != null) {
            splashScreen.dispose(); // Should not happen if logic is correct
        }
        splashScreen = new JWindow();
        Chrome.applyIcon(splashScreen); // Sets window icon for taskbar if applicable

        var panel = new JPanel(new BorderLayout(15, 0)); // Horizontal gap
        Border lineBorder = BorderFactory.createLineBorder(Color.GRAY);
        Border emptyBorder = BorderFactory.createEmptyBorder(30, 50, 30, 50); // Padding
        panel.setBorder(BorderFactory.createCompoundBorder(lineBorder, emptyBorder));

        var iconUrl = Brokk.class.getResource(ICON_RESOURCE);
        if (iconUrl != null) {
            var icon = new ImageIcon(iconUrl);
            // Scale icon to a reasonable size for splash, e.g., 64x64
            Image scaledImage = icon.getImage().getScaledInstance(64, 64, Image.SCALE_SMOOTH);
            JLabel iconLabel = new JLabel(new ImageIcon(scaledImage)); // Alignment handled by BorderLayout
            panel.add(iconLabel, BorderLayout.WEST);
        }

        var label = new JLabel("Brokk " + BuildInfo.version(), SwingConstants.LEFT); // Align text left
        label.setFont(label.getFont().deriveFont(Font.BOLD, 18f)); // Larger font
        panel.add(label, BorderLayout.CENTER);

        splashScreen.add(panel);
        splashScreen.pack();
        splashScreen.setLocationRelativeTo(null); // Center on screen
        splashScreen.setVisible(true);
        splashScreen.toFront(); // Ensure it's on top
    }

    private static void hideSplashScreen() {
        assert SwingUtilities.isEventDispatchThread();
        if (splashScreen != null) {
            splashScreen.setVisible(false);
            splashScreen.dispose();
            splashScreen = null;
        }
    }

    private static boolean isValidDirectory(Path path) {
        if (path == null) return false;
        try {
            return Files.isDirectory(path.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static void createAndShowGui(Path projectPath, ContextManager contextManager) {
        assert SwingUtilities.isEventDispatchThread();
        assert contextManager != null : "ContextManager cannot be null when creating GUI";

        var io = contextManager.createGui();
        var project = contextManager.getProject();

        // Log the current data retention policy.
        // This is called after any necessary dialog has been shown and policy confirmed.
        io.systemOutput("Data Retention Policy set to: " + project.getDataRetentionPolicy());

        openProjectWindows.put(projectPath, io);

        io.getFrame().addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // Worktree-specific closing dialog removed.
                // Standard window closing procedure will be followed.
                performWindowClose(projectPath);
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                // This now only handles cleanup after the window is actually closed
                // The main logic has moved to windowClosing
            }
        });
    }

    /**
     * Opens the given project folder in Brokk, or brings existing window to front.
     *
     * @param path The path to the project.
     * @param isInternalOperation true if Brokk is opening this project as part of an internal process.
     * @param explicitParent If this project is a worktree being opened by its parent, this is the parent Project.
     * @return A CompletableFuture that completes with true if the project was opened successfully, false otherwise.
     */
    /**
     * Checks if a project window is already open for the given path.
     * @param path The project path.
     * @return true if the project window is open, false otherwise.
     */
    public static boolean isProjectOpen(Path path) {
        return openProjectWindows.containsKey(path.toAbsolutePath().normalize());
    }

    /**
     * Brings the existing window for the given project path to the front and focuses it.
     * Does nothing if the project is not currently open.
     * @param path The project path.
     */
    public static void focusProjectWindow(Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Chrome existingWindow = openProjectWindows.get(normalizedPath);
        if (existingWindow != null) {
            SwingUtilities.invokeLater(() -> {
                JFrame frame = existingWindow.getFrame();
                frame.setState(Frame.NORMAL);
                frame.toFront();
                frame.requestFocus();
            });
        }
    }

    /**
     * Finds the Chrome window for a given project path by checking all open windows.
     * This method searches through all open project windows and compares their project root paths.
     * 
     * @param projectPath The project path to search for
     * @return The Chrome window for the project, or null if not found
     */
    public static Chrome findOpenProjectWindow(Path projectPath) {
        if (projectPath == null) {
            return null;
        }
        Path normalizedPath = projectPath.toAbsolutePath().normalize();
        for (Map.Entry<Path, Chrome> entry : openProjectWindows.entrySet()) {
            ContextManager cm = entry.getValue().getContextManager();
            if (cm != null) {
                var p = cm.getProject();
                if (p != null && p.getRoot() != null && 
                    p.getRoot().toAbsolutePath().normalize().equals(normalizedPath)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    public static IProject findOpenProjectByPath(Path path) {
        Chrome chrome = openProjectWindows.get(path); // Check direct path first
        if (chrome != null) {
            return chrome.getContextManager().getProject();
        }
        return null;
    }

    public static CompletableFuture<Boolean> openProject(Path path, @Nullable MainProject parent) {
        final Path projectPath = path.toAbsolutePath().normalize();
        logger.debug("Attempting to open project at {}: arent: {})", projectPath, parent != null ? parent.getRoot() : "null");

        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            focusProjectWindow(projectPath);
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> openCompletionFuture = new CompletableFuture<>();

        // Stage 1: Initialize Project and ContextManager (off-EDT)
        CompletableFuture.supplyAsync(() -> initializeProjectAndContextManager(projectPath, parent), ForkJoinPool.commonPool())
            .thenAcceptAsync(contextManagerOpt -> { // Stage 2: Handle policy dialog and GUI creation (on-EDT)
                if (contextManagerOpt.isEmpty()) {
                    SwingUtil.runOnEdt(Brokk::hideSplashScreen); // Ensure splash is hidden on failure
                    openCompletionFuture.complete(false);
                    return;
                }

                ContextManager contextManager = contextManagerOpt.get();
                var project = contextManager.getProject();
                Path actualProjectPath = project.getRoot(); // Use path from Project instance

                // Check and show data retention dialog if needed
                if (project.getDataRetentionPolicy() == MainProject.DataRetentionPolicy.UNSET) {
                    logger.debug("Project {} has no Data Retention Policy set. Showing dialog.", actualProjectPath.getFileName());
                    boolean policySetAndConfirmed = SettingsDialog.showStandaloneDataRetentionDialog(project, null); // Parent frame is null

                    if (!policySetAndConfirmed) {
                        logger.info("Data retention dialog cancelled for project {}. Aborting open.", actualProjectPath.getFileName());
                        hideSplashScreen();
                        openCompletionFuture.complete(false);
                        return;
                    }
                    // Policy set and OK'd by dialog; project object is updated.
                    logger.info("Data Retention Policy set to: {} for project {}", project.getDataRetentionPolicy(), actualProjectPath.getFileName());
                }

                // If policy was already set, or was set and OK'd by the dialog
                hideSplashScreen(); // Hide splash just before showing the main GUI
                createAndShowGui(actualProjectPath, contextManager);
                Chrome chromeInstance = openProjectWindows.get(actualProjectPath); // Get the newly created Chrome instance
                if (chromeInstance != null) {
                    if (project.getParent() != project && project.getParent() instanceof MainProject) { // It's a worktree with a MainProject parent
                        IProject actualParentProject = project.getParent();
                        mainToWorktreeChromes.computeIfAbsent(actualParentProject, k -> new CopyOnWriteArrayList<>()).add(chromeInstance);
                        logger.debug("Associated worktree window {} with main project {}", actualProjectPath.getFileName(), actualParentProject.getRoot().getFileName());
                    } else if (project instanceof MainProject) { // It's a main project
                        // Ensure an entry exists for this main project, even if it has no worktrees opened *yet*
                        mainToWorktreeChromes.putIfAbsent(project, new CopyOnWriteArrayList<>());
                        logger.debug("Registered main project {} for worktree tracking", actualProjectPath.getFileName());
                    }
                }
                openCompletionFuture.complete(true);
            }, SwingUtilities::invokeLater)
            .exceptionally(ex -> {
                logger.error("Exception during project opening pipeline for {}: {}", projectPath, ex.getMessage(), ex);
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String errorMessage = """
                                      A critical error occurred while trying to open the project:
                                      %s

                                      Please check the logs at ~/.brokk/debug.log and consider filing a bug report.
                                      """.formatted(cause.getMessage()).stripIndent();
                SwingUtil.runOnEdt(() -> {
                    hideSplashScreen(); // Hide splash before showing error dialog
                    JOptionPane.showMessageDialog(null,
                                                  errorMessage,
                                                  "Project Open Error", JOptionPane.ERROR_MESSAGE);
                });
                openCompletionFuture.complete(false); // Signify failure
                return null; // Required for exceptionally's Function<Throwable, ? extends T>
            });

        return openCompletionFuture;
    }

    private static void performWindowClose(Path projectPath) {
        Chrome ourChromeInstance = openProjectWindows.get(projectPath);
        IProject projectBeingClosed = null;
        if (ourChromeInstance != null && ourChromeInstance.getContextManager() != null) {
            projectBeingClosed = ourChromeInstance.getContextManager().getProject();
        }

        if (projectBeingClosed != null) {
            if (projectBeingClosed instanceof MainProject) { // Closing a main project
                logger.debug("Main project {} is closing. Closing its associated worktree windows.", projectPath.getFileName());
                List<Chrome> worktreeChromes = mainToWorktreeChromes.remove(projectBeingClosed);
                if (worktreeChromes != null) {
                    for (Chrome worktreeChrome : worktreeChromes) {
                        Path worktreeChromePath = worktreeChrome.getContextManager().getProject().getRoot();
                        logger.debug("Closing worktree window: {}", worktreeChromePath.getFileName());
                        // Standard way to close a window: dispatch event, then it will call performWindowClose for itself.
                        SwingUtilities.invokeLater(() -> {
                            JFrame frame = worktreeChrome.getFrame();
                            if (frame != null) {
                                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                            }
                        });
                        // DO NOT remove from openProjectWindows here; the worktree's own performWindowClose will handle that.
                    }
                }
            } else { // Closing a worktree project
                IProject parentOfWorktree = projectBeingClosed.getParent();
                if (parentOfWorktree != null) {
                    List<Chrome> worktreeListOfMain = mainToWorktreeChromes.get(parentOfWorktree);
                    if (worktreeListOfMain != null) {
                        if (worktreeListOfMain.remove(ourChromeInstance)) {
                             logger.debug("Removed worktree window {} from main project {}'s tracking list.", projectPath.getFileName(), parentOfWorktree.getRoot().getFileName());
                        }
                        // If the list becomes empty, we could remove the main project's entry from mainToWorktreeChromes,
                        // but computeIfAbsent handles recreation, so it's not strictly necessary for correctness.
                        // However, for cleanliness:
                        if (worktreeListOfMain.isEmpty()) {
                            mainToWorktreeChromes.remove(parentOfWorktree);
                            logger.debug("Removed main project {} from worktree tracking map as its list of worktrees is now empty.", parentOfWorktree.getRoot().getFileName());
                        }
                    }
                }
            }
        }

        // Standard cleanup
        Chrome removedChrome = openProjectWindows.remove(projectPath);
        if (removedChrome != null) {
            removedChrome.close();
        }
        logger.debug("Removed project from open windows map: {}", projectPath);

        if (reOpeningProjects.contains(projectPath)) {
            CompletableFuture.runAsync(() -> MainProject.removeFromOpenProjectsListAndClearActiveSession(projectPath))
                    .exceptionally(ex -> {
                        logger.error("Error removing project (before reopen) from open projects list: {}", projectPath, ex);
                        return null;
                    });

            openProject(projectPath, null).whenCompleteAsync((success, reopenEx) -> {
                reOpeningProjects.remove(projectPath);
                if (reopenEx != null) {
                    logger.error("Exception occurred while trying to reopen project: {}", projectPath, reopenEx);
                } else if (success == null || !success) {
                    logger.warn("Failed to reopen project: {}. It will not be reopened.", projectPath);
                }
                if (openProjectWindows.isEmpty() && reOpeningProjects.isEmpty()) {
                    logger.info("All projects closed after reopen attempt of {}. Exiting.", projectPath);
                    System.exit(0);
                }
            }, SwingUtilities::invokeLater);
            return;
        }

        // Dispose the frame to actually close the window
        if (ourChromeInstance != null) {
            ourChromeInstance.getFrame().dispose();
        }

        boolean noMainProjectsOpen = openProjectWindows.values().stream()
            .noneMatch(chrome -> {
                IProject p = chrome.getContextManager().getProject();
                return p instanceof MainProject; // Check if it's a main project
            });
        boolean appIsExiting = noMainProjectsOpen && reOpeningProjects.isEmpty();
        if (appIsExiting) {
            // We are about to exit the application.
            // Do NOT remove this project from the persistent "open projects" list.
            logger.info("Last project window ({}) closed. App exiting. It remains MRU.", projectPath);
            System.exit(0);
        } else {
            // Other projects are still open or other projects are pending reopening.
            // This one is just closing, so remove it from the persistent "open projects" list.
            CompletableFuture.runAsync(() -> MainProject.removeFromOpenProjectsListAndClearActiveSession(projectPath))
                    .exceptionally(ex -> {
                        logger.error("Error removing project from open projects list: {}", projectPath, ex);
                        return null;
                    });
            // No System.exit(0) here, as other windows/tasks are active.
        }
    }

    private static java.util.Optional<ContextManager> initializeProjectAndContextManager(Path projectPath, @Nullable MainProject parent) {
        try {
            MainProject.updateRecentProject(projectPath);

            var project = AbstractProject.createProject(projectPath, parent);

            // Prevent users from directly opening a worktree as a primary project,
            // *unless* it's an internal operation (like auto-opening associated worktrees or from GitWorktreeTab).
            if (project.getRepo().isWorktree() && parent == null) {
                logger.warn("User attempted to open a worktree ({}) directly as a project. Denying.", projectPath);
                final Path finalProjectPath = projectPath; // for lambda
                SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                        null,
                        "The selected path (" + finalProjectPath.getFileName() + ") is a Git worktree.\n" +
                        "Worktrees should be managed via the 'Worktrees' tab in the main repository window.\n" +
                        "Brokk will open them automatically when created there.",
                        "Cannot Open Worktree Directly",
                        JOptionPane.WARNING_MESSAGE));
                return java.util.Optional.empty(); // Prevent this worktree from being opened by direct user action
            }

            if (!project.hasGit()) {
                int response = SwingUtil.runOnEdt(() -> JOptionPane.showConfirmDialog(
                        null,
                        """
                        This project is not under Git version control. Would you like to initialize a new Git repository here?\

                        Without Git, the project will be read-only, and some features may be limited.""",
                        "Initialize Git Repository?",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE), JOptionPane.NO_OPTION);

                if (response == JOptionPane.YES_OPTION) {
                    try {
                        logger.info("Initializing Git repository at {}...", project.getRoot());
                        io.github.jbellis.brokk.git.GitRepo.initRepo(project.getRoot());
                        // Re-create project to reflect new .git dir; pass existing explicitParent if any.
                        project = AbstractProject.createProject(projectPath, parent);
                        logger.info("Git repository initialized successfully at {}.", project.getRoot());
                    } catch (Exception e) {
                        logger.error("Failed to initialize Git repository at {}: {}", project.getRoot(), e.getMessage(), e);
                        final String errorMsg = e.getMessage();
                        SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                                null,
                                "Failed to initialize Git repository: " + errorMsg +
                                "\nThe project will be opened as read-only.",
                                "Git Initialization Error",
                                JOptionPane.ERROR_MESSAGE));
                        // Continue as read-only with the non-Git project instance
                    }
                } else {
                    logger.info("User declined Git initialization for project {}. Proceeding as read-only.", project.getRoot());
                    // Continue as read-only
                }
            }

            if (project instanceof MainProject mp) {
                mp.reserveSessionsForKnownWorktrees();
            }

            return java.util.Optional.of(new ContextManager(project));
        } catch (Exception e) {
            logger.error("Failed to initialize project and ContextManager for path {}: {}", projectPath, e.getMessage(), e);
            final String errorMsg = e.getMessage();
            SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                    null,
                    "Could not open project " + projectPath.getFileName() + ":\n" + errorMsg +
                    "\nPlease check the logs for more details.",
                    "Project Initialization Failed",
                    JOptionPane.ERROR_MESSAGE));
            return java.util.Optional.empty();
        }
    }

    /**
     * Closes the project if it's already open, then opens it again fresh.
     * Useful for reloading after external changes or config updates.
     */
    public static void reOpenProject(Path path)
    {
        assert SwingUtilities.isEventDispatchThread();

        final Path projectPath = path.toAbsolutePath().normalize();
        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            // Mark as re-opening, the windowClosed listener will do the rest
            reOpeningProjects.add(projectPath);
            // Programatically close the window
            var frame = openProjectWindows.get(projectPath).getFrame();
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        } else {
            // If not open, just open it directly. Pass null for explicitParent.
            openProject(projectPath, null).whenCompleteAsync((success, ex) -> {
                if (ex != null) {
                    logger.error("Error reopening project {}: {}", projectPath, ex);
                } else if (success == null || !success) {
                    logger.warn("Failed to reopen project {}. It will not be automatically reopened.", projectPath);
                    MainProject.removeFromOpenProjectsListAndClearActiveSession(projectPath);
                }
            }, SwingUtilities::invokeLater);
        }
    }
}
