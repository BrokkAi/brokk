package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.util.AtomicWrites;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class BuildDetailsPathNormalizationTest {

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
    void testCanonicalization_onSave(@TempDir Path root) throws Exception {
        // Arrange: BuildDetails with mixed forms
        Path absUnder = root.resolve("absUnder"); // absolute under project, should become relative "absUnder"
        var rawExcludesOrdered = new LinkedHashSet<String>(List.of(
                "bin",
                ".\\gradle",
                "./build/",
                "subdir\\vendor",
                "/nbdist",
                absUnder.toString() // absolute within project
                ));

        var project = new MainProject(root);

        var details = new BuildAgent.BuildDetails("", "", "", rawExcludesOrdered, Map.of());

        // Act: save and read back from properties
        project.saveBuildDetails(details);

        Properties props1 = loadProps(brokkProps(root));
        String json1 = props1.getProperty("buildDetailsJson");
        assertNotNull(json1, "buildDetailsJson should be persisted");

        var parsed1 = parseDetailsFromProps(props1);

        // Assert: canonicalized exclusions in order
        var expectedCanonicalOrder = List.of("bin", "gradle", "build", "subdir/vendor", "/nbdist", "absUnder");
        assertEquals(
                expectedCanonicalOrder.size(), parsed1.excludedDirectories().size(), "Unexpected excludes size");
        assertIterableEquals(
                expectedCanonicalOrder, parsed1.excludedDirectories(), "Exclusions not canonicalized as expected");

        // Assert: JSON stability across subsequent save of canonical content
        project.saveBuildDetails(parsed1);
        Properties props2 = loadProps(brokkProps(root));
        String json2 = props2.getProperty("buildDetailsJson");
        assertEquals(json1, json2, "buildDetailsJson should be stable across saves");
    }

    @Test
    void testLegacyJson_loadsAndRewrites(@TempDir Path root) throws Exception {
        // Arrange: pre-write legacy JSON with backslashes, leading "/", and absolute-under-project paths
        Path fooAbs = root.resolve("foo");
        var legacyExcludes = List.of(
                "build\\",
                "/nbdist",
                fooAbs.toString(), // absolute under project -> should become "foo"
                ".\\out/");

        var legacyDetails = new BuildAgent.BuildDetails("", "", "", new LinkedHashSet<>(legacyExcludes), Map.of());
        String legacyJson = MAPPER.writeValueAsString(legacyDetails);

        // Pre-create .brokk/project.properties with legacy JSON
        Path propsFile = brokkProps(root);
        Files.createDirectories(propsFile.getParent());
        var props = new Properties();
        props.setProperty("buildDetailsJson", legacyJson);
        AtomicWrites.atomicSaveProperties(propsFile, props, "legacy test");

        // Act: construct project and load canonicalized details
        var project = new MainProject(root);
        var loaded = project.loadBuildDetails();

        // Assert: canonicalization occurred on load
        // Expected: "build", "/nbdist" remains absolute (outside project), "foo", "out"
        Set<String> expectedCanonical = Set.of("build", "/nbdist", "foo", "out");
        assertEquals(expectedCanonical, loaded.excludedDirectories(), "Loaded exclusions should be canonicalized");

        // Act: save back and ensure canonical JSON now persisted
        project.saveBuildDetails(loaded);
        Properties props2 = loadProps(propsFile);
        var persisted = parseDetailsFromProps(props2);
        assertEquals(expectedCanonical, persisted.excludedDirectories(), "Persisted exclusions should be canonical");

        // Optional stability check on rewrite
        String jsonAfterRewrite = props2.getProperty("buildDetailsJson");
        assertNotNull(jsonAfterRewrite);
        // Re-save again; should be identical
        project.saveBuildDetails(persisted);
        Properties props3 = loadProps(propsFile);
        assertEquals(jsonAfterRewrite, props3.getProperty("buildDetailsJson"), "Canonical JSON should be stable");
    }

    /**
     * Verify that baseline filtering treats "/build" the same as "build".
     * We initialize a Git repository so AbstractProject.applyFiltering() executes the filtering logic path.
     */
    @Test
    void testApplyFiltering_acceptsLeadingSlash(@TempDir Path root) throws Exception {
        // Initialize a real Git repo so filtering path is exercised
        Git.init().setDirectory(root.toFile()).call();

        // Create some files on disk (existence not strictly required for baseline filter, but realistic)
        Path buildFile = root.resolve("build").resolve("Generated.java");
        Files.createDirectories(buildFile.getParent());
        Files.writeString(buildFile, "class Generated {}");

        Path srcFile = root.resolve("src").resolve("Main.java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, "class Main {}");

        var project = new MainProject(root);

        // Prepare a synthetic file set to filter
        var pfBuild = new ProjectFile(project.getMasterRootPathForConfig(), Path.of("build/Generated.java"));
        var pfSrc = new ProjectFile(project.getMasterRootPathForConfig(), Path.of("src/Main.java"));
        var files = Set.of(pfBuild, pfSrc);

        // Case A: exclusion "/build"
        var detailsSlash = new BuildAgent.BuildDetails("", "", "", new LinkedHashSet<>(List.of("/build")), Map.of());
        project.saveBuildDetails(detailsSlash);
        var filteredSlash = project.applyFiltering(files);
        assertTrue(filteredSlash.contains(pfSrc), "src/Main.java should remain");
        assertFalse(filteredSlash.contains(pfBuild), "build/Generated.java should be excluded by '/build'");

        // Case B: exclusion "build"
        var detailsNoSlash = new BuildAgent.BuildDetails("", "", "", new LinkedHashSet<>(List.of("build")), Map.of());
        project.saveBuildDetails(detailsNoSlash);
        var filteredNoSlash = project.applyFiltering(files);
        assertTrue(filteredNoSlash.contains(pfSrc), "src/Main.java should remain");
        assertFalse(filteredNoSlash.contains(pfBuild), "build/Generated.java should be excluded by 'build'");
    }
}
