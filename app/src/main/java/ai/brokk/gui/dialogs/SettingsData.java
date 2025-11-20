package ai.brokk.gui.dialogs;

import ai.brokk.IProject;
import ai.brokk.MainProject;
import ai.brokk.Service;
import ai.brokk.agents.BuildAgent.BuildDetails;
import java.io.IOException;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Consolidated settings data loaded in background to avoid EDT blocking.
 * All I/O operations happen in the static load() method which runs off EDT.
 */
public record SettingsData(
        // Global settings
        MainProject.JvmMemorySettings jvmMemorySettings,
        String brokkApiKey,
        String accountBalance,
        List<Service.FavoriteModel> favoriteModels,

        // Project-specific settings (nullable if no project)
        @Nullable BuildDetails buildDetails,
        @Nullable String styleGuide,
        @Nullable String commitMessageFormat,
        @Nullable String reviewGuide) {
    private static final Logger logger = LogManager.getLogger(SettingsData.class);

    /**
     * Loads all settings in background thread. All I/O happens here.
     * This method should never be called on the EDT.
     *
     * @param project Current project, or null if no project is open
     * @return SettingsData with all loaded settings
     */
    @Blocking
    public static SettingsData load(@Nullable IProject project) {
        // Load global settings (file I/O)
        var jvmSettings = MainProject.getJvmMemorySettings();
        var apiKey = MainProject.getBrokkKey();
        var balance = loadAccountBalance(apiKey); // network I/O
        var models = MainProject.loadFavoriteModels(); // file I/O

        // If empty, create default and save
        if (project != null && models.isEmpty()) {
            var currentCodeConfig = project.getMainProject().getCodeModelConfig();
            var defaultAlias = "default";
            var defaultFavorite = new Service.FavoriteModel(defaultAlias, currentCodeConfig);
            models = List.of(defaultFavorite);
            try {
                MainProject.saveFavoriteModels(models); // file I/O write
            } catch (Exception e) {
                logger.warn("Failed to save default favorite models", e);
            }
        }

        // Load project-specific settings (file I/O) if project exists
        BuildDetails buildDetails = null;
        String styleGuide = null;
        String commitFormat = null;
        String reviewGuide = null;

        if (project != null) {
            try {
                buildDetails = project.loadBuildDetails();
                styleGuide = project.getStyleGuide();
                commitFormat = project.getCommitMessageFormat();
                reviewGuide = project.getReviewGuide();
            } catch (Exception e) {
                logger.warn("Failed to load project settings", e);
            }
        }

        return new SettingsData(
                jvmSettings, apiKey, balance, models, buildDetails, styleGuide, commitFormat, reviewGuide);
    }

    /**
     * Loads account balance via network call. Safe to call off EDT.
     */
    @Blocking
    private static String loadAccountBalance(String apiKey) {
        if (apiKey.isBlank()) {
            return "No API key configured";
        }

        try {
            Service.validateKey(apiKey); // throws if invalid
            float balance = Service.getUserBalance(apiKey);
            return String.format("$%.2f", balance);
        } catch (IllegalArgumentException e) {
            return "Invalid API key format";
        } catch (IOException e) {
            logger.warn("Failed to load account balance", e);
            return "Error loading balance: " + e.getMessage();
        }
    }
}
