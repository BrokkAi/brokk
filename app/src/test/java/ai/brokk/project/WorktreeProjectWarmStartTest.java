package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeProjectWarmStartTest {

    @TempDir
    Path parentRoot;

    @TempDir
    Path worktreeRoot;

    @Test
    void testWarmStartCopying() throws IOException {
        // 1. Setup parent project using MainProject.forTests to avoid auto-detection logic
        MainProject parentMain = MainProject.forTests(parentRoot);

        Language java = Languages.JAVA;
        Language python = Languages.PYTHON;
        Language typescript = Languages.TYPESCRIPT;

        // Ensure .brokk exists for cache files
        Path parentBrokk = parentRoot.resolve(".brokk");
        Files.createDirectories(parentBrokk);

        Path javaParentCache = java.getStoragePath(parentMain);
        Path pythonParentCache = python.getStoragePath(parentMain);
        Path typescriptParentCache = typescript.getStoragePath(parentMain);

        Files.createDirectories(javaParentCache.getParent());
        Files.writeString(javaParentCache, "java-cache-content");
        Files.writeString(pythonParentCache, "python-cache-content");
        Files.writeString(typescriptParentCache, "typescript-cache-content");

        // 2. Setup worktree with an existing file to test "don't overwrite"
        WorktreeProject worktree = new WorktreeProject(worktreeRoot, parentMain);

        Path javaTargetCache = java.getStoragePath(worktree);
        Files.createDirectories(javaTargetCache.getParent());
        String existingContent = "existing-java-content";
        Files.writeString(javaTargetCache, existingContent);
        long existingTimestamp = Files.getLastModifiedTime(javaTargetCache).toMillis();

        // 3. Execute warm start using the helper to inject multi-language and NONE
        Language multi = new Language.MultiLanguage(Set.of(typescript));
        worktree.warmStartAnalyzerCachesFromParent(Set.of(java, python, multi, Languages.NONE));

        // 4. Assertions

        // - Existing target files are not overwritten
        assertTrue(Files.exists(javaTargetCache));
        assertEquals(existingContent, Files.readString(javaTargetCache), "Java cache should not have been overwritten");
        assertEquals(
                existingTimestamp,
                Files.getLastModifiedTime(javaTargetCache).toMillis(),
                "Timestamp should not have changed");

        // - Target files exist for those languages that weren't there
        Path pythonTargetCache = python.getStoragePath(worktree);
        assertTrue(Files.exists(pythonTargetCache), "Python cache should have been copied");
        assertEquals("python-cache-content", Files.readString(pythonTargetCache));

        // - MultiLanguage flattening works (TypeScript should be copied)
        Path typescriptTargetCache = typescript.getStoragePath(worktree);
        assertTrue(
                Files.exists(typescriptTargetCache), "TypeScript cache (from MultiLanguage) should have been copied");
        assertEquals("typescript-cache-content", Files.readString(typescriptTargetCache));

        // - No file is copied for Languages.NONE
        try {
            Path nonePath = Languages.NONE.getStoragePath(worktree);
            assertFalse(Files.exists(nonePath), "NONE language should not result in a file at getStoragePath");
        } catch (UnsupportedOperationException e) {
            // This is also an acceptable way for NONE to behave
        }
        Path manualNonePath = worktreeRoot.resolve(".brokk").resolve("none" + Language.ANALYZER_STATE_SUFFIX);
        assertFalse(Files.exists(manualNonePath), "NONE language should not result in a 'none.bin.lz4' file");
    }
}
