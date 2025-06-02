package io.github.jbellis.brokk;

/**
 * Receives notifications from the analyzer about significant events or messages
 * that used to be sent to IConsoleIO. This lets us avoid a direct UI or I/O
 * dependency in AnalyzerWrapper.
 */
public interface AnalyzerListener
{
    /**
     * Called when the Analyzer is requested but it is not yet complete.
     */
    void onBlocked();

    /**
     * Called with details of the first build, which is used to infer auto rebuild policy.
     */
    void afterFirstBuild(String msg);

    /**
     * Called when changes to tracked files are detected, after the initial build
     */
    void onTrackedFileChange();

    /**
     * Called when external changes to the git repo are detected
     */
    void onRepoChange();

    /**
     * Called after each Analyzer build, successful or not.
     * This includes the initial build and any subsequent rebuilds.
     */
    void afterEachBuild();
}
