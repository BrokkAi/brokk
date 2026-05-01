package ai.brokk.executor.staticanalysis;

import ai.brokk.analyzer.ProjectFile;

final class StaticAnalysisPaths {
    private StaticAnalysisPaths() {}

    static String normalizeRequestPath(String path) {
        return path.strip().replace('\\', '/');
    }

    static String externalPath(ProjectFile file) {
        return file.toString().replace('\\', '/');
    }
}
