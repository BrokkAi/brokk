package ai.brokk.util;

import java.util.Locale;
import java.util.Set;

public final class FileTargetHeuristic {
    private static final Set<String> LIKELY_FILE_TARGET_EXTENSIONS = Set.of(
            "c",
            "cc",
            "cpp",
            "cs",
            "css",
            "cxx",
            "dart",
            "go",
            "gradle",
            "groovy",
            "h",
            "hpp",
            "htm",
            "html",
            "java",
            "js",
            "json",
            "jsx",
            "kt",
            "kts",
            "less",
            "m",
            "md",
            "mm",
            "php",
            "properties",
            "py",
            "rb",
            "rs",
            "sass",
            "scala",
            "scss",
            "sh",
            "sql",
            "svelte",
            "swift",
            "toml",
            "ts",
            "tsx",
            "txt",
            "vue",
            "xml",
            "yaml",
            "yml");

    private FileTargetHeuristic() {}

    public static boolean looksLikeFileTarget(String target) {
        if (target.equals(".")
                || target.startsWith(".")
                || target.contains("/")
                || target.contains("\\")
                || target.contains("*")
                || target.contains("?")) {
            return true;
        }

        int lastDot = target.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == target.length() - 1) {
            return false;
        }

        var extension = target.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        return LIKELY_FILE_TARGET_EXTENSIONS.contains(extension);
    }
}
