package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.DisabledAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalyzerUtilTest {
    @TempDir
    Path tempDir;

    @Test
    void processUsages_RendersFieldParentSkeleton() {
        var file = new ProjectFile(tempDir, "Target.java");
        var targetClass = CodeUnit.cls(file, "com.example", "Target");
        var targetField = CodeUnit.field(file, "com.example", "Target.field");
        var analyzer = new DisabledAnalyzer() {
            @Override
            public Optional<CodeUnit> parentOf(CodeUnit cu) {
                if (targetField.equals(cu)) {
                    return Optional.of(targetClass);
                }
                return Optional.empty();
            }

            @Override
            public Optional<String> getSkeletonHeader(CodeUnit classUnit) {
                if (targetClass.equals(classUnit)) {
                    return Optional.of("public class Target { public String field; }");
                }
                return Optional.empty();
            }
        };

        var rendered = AnalyzerUtil.processUsages(analyzer, List.of(targetField));

        assertEquals(1, rendered.size());
        assertEquals(
                "public class Target { public String field; }",
                rendered.getFirst().code());
        assertEquals(targetClass, rendered.getFirst().source());
        assertTrue(AnalyzerUtil.CodeWithSource.text(analyzer, rendered)
                .contains("public class Target { public String field; }"));
    }
}
