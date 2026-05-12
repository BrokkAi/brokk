package ai.brokk.analyzer.javascript;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared JS/TS module-resolution path helpers. */
public final class JsTsModuleResolution {
    public static final List<String> KNOWN_EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx");

    private JsTsModuleResolution() {}

    public static List<Path> candidatePaths(Path baseDir, String modulePath) {
        Path resolvedPath = baseDir.resolve(modulePath).normalize();
        String fileName = resolvedPath.getFileName().toString();
        var candidates = new ArrayList<Path>();

        if (KNOWN_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            candidates.add(resolvedPath);
        }
        String baseName = fileName;
        for (String ext : KNOWN_EXTENSIONS) {
            if (baseName.endsWith(ext)) {
                baseName = baseName.substring(0, baseName.length() - ext.length());
                break;
            }
        }
        Path basePath = resolvedPath.resolveSibling(baseName);
        candidates.add(basePath);
        for (String ext : KNOWN_EXTENSIONS) {
            candidates.add(basePath.resolveSibling(baseName + ext));
        }
        KNOWN_EXTENSIONS.stream()
                .map(ext -> resolvedPath.resolve("index" + ext))
                .forEach(candidates::add);
        return List.copyOf(candidates);
    }
}
