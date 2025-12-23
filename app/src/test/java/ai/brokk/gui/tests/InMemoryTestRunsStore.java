package ai.brokk.gui.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class InMemoryTestRunsStore implements TestRunsStore {
    private final AtomicReference<List<RunRecord>> lastSavedRuns = new AtomicReference<>(List.of());

    @Override
    public List<RunRecord> load() {
        return lastSavedRuns.get();
    }

    @Override
    public void save(List<RunRecord> runs) {
        lastSavedRuns.set(new ArrayList<>(runs)); // Save a copy
    }
}
