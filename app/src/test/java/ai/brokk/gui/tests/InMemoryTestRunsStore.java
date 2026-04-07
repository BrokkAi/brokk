package ai.brokk.gui.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class InMemoryTestRunsStore implements TestRunsStore {
    private final AtomicReference<List<Run>> lastSavedRuns = new AtomicReference<>(List.of());

    @Override
    public List<Run> load() {
        return lastSavedRuns.get();
    }

    @Override
    public void save(List<Run> runs) {
        lastSavedRuns.set(new ArrayList<>(runs)); // Save a copy
    }
}
