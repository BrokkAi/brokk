package ai.brokk;

public class NullAnalyzerListener implements AnalyzerListener {
    @Override
    public void onBlocked() {}

    @Override
    public void afterFirstBuild(String msg) {}

    @Override
    public void onTrackedFileChange() {}

    @Override
    public void onRepoChange() {}

    @Override
    public void beforeEachBuild() {}

    @Override
    public void afterEachBuild(boolean externalRequest) {}

    {
    }
}
