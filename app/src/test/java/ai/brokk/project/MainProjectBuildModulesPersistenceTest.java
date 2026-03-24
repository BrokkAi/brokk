package ai.brokk.project;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent;
import ai.brokk.agents.BuildAgent.ModuleBuildEntry;
import ai.brokk.concurrent.AtomicWrites;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MainProjectBuildModulesPersistenceTest {

    private static final ObjectMapper MAPPER = AbstractProject.objectMapper;

    private static Path brokkProps(Path root) {
        return root.resolve(AbstractProject.BROKK_DIR).resolve(AbstractProject.PROJECT_PROPERTIES_FILE);
    }

    private static Properties loadProps(Path propsFile) {
        try {
            var props = new Properties();
            if (Files.exists(propsFile)) {
                try (var reader = Files.newBufferedReader(propsFile)) {
                    props.load(reader);
                }
            }
            return props;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BuildAgent.BuildDetails parseDetailsFromProps(Properties props) {
        try {
            String json = props.getProperty("buildDetailsJson", "");
            assertFalse(json.isBlank(), "buildDetailsJson should be present");
            return MAPPER.readValue(json, BuildAgent.BuildDetails.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void testModulePersistenceAndNormalization(@TempDir Path root) throws Exception {
        // 1. Arrange: Define realistic modules (including brokk-code like Python TUI)
        var executorModule = new ModuleBuildEntry(
                "executor",
                "app",
                "./gradlew :app:classes",
                "./gradlew :app:test",
                "./gradlew :app:test {{#classes}}--tests {{value}}{{/classes}}",
                "Java");

        var brokkCodeModule = new ModuleBuildEntry(
                "brokk-code",
                "brokk-code",
                "uv run ruff check .",
                "uv run pytest",
                "uv run pytest {{#files}}{{value}}{{^last}} {{/last}}{{/files}}",
                "Python");

        var originalModules = List.of(executorModule, brokkCodeModule);
        var originalDetails = new BuildAgent.BuildDetails(
                "global-lint",
                true,
                "global-test",
                true,
                "",
                false,
                Set.of(),
                java.util.Map.of(),
                null,
                "",
                originalModules);

        // 2. Act: Save via MainProject.forTests (which calls saveBuildDetails)
        MainProject project = MainProject.forTests(root, originalDetails);

        // 3. Assert: Verify raw JSON in project.properties
        Properties props = loadProps(brokkProps(root));
        BuildAgent.BuildDetails persisted = parseDetailsFromProps(props);

        assertEquals(originalModules.size(), persisted.modules().size());
        
        // ModuleBuildEntry normalizes "app" to "app/" and "brokk-code" to "brokk-code/"
        assertEquals("app/", persisted.modules().get(0).relativePath());
        assertEquals("brokk-code/", persisted.modules().get(1).relativePath());
        
        for (int i = 0; i < originalModules.size(); i++) {
            var orig = originalModules.get(i);
            var actual = persisted.modules().get(i);
            assertEquals(orig.alias(), actual.alias());
            assertEquals(orig.language(), actual.language());
            assertEquals(orig.buildLintCommand(), actual.buildLintCommand());
            assertEquals(orig.testAllCommand(), actual.testAllCommand());
            assertEquals(orig.testSomeCommand(), actual.testSomeCommand());
        }

        // 4. Assert: Load via a NEW MainProject instance
        MainProject newProject = new MainProject(root);
        BuildAgent.BuildDetails loaded = newProject.loadBuildDetails().orElseThrow();
        
        assertEquals(persisted, loaded);
        assertEquals("app/", loaded.modules().get(0).relativePath());
    }

    @Test
    void testModulePathRoundTripStability(@TempDir Path root) throws Exception {
        // Arrange: Mixed path formats for modules
        // 1. ./brokk-code
        // 2. brokk-code/
        // 3. brokk-code\
        // 4. absolute path under project
        String absPathUnder = root.resolve("abs-module").toString();
        
        var jsonWithMixedPaths = """
            {
              "modules": [
                { "alias": "m1", "relativePath": "./brokk-code", "language": "Python" },
                { "alias": "m2", "relativePath": "brokk-code/", "language": "Python" },
                { "alias": "m3", "relativePath": "brokk-code\\\\", "language": "Python" },
                { "alias": "m4", "relativePath": "%s", "language": "Java" }
              ]
            }
            """.formatted(absPathUnder.replace("\\", "\\\\"));

        Path propsFile = brokkProps(root);
        Files.createDirectories(propsFile.getParent());
        Properties initialProps = new Properties();
        initialProps.setProperty("buildDetailsJson", jsonWithMixedPaths);
        AtomicWrites.save(propsFile, initialProps, "mixed paths test");

        // Act: Load via MainProject (AbstractProject.loadBuildDetails handles JSON -> BuildDetails)
        MainProject project = new MainProject(root);
        BuildAgent.BuildDetails loaded = project.loadBuildDetails().orElseThrow();

        // Assert: Basic normalization from JSON happens on load
        assertEquals("brokk-code/", loaded.modules().get(0).relativePath());
        assertEquals("brokk-code/", loaded.modules().get(1).relativePath());
        assertEquals("brokk-code/", loaded.modules().get(2).relativePath());

        // Note: Relativization of absolute paths happens in MainProject.saveBuildDetails.
        // On initial load, it remains absolute because ModuleBuildEntry constructor 
        // doesn't know the project root.
        
        // Act: Save back to disk - this triggers canonicalization in MainProject
        project.saveBuildDetails(loaded);
        
        // Reload to verify canonicalization
        BuildAgent.BuildDetails reloaded = project.loadBuildDetails().orElseThrow();
        assertEquals("abs-module/", reloaded.modules().get(3).relativePath());
        
        // Assert: JSON stability
        Properties finalProps = loadProps(propsFile);
        String jsonAfterSave = finalProps.getProperty("buildDetailsJson");
        
        project.saveBuildDetails(loaded);
        Properties stableProps = loadProps(propsFile);
        assertEquals(jsonAfterSave, stableProps.getProperty("buildDetailsJson"), "JSON should be stable after canonicalization");
    }
}
