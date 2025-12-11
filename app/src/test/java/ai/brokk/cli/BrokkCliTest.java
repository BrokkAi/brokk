package ai.brokk.cli;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.agents.ContextAgent;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for BrokkCli context cache helper methods (write/read).
 *
 * The production methods are private, so we use reflection to invoke them.
 * We use an empty recommendation (no fragments) to avoid having to provide
 * fragment DTOs or a populated ContextManager.
 */
public class BrokkCliTest {

    @Test
    void testContextCacheWriteReadEmpty(@TempDir Path tempHome) throws Exception {
        String originalHome = System.getProperty("user.home");
        try {
            // Point user.home to a temporary directory so we don't touch the real cache.
            System.setProperty("user.home", tempHome.toString());

            // Prepare a simple RecommendationResult: empty fragments, no metadata
            ContextAgent.RecommendationResult rec =
                    new ContextAgent.RecommendationResult(true, List.of(), "reasoning-text", null);

            String key = "brokk-cli-test-key";
            BrokkCli.writeRecommendationToCache();

            Optional<ContextAgent.RecommendationResult> maybe = BrokkCli.readRecommendationFromCache();
            assertTrue(maybe.isPresent(), "Cached recommendation should be present");
            ContextAgent.RecommendationResult loaded = maybe.get();

            assertEquals(rec.success(), loaded.success(), "success flag should match");
            assertEquals("", loaded.reasoning(), "reasoning should be empty");
            assertNotNull(loaded.fragments(), "fragments list should not be null");
            assertEquals(0, loaded.fragments().size(), "fragments should be empty");
            assertNull(loaded.metadata(), "metadata should be null");

            // Also assert the cache file exists in the expected cache directory
            Method getCaCacheDir = BrokkCli.class.getDeclaredMethod("getCaCacheDir");
            getCaCacheDir.setAccessible(true);
            Path cacheDir = (Path) getCaCacheDir.invoke(null);
            assertTrue(Files.exists(cacheDir), "cache directory should exist");
            assertTrue(Files.exists(cacheDir.resolve(key + ".zip")), "cache zip file should exist");

        } finally {
            // Restore original user.home
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    @Test
    void testContextCacheWriteReadWithFragments(@TempDir Path tempHome, @TempDir Path tempProject) throws Exception {
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempHome.toString());

            // Create a minimal project structure
            Files.createDirectories(tempProject.resolve("src"));

            // Build test context manager and fragments
            var tcm = new TestContextManager(tempProject, new TestConsoleIO());

            ProjectFile pf = new ProjectFile(tempProject, "src/Main.java");
            // Ensure ProjectFile exists and has content
            pf.create();
            pf.write("class Main {}");

            ContextFragment.ProjectPathFragment pfrag = new ContextFragment.ProjectPathFragment(pf, tcm);
            ContextFragment.SummaryFragment sfrag = new ContextFragment.SummaryFragment(
                    tcm, "com.example.Main", ContextFragment.SummaryType.CODEUNIT_SKELETON);

            // Write and read via static API
            ContextAgent.RecommendationResult rec =
                    new ContextAgent.RecommendationResult(true, List.of(pfrag, sfrag), "ignored", null);

            String key = "brokk-cli-test-fragments";
            BrokkCli.writeRecommendationToCache();

            Optional<ContextAgent.RecommendationResult> maybe = BrokkCli.readRecommendationFromCache();
            assertTrue(maybe.isPresent(), "Cached recommendation should be present");
            ContextAgent.RecommendationResult loaded = maybe.get();

            // success is always true; reasoning is always empty for cached values
            assertTrue(loaded.success(), "success should be true");
            assertEquals("", loaded.reasoning(), "reasoning should be empty");

            assertEquals(2, loaded.fragments().size(), "should load both fragments");

            // Validate ProjectPathFragment round-trip
            ContextFragment first = loaded.fragments().get(0);
            assertInstanceOf(
                    ContextFragment.ProjectPathFragment.class, first, "first fragment should be a ProjectPathFragment");
            var loadedPfFrag = (ContextFragment.ProjectPathFragment) first;
            assertEquals(pf, loadedPfFrag.file(), "project file should round-trip");

            // Validate SummaryFragment round-trip
            ContextFragment second = loaded.fragments().get(1);
            assertInstanceOf(
                    ContextFragment.SummaryFragment.class, second, "second fragment should be a SummaryFragment");
            var loadedSumFrag = (ContextFragment.SummaryFragment) second;
            assertEquals("com.example.Main", loadedSumFrag.getTargetIdentifier());
            assertEquals(ContextFragment.SummaryType.CODEUNIT_SKELETON, loadedSumFrag.getSummaryType());

            // Ensure cache file exists
            Method getCaCacheDir = BrokkCli.class.getDeclaredMethod("getCaCacheDir");
            getCaCacheDir.setAccessible(true);
            Path cacheDir = (Path) getCaCacheDir.invoke(null);
            assertTrue(Files.exists(cacheDir.resolve(key + ".zip")), "cache zip file should exist");
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }
}
