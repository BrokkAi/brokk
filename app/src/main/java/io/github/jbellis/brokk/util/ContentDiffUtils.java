package io.github.jbellis.brokk.util;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.PatchFailedException;

public class ContentDiffUtils {

    public static String diff(String oldContent, String newContent) {
        var oldLines = oldContent.lines().toList();
        var newLines = newContent.lines().toList();
        var patch = DiffUtils.diff(oldLines, newLines);
        if (patch.getDeltas().isEmpty()) {
            return "";
        }
        var diffLines = UnifiedDiffUtils.generateUnifiedDiff("old", "new", oldLines, patch, 0);
        return String.join("\n", diffLines);
    }

    public static String applyDiff(String diff, String oldContent) {
        if (diff.isBlank()) {
            return oldContent;
        }
        var diffLines = diff.lines().toList();
        var patch = UnifiedDiffUtils.parseUnifiedDiff(diffLines);
        try {
            var oldLines = oldContent.lines().toList();
            var newLines = patch.applyTo(oldLines);
            return String.join("\n", newLines);
        } catch (PatchFailedException e) {
            throw new RuntimeException("Failed to apply patch", e);
        }
    }
}
