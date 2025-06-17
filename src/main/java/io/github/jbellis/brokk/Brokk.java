package io.github.jbellis.brokk;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.git.GitRepo;
import io.github.jbellis.brokk.gui.CheckThreadViolationRepaintManager;
import io.github.jbellis.brokk.gui.Chrome;
import io.github.jbellis.brokk.gui.SwingUtil;
import io.github.jbellis.brokk.gui.dialogs.SettingsDialog;
import io.github.jbellis.brokk.gui.dialogs.StartupDialog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;


public class Brokk {
    private static final Logger logger = LogManager.getLogger(Brokk.class);

    @Nullable
    private static JWindow splashScreen = null;
    private static final ConcurrentHashMap<Path, Chrome> openProjectWindows = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<IProject, List<Chrome>> mainToWorktreeChromes = new ConcurrentHashMap<>();
    private static final Set<Path> reOpeningProjects = ConcurrentHashMap.newKeySet();
    public static final CompletableFuture<AbstractModel> embeddingModelFuture;

    public static final String ICON_RESOURCE = "/brokk-icon.png";

    // Helper record for argument parsing result
    private record ParsedArgs(boolean noProjectFlag, boolean noKeyFlag, @Nullable String projectPathArg) {}

    // Helper record for key validation result
    private record KeyValidationResult(boolean isValid, @Nullable Path dialogProjectPath) {}

    static {
        // Register Bouncy Castle provider for JGit SSH operations if not already present.
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
            logger.info("Bouncy Castle security provider registered.");
        } else {
            logger.info("Bouncy Castle security provider was already registered.");
        }
    }

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

    private static void setupSystemPropertiesAndIcon() {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            System.setProperty("apple.awt.application.name", "Brokk");
        }

        if (Boolean.getBoolean("brokk.devmode")) {
            RepaintManager.setCurrentManager(new CheckThreadViolationRepaintManager());
        }

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
    }

    private static void initializeLookAndFeelAndSplashScreen(boolean isDark) {
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
            logger.fatal("Failed to initialize SplashScreen. Exiting.", e);
            System.exit(1);
        }
    }

    private static ParsedArgs parseArguments(String[] args) {
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
        return new ParsedArgs(noProjectFlag, noKeyFlag, projectPathArg);
    }

    private static KeyValidationResult performKeyValidationLoop(boolean noKeyFlag, @Nullable Path initialDialogPath) {
        boolean keyIsValid = false;
        Path currentDialogPath = initialDialogPath;

        if (!noKeyFlag) {
            String currentBrokkKey = MainProject.getBrokkKey();
            if (!currentBrokkKey.isEmpty()) {
                try {
                    Service.validateKey(currentBrokkKey);
                    keyIsValid = true;
                } catch (IOException e) {
                    logger.warn("Network error validating existing Brokk key at startup. Assuming valid for now.", e);
                    keyIsValid = true; // Allow offline use
                    final Path finalCurrentDialogPath = currentDialogPath;
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(finalCurrentDialogPath != null ? findOpenProjectWindow(finalCurrentDialogPath).getFrame() : null,
                                                                                  "Network error validating Brokk key. AI services may be unavailable.",
                                                                                  "Network Validation Warning", JOptionPane.WARNING_MESSAGE));
                } catch (IllegalArgumentException e) {
                    logger.warn("Existing Brokk key is invalid: {}", e.getMessage());
                }
            }
        } else {
            keyIsValid = true; // --no-key implies key is not needed for validation
        }

        while (!keyIsValid) {
            SwingUtil.runOnEdt(Brokk::hideSplashScreen);
            final Path dialogPathForLambda = currentDialogPath; // Capture for lambda
            Path newKeyPath = SwingUtil.runOnEdt(() -> StartupDialog.showDialog(null, MainProject.getBrokkKey(), false, dialogPathForLambda, StartupDialog.DialogMode.REQUIRE_KEY_ONLY), null);
            if (newKeyPath == null) { // User quit dialog
                logger.info("Startup dialog (key entry) was closed. Shutting down.");
                return new KeyValidationResult(false, dialogPathForLambda);
            }
            // Assume key was validated by dialog. If it returned a path, that's the new project context.
            keyIsValid = true;
            currentDialogPath = newKeyPath; // Update dialog path if user selected a project
        }
        return new KeyValidationResult(true, currentDialogPath);
    }

    private static List<Path> determineInitialProjectsToOpen(ParsedArgs parsedArgs, @Nullable Path dialogProjectPathFromKey) {
        List<Path> projectsToAttemptOpen = new ArrayList<>();
        if (parsedArgs.projectPathArg != null) {
            Path pathFromArg = Path.of(parsedArgs.projectPathArg).toAbsolutePath().normalize();
            if (isValidDirectory(pathFromArg)) {
                projectsToAttemptOpen.add(pathFromArg);
            } else {
                SwingUtil.runOnEdt(Brokk::hideSplashScreen);
                System.err.printf("Specified project path `%s` does not appear to be a valid directory%n", parsedArgs.projectPathArg);
                System.exit(1); // Exit if specified path is invalid
            }
        } else if (dialogProjectPathFromKey != null && isValidDirectory(dialogProjectPathFromKey)) {
            // If a project was selected during key validation, prioritize it
            projectsToAttemptOpen.add(dialogProjectPathFromKey);
        } else if (!parsedArgs.noProjectFlag) {
            projectsToAttemptOpen.addAll(MainProject.getOpenProjects());
        }
        return projectsToAttemptOpen;
    }

    private static boolean attemptOpenProjects(List<Path> projectsToAttemptOpen) {
        Map<Boolean, List<Path>> partitionedProjects = projectsToAttemptOpen.stream()
                .filter(Brokk::isValidDirectory)
                .peek(p -> { if (!isValidDirectory(p)) logger.warn("Skipping invalid path: {}", p); })
                .collect(Collectors.partitioningBy(p -> {
                    if (GitRepo.hasGitRepo(p)) {
                        try (GitRepo tempR = new GitRepo(p)) {
                            return tempR.isWorktree();
                        } catch (Exception e) {
                            logger.warn("Error checking worktree status for {}: {}. Assuming not a worktree for ordering.", p, e.getMessage());
                            return false;
                        }
                    }
                    return false;
                }));

        List<Path> mainRepoPaths = partitionedProjects.get(false); // Paths that are not worktrees (or error)
        List<Path> worktreePaths = partitionedProjects.get(true);  // Paths that are worktrees

        boolean successfulOpenOccurred = false;

        for (Path mainPath : mainRepoPaths) {
            try {
                if (new OpenProjectBuilder(mainPath).open().get()) {
                    successfulOpenOccurred = true;
                }
            } catch (Exception e) {
                logger.error("Failed to open main project {}: {}", mainPath, e.getMessage(), e);
            }
        }

        for (Path worktreePath : worktreePaths) {
            MainProject parentProject = null;
            if (GitRepo.hasGitRepo(worktreePath)) { // Redundant check, but safe
                try (GitRepo wtRepo = new GitRepo(worktreePath)) { // isWorktree already confirmed by partitioning
                    Path gitTopLevel = wtRepo.getGitTopLevel();
                    parentProject = (MainProject) findOpenProjectByPath(gitTopLevel);
                    if (parentProject == null) {
                        logger.warn("During startup, could not find an already open parent project for worktree {} (expected at {}). " +
                                        "Worktree will attempt to find/open its parent or open standalone if necessary.",
                                worktreePath.getFileName(), gitTopLevel.getFileName());
                    }
                } catch (Exception e) {
                    logger.warn("Error determining parent for worktree {} during startup: {}. Proceeding without explicit parent.", worktreePath.getFileName(), e.getMessage());
                }
            }
            try {
                if (new OpenProjectBuilder(worktreePath).parent(parentProject).open().get()) {
                    successfulOpenOccurred = true;
                }
            } catch (Exception e) {
                logger.error("Failed to open worktree project {}: {}", worktreePath, e.getMessage(), e);
            }
        }
        return successfulOpenOccurred;
    }


    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        logger.debug("Brokk starting");
        setupSystemPropertiesAndIcon();
        ParsedArgs parsedArgs = parseArguments(args);

        boolean isDark = MainProject.getTheme().equals("dark");
        initializeLookAndFeelAndSplashScreen(isDark);

        Path initialDialogPath = parsedArgs.projectPathArg != null ? Path.of(parsedArgs.projectPathArg).toAbsolutePath().normalize() : null;
        KeyValidationResult keyResult = performKeyValidationLoop(parsedArgs.noKeyFlag, initialDialogPath);

        if (!keyResult.isValid()) {
            System.exit(0); // User cancelled key dialog or validation failed critically
        }
        initialDialogPath = keyResult.dialogProjectPath(); // Path might have been selected during key validation

        List<Path> projectsToAttemptOpen = determineInitialProjectsToOpen(parsedArgs, initialDialogPath);
        Path currentDialogContextPath = initialDialogPath; // Path to suggest to StartupDialog if needed

        boolean successfulOpenOccurred = false;
        while (true) {
            if (projectsToAttemptOpen.isEmpty()) {
                SwingUtil.runOnEdt(Brokk::hideSplashScreen); // Hide splash before project selection dialog
                final Path dialogPathForLambda = currentDialogContextPath;
                Path selectedPath = SwingUtil.runOnEdt(() -> StartupDialog.showDialog(null, MainProject.getBrokkKey(), true, dialogPathForLambda, StartupDialog.DialogMode.REQUIRE_PROJECT_ONLY), null);
                if (selectedPath == null) { // User quit dialog
                    logger.info("Startup dialog (project selection) was closed. Shutting down.");
                    System.exit(0);
                }
                projectsToAttemptOpen.add(selectedPath);
                currentDialogContextPath = selectedPath; // Update context for next potential dialog
            }

            successfulOpenOccurred = attemptOpenProjects(projectsToAttemptOpen);

            if (successfulOpenOccurred) {
                break; // At least one project opened, exit the startup loop
            } else {
                projectsToAttemptOpen.clear(); // All attempts failed, clear list to re-trigger dialog
                logger.warn("No projects were successfully opened. Retrying with startup dialog.");
                // currentDialogContextPath remains for the next dialog iteration.
                // Key validation is not repeated here as it passed earlier.
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
        try {
            return Files.isDirectory(path.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static CompletableFuture<Void> createAndShowGui(Path projectPath, ContextManager contextManager) {
        assert SwingUtilities.isEventDispatchThread();
        assert contextManager != null : "ContextManager cannot be null when creating GUI";

        var contextFuture = contextManager.createGui();
        var io = (Chrome) contextManager.getIo();

        // Log the current data retention policy.
        // This is called after any necessary dialog has been shown and policy confirmed.
        io.systemOutput("Data Retention Policy set to: " + contextManager.getProject().getDataRetentionPolicy());

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

        return contextFuture;
    }

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
    @Nullable
    public static Chrome findOpenProjectWindow(@Nullable Path projectPath) {
        if (projectPath == null) {
            return null;
        }
        Path normalizedPath = projectPath.toAbsolutePath().normalize();
        // Check direct map first for exact path match
        Chrome directMatch = openProjectWindows.get(normalizedPath);
        if (directMatch != null) {
            // Verify the project root just in case, though it should match if the key matches
            ContextManager cm = directMatch.getContextManager();
            if (cm != null) {
                var p = cm.getProject();
                if (p != null && p.getRoot() != null &&
                    p.getRoot().toAbsolutePath().normalize().equals(normalizedPath)) {
                    return directMatch;
                }
            }
        }
        // Fallback: Iterate if direct match is not conclusive or not found (e.g. path aliases)
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
        Path normalizedPath = path.toAbsolutePath().normalize();
        Chrome chrome = openProjectWindows.get(normalizedPath); // Check direct path first
        if (chrome != null && chrome.getContextManager() != null) {
            IProject project = chrome.getContextManager().getProject();
            if (project != null && project.getRoot().toAbsolutePath().normalize().equals(normalizedPath)) {
                 return project;
            }
        }
        // Fallback if direct match is not conclusive or path is for parent of worktree
        for (Chrome openChrome : openProjectWindows.values()) {
            if (openChrome.getContextManager() != null) {
                IProject project = openChrome.getContextManager().getProject();
                if (project != null && project.getRoot().toAbsolutePath().normalize().equals(normalizedPath)) {
                    return project;
                }
            }
        }
        return null;
    }

    /**
     * Builder for opening projects.
     */
    public static class OpenProjectBuilder {
        private final Path path;
        private MainProject parent;
        private @Nullable Consumer<Chrome> initialTask;
        private Context sourceContextForSession;

        public OpenProjectBuilder(Path path) {
            this.path = path;
        }

        public OpenProjectBuilder parent(@Nullable MainProject parent) {
            this.parent = parent;
            return this;
        }

        public OpenProjectBuilder initialTask(@Nullable Consumer<Chrome> initialTask) {
            this.initialTask = initialTask;
            return this;
        }

        public OpenProjectBuilder sourceContextForSession(@Nullable Context sourceContextForSession) {
            this.sourceContextForSession = sourceContextForSession;
            return this;
        }

        public CompletableFuture<Boolean> open() {
            return Brokk.doOpenProject(this);
        }

        @Override
        public String toString() {
            return "OpenProjectBuilder{" +
                    "path=" + path +
                    ", parent=" + (parent == null ? "null" : parent.getRoot().toString()) +
                    ", initialTask=" + initialTask +
                    ", sourceContextForSession=" + sourceContextForSession +
                    '}';
        }
    }

    private static CompletableFuture<Boolean> doOpenProject(OpenProjectBuilder builder) {
        final Path projectPath = builder.path.toAbsolutePath().normalize();
        logger.debug("Attempting to open project with {}", builder);

        var existingWindow = openProjectWindows.get(projectPath);
        if (existingWindow != null) {
            focusProjectWindow(projectPath);
            if (builder.initialTask != null) {
                 logger.warn("Initial task provided for already open project {}, task will not be run.", projectPath);
            }
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> openCompletionFuture = new CompletableFuture<>();

        initializeProjectAndContextManager(builder)
            .thenAcceptAsync(contextManagerOpt -> {
                if (contextManagerOpt.isEmpty()) {
                    SwingUtil.runOnEdt(Brokk::hideSplashScreen);
                    openCompletionFuture.complete(false);
                    return;
                }

                ContextManager contextManager = contextManagerOpt.get();
                var project = contextManager.getProject();
                Path actualProjectPath = project.getRoot();

                if (project.getDataRetentionPolicy() == MainProject.DataRetentionPolicy.UNSET) {
                    logger.debug("Project {} has no Data Retention Policy set. Showing dialog.", actualProjectPath.getFileName());
                    boolean policySetAndConfirmed = SettingsDialog.showStandaloneDataRetentionDialog(project, null);
                    if (!policySetAndConfirmed) {
                        logger.info("Data retention dialog cancelled for project {}. Aborting open.", actualProjectPath.getFileName());
                        hideSplashScreen();
                        openCompletionFuture.complete(false);
                        return;
                    }
                    logger.info("Data Retention Policy set to: {} for project {}", project.getDataRetentionPolicy(), actualProjectPath.getFileName());
                }

                hideSplashScreen();
                var guiFuture = createAndShowGui(actualProjectPath, contextManager);
                Chrome chromeInstance = openProjectWindows.get(actualProjectPath);
                assert chromeInstance != null : "Chrome instance should be available after createAndShowGui is called";

                // Associate worktree windows. This can happen once chromeInstance is available.
                if (project instanceof MainProject) {
                    mainToWorktreeChromes.putIfAbsent(project, new CopyOnWriteArrayList<>());
                    logger.debug("Registered main project {} for worktree tracking", actualProjectPath.getFileName());
                } else {
                    IProject actualParentProject = project.getParent();
                    mainToWorktreeChromes.computeIfAbsent(actualParentProject, k -> new CopyOnWriteArrayList<>()).add(chromeInstance);
                    logger.debug("Associated worktree window {} with main project {}", actualProjectPath.getFileName(), actualParentProject.getRoot().getFileName());
                }

                // Chain initialTask execution to guiFuture's completion
                guiFuture.whenCompleteAsync((result, guiEx) -> {
                    if (guiEx != null) {
                        // if we have a half-finished gui we're kind of screwed
                        throw new RuntimeException(guiEx);
                    }

                    if (builder.initialTask != null) {
                        logger.debug("Executing initial task for project {}", actualProjectPath.getFileName());
                        builder.initialTask.accept(chromeInstance);
                    }
                    openCompletionFuture.complete(true); // Project opened, GUI ready, initial task (if any) attempted.
                }, SwingUtilities::invokeLater); // Ensure this block runs on EDT
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
                openCompletionFuture.complete(false);
                return null;
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
                var parentOfWorktree = projectBeingClosed.getParent();
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

            new OpenProjectBuilder(projectPath).open().whenCompleteAsync((success, reopenEx) -> {
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

    private static CompletableFuture<Optional<ContextManager>> initializeProjectAndContextManager(OpenProjectBuilder builder) {
        Path projectPath = builder.path;
        MainProject parent = builder.parent;

        try {
            MainProject.updateRecentProject(projectPath);
            var project = AbstractProject.createProject(projectPath, parent);

            if (project.getRepo().isWorktree() && parent == null) {
                logger.warn("User attempted to open a worktree ({}) directly as a project without specific internal trigger. Denying.", projectPath);
                final Path finalProjectPath = projectPath;
                SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                        null,
                        "The selected path (" + finalProjectPath.getFileName() + ") is a Git worktree.\n" +
                        "Worktrees should be managed via the 'Worktrees' tab in the main repository window, or created by Architect.",
                        "Cannot Open Worktree Directly",
                        JOptionPane.WARNING_MESSAGE));
                return CompletableFuture.completedFuture(Optional.empty());
            }

            if (!project.hasGit()) {
                int response = SwingUtil.runOnEdt(() -> JOptionPane.showConfirmDialog(
                        null,
                        """
                        This project is not under Git version control. Would you like to initialize a new Git repository here?

                        Without Git, the project will be read-only, and some features may be limited.""",
                        "Initialize Git Repository?",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE), JOptionPane.NO_OPTION);

                if (response == JOptionPane.YES_OPTION) {
                    try {
                        logger.info("Initializing Git repository at {}...", project.getRoot());
                        io.github.jbellis.brokk.git.GitRepo.initRepo(project.getRoot());
                        project = AbstractProject.createProject(projectPath, parent); // Re-create project
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
                    }
                } else {
                    logger.info("User declined Git initialization for project {}. Proceeding as read-only.", project.getRoot());
                }
            }

            if (project instanceof MainProject mp) {
                mp.reserveSessionsForKnownWorktrees();
            }

            // TODO nothing is actually async here
            var contextManager = new ContextManager(project); // Project must be final or effectively final for lambda
            if (builder.sourceContextForSession != null) {
                // Asynchronously create session from workspace, then complete the future
                String newSessionName = "Architect Session (" + project.getRoot().getFileName() + ")"; // Or generate based on branch
                contextManager.createSessionWithoutGui(builder.sourceContextForSession, newSessionName);
            }
            return CompletableFuture.completedFuture(Optional.of(contextManager));
        } catch (Exception e) {
            logger.error("Failed to initialize project for path {}: {}", projectPath, e.getMessage(), e);
            final String errorMsg = e.getMessage();
            SwingUtil.runOnEdt(() -> JOptionPane.showMessageDialog(
                    null,
                    "Could not open project " + projectPath.getFileName() + ":\n" + errorMsg +
                    "\nPlease check the logs for more details.",
                    "Project Initialization Failed",
                    JOptionPane.ERROR_MESSAGE));
            return CompletableFuture.completedFuture(Optional.empty());
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
            // If not open, just open it directly.
            new OpenProjectBuilder(projectPath).open().whenCompleteAsync((success, ex) -> {
                if (ex != null) {
                    logger.error("Error reopening project {}: {}", projectPath, ex);
                } else if (success == null || !success) {
                    logger.warn("Failed to reopen project {}. It will not be automatically reopened.", projectPath);
                    MainProject.removeFromOpenProjectsListAndClearActiveSession(projectPath);
                }
            }, SwingUtilities::invokeLater);
        }
    }

    public static ConcurrentHashMap<Path, Chrome> getOpenProjectWindows() {
        return openProjectWindows;
    }
}
