package ai.brokk;

public class NullAnalyzerListener implements AnalyzerListener {
    @Override
    public void onBlocked() {}

    @Override
    public void beforeEachBuild() {}

    @Override
    public void afterEachBuild(boolean externalRequest) {}
}
