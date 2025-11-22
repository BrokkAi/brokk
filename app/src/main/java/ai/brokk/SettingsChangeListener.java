package ai.brokk;

public interface SettingsChangeListener {
    default void gitHubTokenChanged() {}

    default void issueProviderChanged() {}

    default void autoUpdateLocalDependenciesChanged() {}

    default void autoUpdateGitDependenciesChanged() {}
}
