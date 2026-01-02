package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.AbstractProject;
import ai.brokk.project.MainProject;
import ai.brokk.util.AtomicWrites;
import ai.brokk.util.PathNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
                absUnder.toString() // absolute under project, becomes relative
                ));

        var project = new MainProject(root);

        var details = new BuildAgent.BuildDetails("", "", "", rawExcludesOrdered);

        // Act: save and read back from properties
        project.saveBuildDetails(details);

        Properties props1 = loadProps(brokkProps(root));
        String json1 = props1.getProperty("buildDetailsJson");
        assertNotNull(json1, "buildDetailsJson should be persisted");

        var parsed1 = parseDetailsFromProps(props1);

        // Assert: canonicalized exclusions in order
        var expectedCanonicalOrder = List.of("bin", "gradle", "build", "subdir/vendor", "/nbdist", "absUnder");
        assertEquals(expectedCanonicalOrder.size(), parsed1.exclusionPatterns().size(), "Unexpected excludes size");
        assertIterableEquals(
                expectedCanonicalOrder, parsed1.exclusionPatterns(), "Exclusions not canonicalized as expected");

        // Assert: JSON stability across subsequent save of canonical content
        project.saveBuildDetails(parsed1);
        Properties props2 = loadProps(brokkProps(root));
        String json2 = props2.getProperty("buildDetailsJson");
        assertEquals(json1, json2, "buildDetailsJson should be stable across saves");
    }

    @Test
    void testLegacyJson_migratesExcludedDirectories() throws Exception {
        // Old JSON format with excludedDirectories - should migrate to exclusionPatterns
        String oldJson =
                """
            {"buildLintCommand":"mvn compile","testAllCommand":"mvn test","testSomeCommand":"",\
            "excludedDirectories":["target","build"],"environmentVariables":{}}
            """;

        var details = MAPPER.readValue(oldJson, BuildAgent.BuildDetails.class);

        // Legacy excludedDirectories should be migrated to exclusionPatterns
        assertEquals("mvn compile", details.buildLintCommand());
        assertEquals(Set.of("target", "build"), details.exclusionPatterns());
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

        var legacyDetails = new BuildAgent.BuildDetails("", "", "", new LinkedHashSet<>(legacyExcludes));
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
        assertEquals(expectedCanonical, loaded.exclusionPatterns(), "Loaded exclusions should be canonicalized");

        // Act: save back and ensure canonical JSON now persisted
        project.saveBuildDetails(loaded);
        Properties props2 = loadProps(propsFile);
        var persisted = parseDetailsFromProps(props2);
        assertEquals(expectedCanonical, persisted.exclusionPatterns(), "Persisted exclusions should be canonical");

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
        var detailsSlash = new BuildAgent.BuildDetails("", "", "", new LinkedHashSet<>(List.of("/build")));
        project.saveBuildDetails(detailsSlash);
        var filteredSlash = project.applyFiltering(files);
        assertTrue(filteredSlash.contains(pfSrc), "src/Main.java should remain");
        assertFalse(filteredSlash.contains(pfBuild), "build/Generated.java should be excluded by '/build'");

        // Case B: exclusion "build"
        var detailsNoSlash = new BuildAgent.BuildDetails("", "", "", new LinkedHashSet<>(List.of("build")));
        project.saveBuildDetails(detailsNoSlash);
        var filteredNoSlash = project.applyFiltering(files);
        assertTrue(filteredNoSlash.contains(pfSrc), "src/Main.java should remain");
        assertFalse(filteredNoSlash.contains(pfBuild), "build/Generated.java should be excluded by 'build'");
    }

    /**
     * Verify that path-based exclusion patterns (containing /) match both the directory
     * and all files/subdirectories underneath it.
     */
    @Test
    void testJavaHomeMigrationAndNonPersistence(@TempDir Path root) throws Exception {
        // 1. Arrange: Write .brokk/project.properties with JAVA_HOME in buildDetailsJson
        Path propsFile = brokkProps(root);
        Files.createDirectories(propsFile.getParent());

        String jdkPath = root.resolve("mock-jdk").toString();
        Map<String, String> envVars = new HashMap<>();
        envVars.put("JAVA_HOME", jdkPath);
        envVars.put("OTHER_VAR", "value");

        BuildAgent.BuildDetails legacyDetails =
                new BuildAgent.BuildDetails("mvn compile", "mvn test", "", Set.of(), envVars);

        Properties projectProps = new Properties();
        projectProps.setProperty("buildDetailsJson", MAPPER.writeValueAsString(legacyDetails));
        AtomicWrites.atomicSaveProperties(propsFile, projectProps, "migration test");

        // 2. Act: Load details via MainProject
        MainProject project = new MainProject(root);
        BuildAgent.BuildDetails loadedDetails = project.loadBuildDetails();

        // 3. Assert: .brokk/workspace.properties now contains jdk.home
        Path workspacePropsFile = root.resolve(".brokk/workspace.properties");
        assertTrue(Files.exists(workspacePropsFile), "workspace.properties should be created");
        Properties wsProps = loadProps(workspacePropsFile);
        String expectedJdkHome = PathNormalizer.canonicalizeEnvPathValue(jdkPath);
        assertEquals(expectedJdkHome, wsProps.getProperty("jdk.home"), "jdk.home should be migrated to workspace.properties");

        // 4. Assert: Loaded details do NOT contain JAVA_HOME in environmentVariables map
        assertFalse(
                loadedDetails.environmentVariables().containsKey("JAVA_HOME"),
                "Loaded details should strip JAVA_HOME from environmentVariables");
        assertEquals("value", loadedDetails.environmentVariables().get("OTHER_VAR"));

        // 5. Act: Save details
        project.saveBuildDetails(loadedDetails);

        // 6. Assert: .brokk/project.properties rewritten without JAVA_HOME in buildDetailsJson
        Properties updatedProjectProps = loadProps(propsFile);
        BuildAgent.BuildDetails persistedDetails = parseDetailsFromProps(updatedProjectProps);
        assertFalse(
                persistedDetails.environmentVariables().containsKey("JAVA_HOME"),
                "Persisted JSON should not contain JAVA_HOME");
    }

    @Test
    void testApplyFiltering_pathPrefixMatchesSubdirectories(@TempDir Path root) throws Exception {
        // Initialize a real Git repo so filtering path is exercised
        Git.init().setDirectory(root.toFile()).call();

        // Create files in app/src/test/resources hierarchy
        Path resourcesDir = root.resolve("app/src/test/resources");
        Files.createDirectories(resourcesDir);
        Path resourceFile = resourcesDir.resolve("test-data.json");
        Files.writeString(resourceFile, "{}");

        Path nestedDir = resourcesDir.resolve("testcode-java");
        Files.createDirectories(nestedDir);
        Path nestedFile = nestedDir.resolve("Sample.java");
        Files.writeString(nestedFile, "class Sample {}");

        Path srcFile = root.resolve("app/src/main/java/Main.java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, "class Main {}");

        var project = new MainProject(root);

        // Create ProjectFile instances
        var pfResources = new ProjectFile(root, Path.of("app/src/test/resources/test-data.json"));
        var pfNested = new ProjectFile(root, Path.of("app/src/test/resources/testcode-java/Sample.java"));
        var pfMain = new ProjectFile(root, Path.of("app/src/main/java/Main.java"));
        var files = Set.of(pfResources, pfNested, pfMain);

        // Exclude "app/src/test/resources" - should exclude both files under it
        var details = new BuildAgent.BuildDetails("", "", "", new LinkedHashSet<>(List.of("app/src/test/resources")));
        project.saveBuildDetails(details);
        var filtered = project.applyFiltering(files);

        assertTrue(filtered.contains(pfMain), "Main.java should remain");
        assertFalse(filtered.contains(pfResources), "test-data.json should be excluded by path prefix");
        assertFalse(filtered.contains(pfNested), "testcode-java/Sample.java should be excluded by path prefix");
    }
}
