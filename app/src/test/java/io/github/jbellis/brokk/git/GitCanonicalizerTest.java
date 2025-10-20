package io.github.jbellis.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.git.GitRepo.Canonicalizer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GitCanonicalizerTest {
    private Path tempDir;

    @BeforeEach
    public void setup() throws Exception {
        tempDir = Files.createTempDirectory("brokk-rename-chain-");
    }

    @AfterEach
    public void cleanup() throws Exception {
        var gitDir = tempDir.resolve(".git");
        if (Files.exists(gitDir)) {
            try (var walk = Files.walk(gitDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
        if (Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private void write(String path, String content) throws IOException {
        Files.writeString(tempDir.resolve(path), content, StandardCharsets.UTF_8);
    }

    /** convenience for reading test files */
    private String read(String path) throws IOException {
        return Files.readString(tempDir.resolve(path), StandardCharsets.UTF_8);
    }

    @Test
    public void canonicalizerRespectsLineageAndIgnoresPathRecycling() throws Exception {
        String contentA = "class A { }";
        String contentSvc = "class Service { }";

        String contentTouch = "\n// touch";

        String c1;
        String c2;

        try (var git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var cfg = git.getRepository().getConfig();
            cfg.setString("user", null, "name", "Test User");
            cfg.setString("user", null, "email", "test@example.com");
            cfg.save();

            // C1: add A.java + Service.java (co-change)
            write("A.java", contentA);
            write("Service.java", contentSvc);
            git.add().addFilepattern("A.java").addFilepattern("Service.java").call();
            c1 = git.commit()
                    .setMessage("add A + service")
                    .setSign(false)
                    .call()
                    .getName();

            // C2: modify both again (ensure PMI sample includes this commit)
            write("A.java", read("A.java") + contentTouch);
            write("Service.java", read("Service.java") + contentTouch);
            git.add().addFilepattern("A.java").addFilepattern("Service.java").call();
            c2 = git.commit()
                    .setMessage("touch A + service")
                    .setSign(false)
                    .call()
                    .getName();

            // C3: rename A->B
            Files.move(tempDir.resolve("A.java"), tempDir.resolve("B.java"));
            git.rm().addFilepattern("A.java").call();
            git.add().addFilepattern("B.java").call();
            git.commit().setMessage("rename A->B").setSign(false).call();

            // C4: rename B->C
            Files.move(tempDir.resolve("B.java"), tempDir.resolve("C.java"));
            git.rm().addFilepattern("B.java").call();
            git.add().addFilepattern("C.java").call();
            git.commit().setMessage("rename B->C").setSign(false).call();

            // C5: add D
            write("D.java", "class D { }");
            git.add().addFilepattern("D.java").call();
            git.commit().setMessage("add D").setSign(false).call();

            // C6: rename D->B (path recycling)
            Files.move(tempDir.resolve("D.java"), tempDir.resolve("B.java"));
            git.rm().addFilepattern("D.java").call();
            git.add().addFilepattern("B.java").call();
            git.commit().setMessage("rename D->B").setSign(false).call();

            // C7: rename B->F
            Files.move(tempDir.resolve("B.java"), tempDir.resolve("F.java"));
            git.rm().addFilepattern("B.java").call();
            git.add().addFilepattern("F.java").call();
            git.commit().setMessage("rename B->F").setSign(false).call();
        }

        try (var repo = new GitRepo(tempDir)) {
            // PMI will sample commits that touched Service.java; assemble that set for the canonicalizer window
            var servicePf = new ProjectFile(tempDir, Path.of("Service.java"));
            var counted = repo.getFileHistories(List.of(servicePf), Integer.MAX_VALUE);
            assertTrue(counted.stream().anyMatch(ci -> ci.id().equals(c2)), "Expect c2 in the sample");

            // Build canonicalizer for the PMI sample and resolve "A.java as of C2"
            Canonicalizer rc = repo.buildCanonicalizer(counted);

            var aAtC2 = new ProjectFile(tempDir, Path.of("A.java"));
            var expectC = new ProjectFile(tempDir, Path.of("C.java"));
            var got = rc.canonicalize(c2, aAtC2);

            assertEquals(expectC, got, "A at C2 should canonicalize to C, not F");
            assertNotEquals(
                    new ProjectFile(tempDir, Path.of("F.java")), got, "Must not jump into later recycled B->F chain");
        }
    }
}
