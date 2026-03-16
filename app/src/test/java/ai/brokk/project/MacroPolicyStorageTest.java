package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.macro.MacroPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacroPolicyStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void testSaveAndLoadMacroPolicy() throws IOException {
        MainProject project = MainProject.forTests(tempDir);
        Language lang = Languages.RUST;
        project.setAnalyzerLanguages(Set.of(lang));

        MacroPolicy policy = new MacroPolicy(
                "1.0",
                "rust",
                List.of(new MacroPolicy.MacroMatch("my_macro", null, MacroPolicy.MacroStrategy.AI_EXPAND, Map.of())));

        project.setMacroPolicy(lang, policy);

        // Verify file existence
        Path expectedFile = tempDir.resolve(".brokk").resolve("macros_rust.yml");
        assertTrue(java.nio.file.Files.exists(expectedFile), "Policy file should exist");

        // Load back
        Map<Language, MacroPolicy> policies = project.getMacroPolicies();
        assertTrue(policies.containsKey(lang));
        assertEquals("rust", policies.get(lang).language());
        assertEquals(1, policies.get(lang).macros().size());
        assertEquals("my_macro", policies.get(lang).macros().get(0).name());
    }

    @Test
    void testDeleteMacroPolicy() throws IOException {
        MainProject project = MainProject.forTests(tempDir);
        Language lang = Languages.JAVA;
        project.setAnalyzerLanguages(Set.of(lang));

        MacroPolicy policy = new MacroPolicy("1.0", "java", List.of());
        project.setMacroPolicy(lang, policy);

        Path expectedFile = tempDir.resolve(".brokk").resolve("macros_java.yml");
        assertTrue(java.nio.file.Files.exists(expectedFile));

        project.setMacroPolicy(lang, null);
        assertFalse(java.nio.file.Files.exists(expectedFile), "Policy file should be deleted");
    }
}
