package ai.brokk.init.onboarding;

/**
 * Marker interface for onboarding step dialog data payloads.
 * <p>
 * All step-specific data records that need to be passed to the UI layer
 * for dialog display must implement this interface.
 * <p>
 * This enables type-safe handling of step results in the UI layer without
 * unsafe casts.
 */
public sealed interface OnboardingDialogData
        permits MigrationStep.MigrationDialogData,
                BuildSettingsStep.BuildSettingsDialogData,
                GitConfigStep.GitConfigDialogData,
                PostGitStyleRegenerationStep.RegenerationOfferData {}
