package io.github.jbellis.brokk.gui.tests;

import java.util.List;

public interface TestRunsStore {
    List<RunRecord> load();

    void save(List<RunRecord> runs);
}
