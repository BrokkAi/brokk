package ai.brokk.context;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.IContextManager;
import ai.brokk.testutil.FileUtil;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.util.HistoryIo;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryIoV3CompatibilityTest {
    @TempDir
    Path tempDir;

    private IContextManager mockContextManager;
    private Path projectRoot;
    private Path zipPath;

    @BeforeEach
    void setup() throws IOException, URISyntaxException {
        projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        mockContextManager = new TestContextManager(projectRoot, new NoOpConsoleIO());

        var resourceUri = requireNonNull(
                        HistoryIoV3CompatibilityTest.class.getResource("/context-fragments/v3-small-complete"))
                .toURI();
        var resourcePath = Path.of(resourceUri);
        var staging = tempDir.resolve("staging");
        FileUtil.copyDirectory(resourcePath, staging);

        var jsonPath = staging.resolve("fragments-v3.json");
        String content = Files.readString(jsonPath, StandardCharsets.UTF_8);
        String sanitizedRoot = projectRoot.toString().replace('\\', '/');
        String updatedContent = content.replace("/Users/dave/Workspace/BrokkAi/brokk", sanitizedRoot);
        Files.writeString(jsonPath, updatedContent, StandardCharsets.UTF_8);

        FileUtil.createDummyFile(projectRoot, "app/src/main/java/io/github/jbellis/brokk/GitHubAuth.java");
        FileUtil.createDummyFile(projectRoot, "app/src/main/resources/icons/light/ai-robot.png");
        FileUtil.createDummyFile(projectRoot, "app/src/main/java/io/github/jbellis/brokk/AbstractProject.java");
        FileUtil.createDummyFile(projectRoot, "app/src/main/java/io/github/jbellis/brokk/IProject.java");

        zipPath = tempDir.resolve("history.zip");
        FileUtil.zipDirectory(staging, zipPath);
    }

    @Test
    void testReadV3Zip() throws IOException {
        ContextHistory history = HistoryIo.readZip(zipPath, mockContextManager);

        assertNotNull(history);
        assertFalse(history.getHistory().isEmpty());

        Context top = history.getLiveContext();

        var projectPathFragment = findFragment(top, ContextFragment.ProjectPathFragment.class, f -> f.description()
                .contains("GitHubAuth.java"));
        assertNotNull(projectPathFragment, "ProjectPathFragment for GitHubAuth.java should be present");

        var buildFragment = findFragment(
                top,
                ContextFragment.StringFragment.class,
                f -> "Source code for io.github.jbellis.brokk.Completions.expandPath".equals(f.description()));
        assertNotNull(buildFragment, "Migrated BuildFragment (as StringFragment) should be present");

        var imageFileFragment = findFragment(top, ContextFragment.ImageFileFragment.class, f -> f.description()
                .contains("ai-robot.png"));
        assertNotNull(imageFileFragment, "ImageFileFragment for ai-robot.png should be present");
        assertTrue(
                imageFileFragment.file().absPath().toString().endsWith("ai-robot.png"),
                "ImageFileFragment path should be correct");
    }

    /**
     * Helper function to find a ContextFragment of a specific type using a given predicate.
     *
     * @param context   the context to sift through.
     * @param type      the fragment type.
     * @param condition the predicate on which to map the fragment of type "type".
     * @param <T>       The ContextFragment type.
     * @return the fragment if found, null otherwise.
     */
    private @Nullable <T extends ContextFragment> T findFragment(
            Context context, Class<T> type, Predicate<T> condition) {
        return context.allFragments()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(condition)
                .findFirst()
                .orElse(null);
    }
}
