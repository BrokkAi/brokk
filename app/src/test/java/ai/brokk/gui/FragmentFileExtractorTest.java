package ai.brokk.gui;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragment;
import ai.brokk.testutil.TestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FragmentFileExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void testExtractProjectFile_FromProjectPathFragment() throws Exception {
        // Create a test project with a file
        var projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("Test.java"), "public class Test {}");

        var project = new TestProject(projectRoot, Languages.JAVA);
        var projectFile = new ProjectFile(projectRoot, Path.of("Test.java"));

        // Create a ProjectPathFragment
        var fragment = new ContextFragment.ProjectPathFragment(projectFile, null);

        // Extract the file
        ProjectFile extracted = FragmentFileExtractor.extractProjectFile(fragment);

        assertNotNull(extracted, "Should extract ProjectFile from ProjectPathFragment");
        assertEquals(projectFile, extracted, "Should return the same ProjectFile");
    }

    // Note: Testing with non-PathFragment is difficult without mocking or complex setup
    // The method handles this case by checking instanceof PathFragment,
    // which is verified by the null test below

    @Test
    void testExtractProjectFile_FromPathFragmentWithNonProjectFile() throws Exception {
        // Create an ExternalFile (not a ProjectFile)
        var externalFile = tempDir.resolve("external.txt");
        Files.writeString(externalFile, "External content");

        var externalBrokkFile = new ExternalFile(externalFile);
        var fragment = new ContextFragment.ExternalPathFragment(externalBrokkFile, null);

        ProjectFile extracted = FragmentFileExtractor.extractProjectFile(fragment);

        assertNull(extracted, "Should return null when PathFragment contains ExternalFile, not ProjectFile");
    }

    @Test
    void testExtractProjectFile_WithNullFragment() {
        // Edge case: what if someone passes null?
        // The method should handle it gracefully (it does instanceof check first)
        // Actually, instanceof null is false, so it returns null
        assertNull(FragmentFileExtractor.extractProjectFile(null), "Should return null when fragment is null");
    }
}
