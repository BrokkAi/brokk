package io.github.jbellis.brokk;

/**
 * Receives notifications from the analyzer about significant events or messages that used to be sent to IConsoleIO.
 * This lets us avoid a direct UI or I/O dependency in AnalyzerWrapper.
 *
 * Since these run on the Analyzer update thread, asking for the analyzer (AnalyzerWrapper::get) is NOT
 * allowed until onAnalyzerReady is called.
 */
public interface AnalyzerListener {
    /** Called when the Analyzer is requested but it is not yet complete. */
    void onBlocked();

    /** Called with details of the first build, which is used to infer auto rebuild policy. */
    void afterFirstBuild(String msg);

    /** Called when changes to tracked files are detected, after the initial build */
    void onTrackedFileChange();

    /** Called when external changes to the git repo are detected */
    void onRepoChange();

    /** Called before each Analyzer build starts. */
    void beforeEachBuild();

    /**
     * Called after each Analyzer build, successful or not. This includes the initial build and any subsequent rebuilds.
     *
     * @param externalRequest true if the build was triggered by an external request.
     */
    void afterEachBuild(boolean externalRequest);

    /** Called when the analyzer transitions from not-ready to ready state. */
    default void onAnalyzerReady() {}
}
