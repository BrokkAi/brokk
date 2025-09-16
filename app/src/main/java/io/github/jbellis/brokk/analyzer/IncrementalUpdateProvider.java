package io.github.jbellis.brokk.analyzer;

import java.util.Set;

public interface IncrementalUpdateProvider extends CapabilityProvider {

    /**
     * Update the Analyzer for create/modify/delete activity against `changedFiles`. This is O(M) in the number of
     * changed files.
     */
    IAnalyzer update(Set<ProjectFile> changedFiles);

    /**
     * Scan for changes across all files in the Analyzer. This involves hashing each file so it is O(N) in the total
     * number of files and relatively heavyweight.
     */
    IAnalyzer update();
}
