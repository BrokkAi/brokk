package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.ProjectFile;
import java.util.Set;

abstract class AbstractUsageReferenceGraphTest {

    protected static ProjectFile projectFile(Set<ProjectFile> files, String fileName) {
        String normalizedFileName = fileName.replace('\\', '/');
        return files.stream()
                .filter(pf -> pf.toString().replace('\\', '/').endsWith(normalizedFileName))
                .findFirst()
                .orElseThrow();
    }
}
