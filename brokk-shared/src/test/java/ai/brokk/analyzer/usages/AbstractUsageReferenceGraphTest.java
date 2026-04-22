package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.ProjectFile;
import java.util.Set;

abstract class AbstractUsageReferenceGraphTest {

    protected static ProjectFile projectFile(Set<ProjectFile> files, String fileName) {
        return files.stream()
                .filter(pf -> pf.toString().endsWith(fileName))
                .findFirst()
                .orElseThrow();
    }
}
