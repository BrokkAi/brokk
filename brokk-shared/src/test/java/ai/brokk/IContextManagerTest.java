package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.CoreTestProject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IContextManagerTest {
    @Test
    void toFile_absoluteLikeLeadingSlash_mapsToProjectRelativeIfExists(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("foo"));
        Files.writeString(root.resolve("foo/bar.java"), "class X {}");

        var project = new CoreTestProject(root, java.util.Set.of());
        IContextManager cm = new TestContextManager(project);

        ProjectFile pf = cm.toFile("/foo/bar.java");
        assertEquals(Path.of("foo/bar.java"), pf.getRelPath());
    }

    @Test
    void toFile_absoluteLikeLeadingBackslash_mapsToProjectRelativeIfExists(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("foo"));
        Files.writeString(root.resolve("foo/bar.java"), "class X {}");

        var project = new CoreTestProject(root, java.util.Set.of());
        IContextManager cm = new TestContextManager(project);

        ProjectFile pf = cm.toFile("\\\\foo\\\\bar.java");
        assertEquals(Path.of("foo/bar.java"), pf.getRelPath());
    }

    @Test
    void toFile_windowsDriveAbsolute_mapsToProjectRelativeIfExists(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("foo"));
        Files.writeString(root.resolve("foo/bar.java"), "class X {}");

        var project = new CoreTestProject(root, java.util.Set.of());
        IContextManager cm = new TestContextManager(project);

        assertEquals(Path.of("foo/bar.java"), cm.toFile("C:\\\\foo\\\\bar.java").getRelPath());
        assertEquals(Path.of("foo/bar.java"), cm.toFile("C:/foo/bar.java").getRelPath());
    }

    @Test
    void toFile_absoluteLikeNonexistent_throws(@TempDir Path root) {
        var project = new CoreTestProject(root, java.util.Set.of());
        IContextManager cm = new TestContextManager(project);
        assertThrows(IllegalArgumentException.class, () -> cm.toFile("/does/not/exist.java"));
    }

    @Test
    void toFile_traversalOutsideProjectRoot_throws(@TempDir Path root) {
        var project = new CoreTestProject(root, java.util.Set.of());
        IContextManager cm = new TestContextManager(project);
        assertThrows(IllegalArgumentException.class, () -> cm.toFile("../evil.txt"));
        assertThrows(IllegalArgumentException.class, () -> cm.toFile("/../evil.txt"));
        assertThrows(IllegalArgumentException.class, () -> cm.toFile("C:\\\\..\\\\evil.txt"));
    }

    private static final class TestContextManager implements IContextManager {
        private final CoreTestProject project;

        private TestContextManager(CoreTestProject project) {
            this.project = project;
        }

        @Override
        public CoreTestProject getProject() {
            return project;
        }

        @Override
        public ai.brokk.analyzer.IAnalyzer getAnalyzerUninterrupted() {
            throw new UnsupportedOperationException();
        }
    }
}
