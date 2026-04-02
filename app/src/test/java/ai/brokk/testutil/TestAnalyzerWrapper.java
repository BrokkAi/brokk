package ai.brokk.testutil;

import ai.brokk.IAnalyzerWrapper;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

public class TestAnalyzerWrapper implements IAnalyzerWrapper {

    private @Nullable IAnalyzer analyzer;
    private final AtomicInteger pauseCount = new AtomicInteger(0);
    private final AtomicInteger resumeCount = new AtomicInteger(0);
    private final AtomicInteger rebuildCount = new AtomicInteger(0);
    private final AtomicInteger deletePersistedCount = new AtomicInteger(0);

    public TestAnalyzerWrapper(@Nullable IAnalyzer analyzer) {
        this.analyzer = analyzer;
    }

    public TestAnalyzerWrapper() {
        this(null);
    }

    @Override
    public CompletableFuture<IAnalyzer> updateFiles(Set<ProjectFile> relevantFiles) {
        if (analyzer != null) {
            IAnalyzer updated = analyzer.update(relevantFiles);
            this.analyzer = updated;
            return CompletableFuture.completedFuture(updated);
        }
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Not used in this test"));
    }

    @Override
    public IAnalyzer get() throws InterruptedException {
        if (analyzer != null) {
            return analyzer;
        }
        throw new UnsupportedOperationException("Not used in this test");
    }

    @Override
    public IAnalyzer getNonBlocking() {
        return analyzer;
    }

    @Override
    public void requestRebuild() {
        rebuildCount.incrementAndGet();
    }

    @Override
    public void pause() {
        pauseCount.incrementAndGet();
    }

    @Override
    public void resume() {
        resumeCount.incrementAndGet();
    }

    @Override
    public void deletePersistedAnalyzerStateFiles() {
        deletePersistedCount.incrementAndGet();
    }

    @Override
    public boolean isPause() {
        return pauseCount.get() > resumeCount.get();
    }

    public int getPauseCount() {
        return pauseCount.get();
    }

    public int getResumeCount() {
        return resumeCount.get();
    }

    public int getRebuildCount() {
        return rebuildCount.get();
    }

    public int getDeletePersistedCount() {
        return deletePersistedCount.get();
    }
}
