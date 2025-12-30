package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.ContextFragments.AbstractComputedFragment;
import ai.brokk.testutil.NoOpConsoleIO;
import ai.brokk.testutil.TestContextManager;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Comprehensive test suite documenting equality semantics for all fragment types.
 *
 * <p>This test suite explicitly verifies and documents the behavior of both `equals()` and
 * `hasSameSource()` for each fragment type. It serves as:
 * - A guardrail against regressions in equality semantics
 * - Living documentation of intended equality behavior
 * - A reference for fragment implementers
 *
 * <p><strong>Equality Semantics:</strong>
 * - `equals()`: Identity-based for most fragments (default Object behavior); content-hashed for
 *   content-addressed fragments
 * - `hasSameSource()`: Compares semantic source/origin; for files, uses normalized paths; for
 *   virtual fragments, uses `repr()` or content
 */
class FragmentEqualityTest {
    @TempDir
    Path tempDir;

    private ai.brokk.IContextManager contextManager;

    private final List<AbstractComputedFragment> trackedFragments = new ArrayList<>();

    @BeforeEach
    void setup() {
        contextManager = new TestContextManager(tempDir, new NoOpConsoleIO());
        ContextFragments.setMinimumId(1);
    }

    private <T extends AbstractComputedFragment> T track(T fragment) {
        trackedFragments.add(fragment);
        return fragment;
    }

    private BufferedImage createTestImage(Color color, int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    @Nested
    class ProjectPathFragmentEqualityTest {
        @Test
        void testEqualsIdentity() throws IOException {
            var file1 = new ProjectFile(tempDir, "src/File1.java");
            Files.createDirectories(file1.absPath().getParent());
            Files.writeString(file1.absPath(), "content");

            var frag1 = new ContextFragments.ProjectPathFragment(file1, contextManager);
            var frag2 = new ContextFragments.ProjectPathFragment(file1, contextManager);

            // Identity-based: different instances are NOT equal() (different numeric IDs)
            assertNotEquals(frag1, frag2);
            // But they have the same source
            assertTrue(frag1.hasSameSource(frag2));
        }

        @Test
        void testEqualsNullAndType() throws IOException {
            var file1 = new ProjectFile(tempDir, "src/File1.java");
            Files.createDirectories(file1.absPath().getParent());
            Files.writeString(file1.absPath(), "content");
            var frag1 = new ContextFragments.ProjectPathFragment(file1, contextManager);

            assertNotEquals(frag1, null);
            assertNotEquals(frag1, "not a fragment");
        }

        @Test
        void testHasSameSourceSamePath() throws IOException {
            var file1 = new ProjectFile(tempDir, "src/File1.java");
            Files.createDirectories(file1.absPath().getParent());
            Files.writeString(file1.absPath(), "content");

            var frag1 = new ContextFragments.ProjectPathFragment(file1, contextManager);
            var frag2 = new ContextFragments.ProjectPathFragment(file1, contextManager);

            // hasSameSource: compares normalized paths
            assertTrue(frag1.hasSameSource(frag2));
        }

        @Test
        void testHasSameSourceDifferentPaths() throws IOException {
            var file1 = new ProjectFile(tempDir, "src/File1.java");
            var file2 = new ProjectFile(tempDir, "src/File2.java");
            Files.createDirectories(file1.absPath().getParent());
            Files.writeString(file1.absPath(), "content1");
            Files.writeString(file2.absPath(), "content2");

            var frag1 = new ContextFragments.ProjectPathFragment(file1, contextManager);
            var frag2 = new ContextFragments.ProjectPathFragment(file2, contextManager);

            assertFalse(frag1.hasSameSource(frag2));
        }

        @Test
        void testHasSameSourceDifferentTypes() throws IOException {
            var file1 = new ProjectFile(tempDir, "src/File1.java");
            Files.createDirectories(file1.absPath().getParent());
            Files.writeString(file1.absPath(), "content");

            var ppf = new ContextFragments.ProjectPathFragment(file1, contextManager);
            var sf = new ContextFragments.StringFragment(
                    contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

            assertFalse(ppf.hasSameSource(sf));
        }
    }

    @Nested
    class ExternalPathFragmentEqualityTest {
        @Test
        void testEqualsIdentity() throws IOException {
            Path externalFile = tempDir.resolve("external.txt");
            Files.writeString(externalFile, "content");
            var file = new ExternalFile(externalFile);

            var frag1 = new ContextFragments.ExternalPathFragment(file, contextManager);
            var frag2 = new ContextFragments.ExternalPathFragment(file, contextManager);

            assertNotEquals(frag1, frag2);
        }

        @Test
        void testHasSameSourceSamePath() throws IOException {
            Path externalFile = tempDir.resolve("external.txt");
            Files.writeString(externalFile, "content");
            var file = new ExternalFile(externalFile);

            var frag1 = new ContextFragments.ExternalPathFragment(file, contextManager);
            var frag2 = new ContextFragments.ExternalPathFragment(file, contextManager);

            assertTrue(frag1.hasSameSource(frag2));
        }

        @Test
        void testHasSameSourceDifferentPaths() throws IOException {
            Path externalFile1 = tempDir.resolve("external1.txt");
            Path externalFile2 = tempDir.resolve("external2.txt");
            Files.writeString(externalFile1, "content1");
            Files.writeString(externalFile2, "content2");
            var file1 = new ExternalFile(externalFile1);
            var file2 = new ExternalFile(externalFile2);

            var frag1 = new ContextFragments.ExternalPathFragment(file1, contextManager);
            var frag2 = new ContextFragments.ExternalPathFragment(file2, contextManager);

            assertFalse(frag1.hasSameSource(frag2));
        }
    }

    @Nested
    class ImageFileFragmentEqualityTest {
        @Test
        void testEqualsIdentity() throws IOException {
            Path imageFile = tempDir.resolve("image.png");
            var testImage = createTestImage(Color.RED, 10, 10);
            var projectFile = new ProjectFile(tempDir, tempDir.relativize(imageFile));
            var iff1 = new ContextFragments.ImageFileFragment(projectFile, contextManager);
            var iff2 = new ContextFragments.ImageFileFragment(projectFile, contextManager);

            assertNotEquals(iff1, iff2);
        }

        @Test
        void testHasSameSourceSamePath() throws IOException {
            Path imageFile = tempDir.resolve("image.png");
            var projectFile = new ProjectFile(tempDir, tempDir.relativize(imageFile));

            var iff1 = new ContextFragments.ImageFileFragment(projectFile, contextManager);
            var iff2 = new ContextFragments.ImageFileFragment(projectFile, contextManager);

            assertTrue(iff1.hasSameSource(iff2));
        }

        @Test
        void testHasSameSourceDifferentPaths() throws IOException {
            Path imageFile1 = tempDir.resolve("image1.png");
            Path imageFile2 = tempDir.resolve("image2.png");
            var pf1 = new ProjectFile(tempDir, tempDir.relativize(imageFile1));
            var pf2 = new ProjectFile(tempDir, tempDir.relativize(imageFile2));

            var iff1 = new ContextFragments.ImageFileFragment(pf1, contextManager);
            var iff2 = new ContextFragments.ImageFileFragment(pf2, contextManager);

            assertFalse(iff1.hasSameSource(iff2));
        }
    }

    @Nested
    class GitFileFragmentEqualityTest {
        @Test
        void testEqualsIdentity() {
            var file = new ProjectFile(tempDir, "src/File.java");
            var gff1 = new ContextFragments.GitFileFragment(file, "abc123", "content1");
            var gff2 = new ContextFragments.GitFileFragment(file, "abc123", "content1");

            // Content-hashed: same file, revision, and content produce same ID
            assertEquals(gff1, gff2);
        }

        @Test
        void testEqualsDifferentRevisions() {
            var file = new ProjectFile(tempDir, "src/File.java");
            var gff1 = new ContextFragments.GitFileFragment(file, "abc123", "content1");
            var gff2 = new ContextFragments.GitFileFragment(file, "def456", "content1");

            assertNotEquals(gff1, gff2);
        }

        @Test
        void testEqualsDifferentContent() {
            var file = new ProjectFile(tempDir, "src/File.java");
            var gff1 = new ContextFragments.GitFileFragment(file, "abc123", "content1");
            var gff2 = new ContextFragments.GitFileFragment(file, "abc123", "content2");

            assertNotEquals(gff1, gff2);
        }

        @Test
        void testHasSameSourceSameFileAndRevision() throws IOException {
            var file = new ProjectFile(tempDir, "src/File.java");
            var gff1 = new ContextFragments.GitFileFragment(file, "abc123", "content1");
            var gff2 = new ContextFragments.GitFileFragment(file, "abc123", "content1");

            assertTrue(gff1.hasSameSource(gff2));
        }

        @Test
        void testHasSameSourceDifferentRevisions() throws IOException {
            var file = new ProjectFile(tempDir, "src/File.java");
            var gff1 = new ContextFragments.GitFileFragment(file, "abc123", "content1");
            var gff2 = new ContextFragments.GitFileFragment(file, "def456", "content1");

            assertFalse(gff1.hasSameSource(gff2));
        }
    }

    @Nested
    class StringFragmentEqualityTest {
        @Test
        void testEqualsIdenticalContent() {
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

            // Identity-based: different instances are NOT equal()
            assertNotEquals(sf1, sf2);
            // But they have the same content source
            assertTrue(sf1.hasSameSource(sf2));
        }

        @Test
        void testEqualsDifferentDescriptions() {
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc1", SyntaxConstants.SYNTAX_STYLE_NONE);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc2", SyntaxConstants.SYNTAX_STYLE_NONE);

            // Identity-based: different instances are NOT equal()
            assertNotEquals(sf1, sf2);
            // Different descriptions => different source
            assertFalse(sf1.hasSameSource(sf2));
        }

        @Test
        void testEqualsDifferentText() {
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "text1", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "text2", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

            assertNotEquals(sf1, sf2);
        }

        @Test
        void testEqualsDifferentSyntaxStyle() {
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_JAVA);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_PYTHON);

            assertNotEquals(sf1, sf2);
        }

        @Test
        void testHasSameSourceIdenticalContent() {
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc1", SyntaxConstants.SYNTAX_STYLE_NONE);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc2", SyntaxConstants.SYNTAX_STYLE_NONE);

            // hasSameSource now compares description (and syntax style), not text
            assertFalse(sf1.hasSameSource(sf2));
        }

        @Test
        void testHasSameSourceDifferentSyntaxStyle() {
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_JAVA);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_PYTHON);

            assertFalse(sf1.hasSameSource(sf2));
        }

        @Test
        void testHasSameSourceBuildResultsSystemFragment() {
            // Both use the hardcoded BUILD_RESULTS description
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "build output 1", "Latest Build Results", SyntaxConstants.SYNTAX_STYLE_NONE);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "build output 2", "Latest Build Results", SyntaxConstants.SYNTAX_STYLE_NONE);

            // Despite different text content, they have the same source because both map to BUILD_RESULTS
            assertTrue(sf1.hasSameSource(sf2));
        }

        @Test
        void testHasSameSourceSearchNotesSystemFragment() {
            // Both use the hardcoded SEARCH_NOTES description
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "notes 1", "Code Notes", SyntaxConstants.SYNTAX_STYLE_MARKDOWN);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "notes 2", "Code Notes", SyntaxConstants.SYNTAX_STYLE_MARKDOWN);

            // Despite different text content, they have the same source because both map to SEARCH_NOTES
            assertTrue(sf1.hasSameSource(sf2));
        }

        @Test
        void testHasSameSourceDiscardedContextSystemFragment() {
            // Both use the hardcoded DISCARDED_CONTEXT description
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "{\"key\": \"value1\"}", "Discarded Context", SyntaxConstants.SYNTAX_STYLE_JSON);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "{\"key\": \"value2\"}", "Discarded Context", SyntaxConstants.SYNTAX_STYLE_JSON);

            // Despite different text content, they have the same source because both map to DISCARDED_CONTEXT
            assertTrue(sf1.hasSameSource(sf2));
        }

        @Test
        void testHasSameSourceDifferentSystemFragments() {
            // One maps to BUILD_RESULTS, the other to SEARCH_NOTES
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "build output", "Latest Build Results", SyntaxConstants.SYNTAX_STYLE_NONE);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "notes", "Code Notes", SyntaxConstants.SYNTAX_STYLE_MARKDOWN);

            // Different system fragment types should not have same source
            assertFalse(sf1.hasSameSource(sf2));
        }

        @Test
        void testHasSameSourceOneSystemOneRegular() {
            // One maps to a system fragment type, the other does not
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "build output", "Latest Build Results", SyntaxConstants.SYNTAX_STYLE_NONE);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "custom text", "Custom Description", SyntaxConstants.SYNTAX_STYLE_NONE);

            // Mixed: one system, one regular should not have same source
            assertFalse(sf1.hasSameSource(sf2));
        }

        @Test
        void testHasSameSourceBothRegularFragments() {
            // Both have non-system content but different descriptions => different source
            var sf1 = new ContextFragments.StringFragment(
                    contextManager, "custom text", "Desc A", SyntaxConstants.SYNTAX_STYLE_NONE);
            var sf2 = new ContextFragments.StringFragment(
                    contextManager, "custom text", "Desc B", SyntaxConstants.SYNTAX_STYLE_NONE);

            // Description is treated as the source; different descriptions => not same source
            assertFalse(sf1.hasSameSource(sf2));
        }

        @Test
        void testHasSameSourceCompareAgainstNonStringFragment() {
            // StringFragment vs different fragment type should not match
            var sf = new ContextFragments.StringFragment(
                    contextManager, "text", "Latest Build Results", SyntaxConstants.SYNTAX_STYLE_NONE);
            var uf = track(new ContextFragments.UsageFragment(contextManager, "com.example.Class"));

            assertFalse(sf.hasSameSource(uf));
        }

        @Test
        void testDiffStringFragmentExposesFiles() throws IOException {
            var file1 = new ProjectFile(tempDir, "src/File1.java");
            var file2 = new ProjectFile(tempDir, "src/File2.java");
            Files.createDirectories(file1.absPath().getParent());
            Files.writeString(file1.absPath(), "class File1 {}");
            Files.writeString(file2.absPath(), "class File2 {}");

            var associatedFiles = Set.of(file1, file2);
            var sf = new ContextFragments.StringFragment(
                    contextManager,
                    "diff --git a/src/File1.java b/src/File1.java\n...",
                    "Diff of File1.java and File2.java",
                    SyntaxConstants.SYNTAX_STYLE_NONE,
                    associatedFiles);

            var filesFromFragment = sf.files().join();
            assertEquals(associatedFiles, filesFromFragment);
        }

        @Test
        void testDiffParsingSingleFileGitDiff() throws IOException {
            var file = new ProjectFile(tempDir, "src/GitDiffSingle.java");
            Files.createDirectories(file.absPath().getParent());
            Files.writeString(file.absPath(), "class GitDiffSingle {}");

            String diffText =
                    """
                    diff --git a/src/GitDiffSingle.java b/src/GitDiffSingle.java
                    index e69de29..4b825dc 100644
                    --- a/src/GitDiffSingle.java
                    +++ b/src/GitDiffSingle.java
                    @@ -1 +1 @@
                    -class GitDiffSingle {}
                    +class GitDiffSingle { }
                    """;

            var sf = new ContextFragments.StringFragment(
                    contextManager, diffText, "Git diff for GitDiffSingle.java", SyntaxConstants.SYNTAX_STYLE_NONE);

            var expectedPaths = Set.of(file.absPath().toString());
            var actualPaths = sf.files().join().stream()
                    .map(pf -> pf.absPath().toString())
                    .collect(Collectors.toSet());
            assertEquals(expectedPaths, actualPaths);
        }

        @Test
        void testDiffParsingMultiFileUnifiedDiff() throws IOException {
            var fileA = new ProjectFile(tempDir, "src/UnifiedA.java");
            var fileB = new ProjectFile(tempDir, "src/UnifiedB.java");
            Files.createDirectories(fileA.absPath().getParent());
            Files.writeString(fileA.absPath(), "class UnifiedA {}");
            Files.writeString(fileB.absPath(), "class UnifiedB {}");

            String diffText =
                    """
                    --- src/UnifiedA.java
                    +++ src/UnifiedA.java
                    @@ -1 +1 @@
                    -class UnifiedA {}
                    +class UnifiedA { }

                    --- src/UnifiedB.java
                    +++ src/UnifiedB.java
                    @@ -1 +1 @@
                    -class UnifiedB {}
                    +class UnifiedB { }
                    """;

            var sf = new ContextFragments.StringFragment(
                    contextManager,
                    diffText,
                    "Unified diff for UnifiedA.java and UnifiedB.java",
                    SyntaxConstants.SYNTAX_STYLE_NONE);

            var expectedPaths =
                    Set.of(fileA.absPath().toString(), fileB.absPath().toString());
            var actualPaths = sf.files().join().stream()
                    .map(pf -> pf.absPath().toString())
                    .collect(Collectors.toSet());
            assertEquals(expectedPaths, actualPaths);
        }

        @Test
        void testDiffParsingDeletionDiffWithDevNull() throws IOException {
            var file = new ProjectFile(tempDir, "src/Deleted.java");
            Files.createDirectories(file.absPath().getParent());
            Files.writeString(file.absPath(), "class Deleted {}");

            String diffText =
                    """
                    diff --git a/src/Deleted.java b/src/Deleted.java
                    deleted file mode 100644
                    index e69de29..0000000
                    --- a/src/Deleted.java
                    +++ /dev/null
                    @@ -1 +0,0 @@
                    -class Deleted {}
                    """;

            var sf = new ContextFragments.StringFragment(
                    contextManager, diffText, "Deletion diff for Deleted.java", SyntaxConstants.SYNTAX_STYLE_NONE);

            var expectedPaths = Set.of(file.absPath().toString());
            var actualPaths = sf.files().join().stream()
                    .map(pf -> pf.absPath().toString())
                    .collect(Collectors.toSet());
            assertEquals(expectedPaths, actualPaths);
        }

        @Test
        void testDiffParsingRenameDiffPrefersNewPath() throws IOException {
            var oldFile = new ProjectFile(tempDir, "src/RenamedOld.java");
            var newFile = new ProjectFile(tempDir, "src/RenamedNew.java");
            Files.createDirectories(oldFile.absPath().getParent());
            Files.writeString(oldFile.absPath(), "class RenamedOld {}");
            Files.writeString(newFile.absPath(), "class RenamedNew {}");

            String diffText =
                    """
                    diff --git a/src/RenamedOld.java b/src/RenamedNew.java
                    similarity index 100%
                    rename from src/RenamedOld.java
                    rename to src/RenamedNew.java
                    --- a/src/RenamedOld.java
                    +++ b/src/RenamedNew.java
                    @@ -1 +1 @@
                    -class RenamedOld {}
                    +class RenamedNew {}
                    """;

            var sf = new ContextFragments.StringFragment(
                    contextManager,
                    diffText,
                    "Rename diff from RenamedOld.java to RenamedNew.java",
                    SyntaxConstants.SYNTAX_STYLE_NONE);

            var expectedPaths = Set.of(newFile.absPath().toString());
            var actualPaths = sf.files().join().stream()
                    .map(pf -> pf.absPath().toString())
                    .collect(Collectors.toSet());
            assertEquals(expectedPaths, actualPaths);
        }

        @Test
        void testDiffParsingNonDiffTextHasNoFiles() {
            var sf = new ContextFragments.StringFragment(
                    contextManager,
                    "This is not a diff\nJust some plain text.",
                    "Plain text",
                    SyntaxConstants.SYNTAX_STYLE_NONE);

            assertTrue(sf.files().join().isEmpty());
        }
    }

    @Nested
    class PasteTextFragmentEqualityTest {
        @Test
        void testEqualsIdenticalContent() {
            var ptf1 = new ContextFragments.PasteTextFragment(
                    contextManager,
                    "text",
                    CompletableFuture.completedFuture("desc1"),
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));
            var ptf2 = new ContextFragments.PasteTextFragment(
                    contextManager,
                    "text",
                    CompletableFuture.completedFuture("desc2"),
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));

            // Identity-based: different instances are NOT equal()
            assertNotEquals(ptf1, ptf2);
            // But they have the same text content
            assertTrue(ptf1.hasSameSource(ptf2));
        }

        @Test
        void testEqualsDifferentText() {
            var ptf1 = new ContextFragments.PasteTextFragment(
                    contextManager,
                    "text1",
                    CompletableFuture.completedFuture("desc"),
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));
            var ptf2 = new ContextFragments.PasteTextFragment(
                    contextManager,
                    "text2",
                    CompletableFuture.completedFuture("desc"),
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));

            assertNotEquals(ptf1, ptf2);
        }

        @Test
        void testHasSameSourceIdenticalText() {
            var ptf1 = new ContextFragments.PasteTextFragment(
                    contextManager,
                    "text",
                    CompletableFuture.completedFuture("desc1"),
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));
            var ptf2 = new ContextFragments.PasteTextFragment(
                    contextManager,
                    "text",
                    CompletableFuture.completedFuture("desc2"),
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));

            // hasSameSource compares text content
            assertTrue(ptf1.hasSameSource(ptf2));
        }

        @Test
        void testHasSameSourceDifferentText() {
            var ptf1 = new ContextFragments.PasteTextFragment(
                    contextManager,
                    "text1",
                    CompletableFuture.completedFuture("desc"),
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));
            var ptf2 = new ContextFragments.PasteTextFragment(
                    contextManager,
                    "text2",
                    CompletableFuture.completedFuture("desc"),
                    CompletableFuture.completedFuture(SyntaxConstants.SYNTAX_STYLE_NONE));

            assertFalse(ptf1.hasSameSource(ptf2));
        }
    }

    @Nested
    class AnonymousImageFragmentEqualityTest {
        @Test
        void testEqualsIdenticalImage() {
            var image1 = createTestImage(Color.RED, 10, 10);
            var aif1 = new ContextFragments.AnonymousImageFragment(
                    contextManager, image1, CompletableFuture.completedFuture("desc1"));
            var aif2 = new ContextFragments.AnonymousImageFragment(
                    contextManager, image1, CompletableFuture.completedFuture("desc2"));

            // Identity-based: different instances are NOT equal()
            assertNotEquals(aif1, aif2);
            // But they have the same image content
            assertTrue(aif1.hasSameSource(aif2));
        }

        @Test
        void testEqualsDifferentImages() {
            var image1 = createTestImage(Color.RED, 10, 10);
            var image2 = createTestImage(Color.BLUE, 10, 10);
            var aif1 = new ContextFragments.AnonymousImageFragment(
                    contextManager, image1, CompletableFuture.completedFuture("desc"));
            var aif2 = new ContextFragments.AnonymousImageFragment(
                    contextManager, image2, CompletableFuture.completedFuture("desc"));

            assertNotEquals(aif1, aif2);
        }

        @Test
        void testHasSameSourceIdenticalImage() {
            var image1 = createTestImage(Color.RED, 10, 10);
            var aif1 = new ContextFragments.AnonymousImageFragment(
                    contextManager, image1, CompletableFuture.completedFuture("desc1"));
            var aif2 = new ContextFragments.AnonymousImageFragment(
                    contextManager, image1, CompletableFuture.completedFuture("desc2"));

            // hasSameSource uses contentHash (ID)
            assertTrue(aif1.hasSameSource(aif2));
        }

        @Test
        void testHasSameSourceDifferentImages() {
            var image1 = createTestImage(Color.RED, 10, 10);
            var image2 = createTestImage(Color.BLUE, 10, 10);
            var aif1 = new ContextFragments.AnonymousImageFragment(
                    contextManager, image1, CompletableFuture.completedFuture("desc"));
            var aif2 = new ContextFragments.AnonymousImageFragment(
                    contextManager, image2, CompletableFuture.completedFuture("desc"));

            assertFalse(aif1.hasSameSource(aif2));
        }
    }

    @Nested
    class UsageFragmentEqualityTest {
        @Test
        void testEqualsIdentity() {
            var uf1 = track(new ContextFragments.UsageFragment(contextManager, "com.example.Class"));
            var uf2 = track(new ContextFragments.UsageFragment(contextManager, "com.example.Class"));

            // Identity-based: different instances are NOT equal
            assertNotEquals(uf1, uf2);
        }

        @Test
        void testHasSameSourceRepr() {
            var uf1 = track(new ContextFragments.UsageFragment(contextManager, "com.example.Class"));
            var uf2 = track(new ContextFragments.UsageFragment(contextManager, "com.example.Class"));

            // hasSameSource compares repr()
            assertTrue(uf1.hasSameSource(uf2));
        }

        @Test
        void testHasSameSourceDifferentTargets() {
            var uf1 = track(new ContextFragments.UsageFragment(contextManager, "com.example.Class1"));
            var uf2 = track(new ContextFragments.UsageFragment(contextManager, "com.example.Class2"));

            assertFalse(uf1.hasSameSource(uf2));
        }

        @Test
        void testHasSameSourceDifferentIncludeTestFiles() {
            var uf1 = track(new ContextFragments.UsageFragment(contextManager, "com.example.Class", true));
            var uf2 = track(new ContextFragments.UsageFragment(contextManager, "com.example.Class", false));

            // Different includeTestFiles flags produce different repr()
            assertFalse(uf1.hasSameSource(uf2));
        }
    }

    @Nested
    class CodeFragmentEqualityTest {
        private CodeUnit createTestCodeUnit(String fqName) {
            var file = new ProjectFile(tempDir, "Test.java");
            String shortName = fqName.substring(fqName.lastIndexOf('.') + 1);
            String packageName = fqName.contains(".") ? fqName.substring(0, fqName.lastIndexOf('.')) : "";
            return new CodeUnit(file, CodeUnitType.CLASS, packageName, shortName);
        }

        @Test
        void testEqualsIdentity() {
            var unit = createTestCodeUnit("com.example.Test");
            var cf1 = new ContextFragments.CodeFragment(contextManager, unit);
            var cf2 = new ContextFragments.CodeFragment(contextManager, unit);

            assertNotEquals(cf1, cf2);
        }

        @Test
        void testHasSameSourceSameUnit() {
            var unit = createTestCodeUnit("com.example.Test");
            var cf1 = new ContextFragments.CodeFragment(contextManager, unit);
            var cf2 = new ContextFragments.CodeFragment(contextManager, unit);

            assertTrue(cf1.hasSameSource(cf2));
        }

        @Test
        void testHasSameSourceDifferentUnits() {
            var unit1 = createTestCodeUnit("com.example.Test1");
            var unit2 = createTestCodeUnit("com.example.Test2");
            var cf1 = new ContextFragments.CodeFragment(contextManager, unit1);
            var cf2 = new ContextFragments.CodeFragment(contextManager, unit2);

            assertFalse(cf1.hasSameSource(cf2));
        }
    }

    @Nested
    class CallGraphFragmentEqualityTest {
        @Test
        void testEqualsIdentity() {
            var cgf1 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method", 2, true);
            var cgf2 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method", 2, true);

            assertNotEquals(cgf1, cgf2);
        }

        @Test
        void testHasSameSourceSameParams() {
            var cgf1 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method", 2, true);
            var cgf2 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method", 2, true);

            assertTrue(cgf1.hasSameSource(cgf2));
        }

        @Test
        void testHasSameSourceDifferentMethods() {
            var cgf1 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method1", 2, true);
            var cgf2 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method2", 2, true);

            assertFalse(cgf1.hasSameSource(cgf2));
        }

        @Test
        void testHasSameSourceDifferentDepth() {
            var cgf1 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method", 2, true);
            var cgf2 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method", 3, true);

            assertFalse(cgf1.hasSameSource(cgf2));
        }

        @Test
        void testHasSameSourceDifferentDirection() {
            var cgf1 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method", 2, true);
            var cgf2 = new ContextFragments.CallGraphFragment(contextManager, "com.example.method", 2, false);

            assertFalse(cgf1.hasSameSource(cgf2));
        }
    }

    @Nested
    class TaskFragmentEqualityTest {
        @Test
        void testEqualsIdenticalMessages() {
            var messages = List.<ChatMessage>of(UserMessage.from("user"), AiMessage.from("ai"));
            var tf1 = new ContextFragments.TaskFragment(contextManager, messages, "session");
            var tf2 = new ContextFragments.TaskFragment(contextManager, messages, "session");

            // Identity-based: different instances are NOT equal()
            assertNotEquals(tf1, tf2);
            // But they represent the same session content
            assertTrue(tf1.hasSameSource(tf2));
        }

        @Test
        void testEqualsDifferentMessages() {
            var messages1 = List.<ChatMessage>of(UserMessage.from("user1"));
            var messages2 = List.<ChatMessage>of(UserMessage.from("user2"));
            var tf1 = new ContextFragments.TaskFragment(contextManager, messages1, "session");
            var tf2 = new ContextFragments.TaskFragment(contextManager, messages2, "session");

            assertNotEquals(tf1, tf2);
        }

        @Test
        void testEqualsDifferentSession() {
            var messages = List.<ChatMessage>of(UserMessage.from("user"));
            var tf1 = new ContextFragments.TaskFragment(contextManager, messages, "session1");
            var tf2 = new ContextFragments.TaskFragment(contextManager, messages, "session2");

            assertNotEquals(tf1, tf2);
        }
    }

    @Nested
    class StacktraceFragmentEqualityTest {
        private CodeUnit createTestCodeUnit(String fqName) {
            var file = new ProjectFile(tempDir, "Test.java");
            String shortName = fqName.substring(fqName.lastIndexOf('.') + 1);
            String packageName = fqName.contains(".") ? fqName.substring(0, fqName.lastIndexOf('.')) : "";
            return new CodeUnit(file, CodeUnitType.CLASS, packageName, shortName);
        }

        @Test
        void testEqualsIdenticalContent() {
            var sources = Set.of(createTestCodeUnit("com.example.Error"));
            var sf1 =
                    new ContextFragments.StacktraceFragment(contextManager, sources, "stacktrace", "Exception", "code");
            var sf2 =
                    new ContextFragments.StacktraceFragment(contextManager, sources, "stacktrace", "Exception", "code");

            // Identity-based: different instances are NOT equal()
            assertNotEquals(sf1, sf2);
            // But they represent the same stacktrace content
            assertTrue(sf1.hasSameSource(sf2));
        }

        @Test
        void testEqualsDifferentExceptions() {
            var sources = Set.of(createTestCodeUnit("com.example.Error"));
            var sf1 = new ContextFragments.StacktraceFragment(
                    contextManager, sources, "stacktrace", "Exception1", "code");
            var sf2 = new ContextFragments.StacktraceFragment(
                    contextManager, sources, "stacktrace", "Exception2", "code");

            assertNotEquals(sf1, sf2);
        }
    }

    @Nested
    class SummaryFragmentEqualityTest {
        @Test
        void testEqualsIdentity() {
            var sumf1 = new ContextFragments.SummaryFragment(
                    contextManager, "com.example.Class", ContextFragment.SummaryType.CODEUNIT_SKELETON);
            var sumf2 = new ContextFragments.SummaryFragment(
                    contextManager, "com.example.Class", ContextFragment.SummaryType.CODEUNIT_SKELETON);

            assertNotEquals(sumf1, sumf2);
        }

        @Test
        void testHasSameSourceSameTarget() {
            var sumf1 = new ContextFragments.SummaryFragment(
                    contextManager, "com.example.Class", ContextFragment.SummaryType.CODEUNIT_SKELETON);
            var sumf2 = new ContextFragments.SummaryFragment(
                    contextManager, "com.example.Class", ContextFragment.SummaryType.CODEUNIT_SKELETON);

            assertTrue(sumf1.hasSameSource(sumf2));
        }

        @Test
        void testHasSameSourceDifferentTargets() {
            var sumf1 = new ContextFragments.SummaryFragment(
                    contextManager, "com.example.Class1", ContextFragment.SummaryType.CODEUNIT_SKELETON);
            var sumf2 = new ContextFragments.SummaryFragment(
                    contextManager, "com.example.Class2", ContextFragment.SummaryType.CODEUNIT_SKELETON);

            assertFalse(sumf1.hasSameSource(sumf2));
        }

        @Test
        void testHasSameSourceDifferentSummaryTypes() {
            var sumf1 = new ContextFragments.SummaryFragment(
                    contextManager, "com.example.Class", ContextFragment.SummaryType.CODEUNIT_SKELETON);
            var sumf2 = new ContextFragments.SummaryFragment(
                    contextManager, "com.example.Class", ContextFragment.SummaryType.FILE_SKELETONS);

            assertFalse(sumf1.hasSameSource(sumf2));
        }
    }

    @Nested
    class HistoryFragmentEqualityTest {
        @Test
        void testEqualsIdenticalHistory() {
            var messages = List.<ChatMessage>of(UserMessage.from("user"));
            var tf = new ContextFragments.TaskFragment(contextManager, messages, "session");
            var te = new ai.brokk.TaskEntry(1, tf, null);
            var hf1 = new ContextFragments.HistoryFragment(contextManager, List.of(te));
            var hf2 = new ContextFragments.HistoryFragment(contextManager, List.of(te));

            // Identity-based: different instances are NOT equal()
            assertNotEquals(hf1, hf2);
            // But they represent the same history content
            assertTrue(hf1.hasSameSource(hf2));
        }

        @Test
        void testEqualsDifferentHistory() {
            var messages1 = List.<ChatMessage>of(UserMessage.from("user1"));
            var messages2 = List.<ChatMessage>of(UserMessage.from("user2"));
            var tf1 = new ContextFragments.TaskFragment(contextManager, messages1, "session");
            var tf2 = new ContextFragments.TaskFragment(contextManager, messages2, "session");
            var te1 = new ai.brokk.TaskEntry(1, tf1, null);
            var te2 = new ai.brokk.TaskEntry(1, tf2, null);
            var hf1 = new ContextFragments.HistoryFragment(contextManager, List.of(te1));
            var hf2 = new ContextFragments.HistoryFragment(contextManager, List.of(te2));

            assertNotEquals(hf1, hf2);
        }
    }

    @Nested
    class CrossTypeEqualityTest {
        @Test
        void testStringVsCodeFragment() {
            var sf = new ContextFragments.StringFragment(
                    contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);
            var unit = new CodeUnit(new ProjectFile(tempDir, "Test.java"), CodeUnitType.CLASS, "com.example", "Test");
            var cf = new ContextFragments.CodeFragment(contextManager, unit);

            assertFalse(sf.hasSameSource(cf));
        }

        @Test
        void testNullComparison() {
            var sf = new ContextFragments.StringFragment(
                    contextManager, "text", "desc", SyntaxConstants.SYNTAX_STYLE_NONE);

            assertFalse(sf.hasSameSource(null));
        }
    }

    /**
     * UsageFragment's computed value can cause Llm to write an llm-history directory even though we're
     * passing an empty analyzer. This waits for those tasks to finish before letting JUnit try to clean up;
     * otherwise it will throw if it loses the race and llm-history gets created after it does its "remove everything"
     * pass but before it runs rmdir.
     */
    @AfterEach
    void awaitTrackedFragments() throws InterruptedException {
        for (AbstractComputedFragment fragment : trackedFragments) {
            fragment.await(Duration.ofSeconds(5));
        }
        trackedFragments.clear();
    }
}
