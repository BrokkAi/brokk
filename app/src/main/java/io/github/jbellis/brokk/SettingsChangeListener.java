package io.github.jbellis.brokk;

public interface SettingsChangeListener {
    default void gitHubTokenChanged() {}
    default void issueProviderChanged() {}
}
