package ai.brokk.watchservice;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class NoopWatchService extends AbstractWatchService {
    public NoopWatchService() {
        super(Path.of("."), null, null, List.of());
    }

    @Override
    public void start(CompletableFuture<?> delayNotificationsUntilCompleted) {}

    @Override
    public void pause() {}

    @Override
    public void resume() {}

    @Override
    public boolean isPaused() {
        return false;
    }

    @Override
    public void close() {}
}
