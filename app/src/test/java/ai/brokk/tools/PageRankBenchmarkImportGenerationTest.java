package ai.brokk.tools;

import static ai.brokk.testutil.AnalyzerCreator.createTreeSitterAnalyzer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PageRankBenchmarkImportGenerationTest {

    @Test
    void generatedImportsAreSyntacticallyValidAndResolvable() throws IOException {
        int n = 20;
        double edgeProb = 0.05;
        long seed = 42L;
        Random random = new Random(seed);

        List<String> fileNames = IntStream.range(0, n)
                .mapToObj(i -> String.format("File%05d", i))
                .toList();

        String firstContent = PageRankBenchmark.generateFileContent(0, fileNames, random, edgeProb);
        var builder = InlineTestProjectCreator.code(firstContent, "File00000.java");

        for (int i = 1; i < n; i++) {
            String content = PageRankBenchmark.generateFileContent(i, fileNames, random, edgeProb);
            builder.addFileContents(content, String.format("File%05d.java", i));
        }

        try (var project = builder.build()) {
            var analyzer = createTreeSitterAnalyzer(project);
            Set<ProjectFile> files = project.getAllFiles();

            for (ProjectFile file : files) {
                // a) Fetch raw imports
                List<String> rawImports = analyzer.importStatementsOf(file);

                for (String importLine : rawImports) {
                    // b) Assert each import statement matches expected explicit import syntax
                    assertTrue(
                            importLine.matches("^import p\\d+\\.File\\d{5};$"),
                            "Import statement '" + importLine + "' in " + file.getFileName() + " has invalid syntax");
                }

                // c) Fetch resolved imports
                Set<CodeUnit> resolvedImports = analyzer.importedCodeUnitsOf(file);

                // d) Assert resolved import count equals raw import count
                assertEquals(
                        rawImports.size(),
                        resolvedImports.size(),
                        "Resolved import count mismatch in " + file.getFileName());

                // e) Assert each imported FQN is present in the resolved set
                Set<String> resolvedFqns = resolvedImports.stream()
                        .map(CodeUnit::fqName)
                        .collect(Collectors.toSet());

                for (String importLine : rawImports) {
                    String fqn = importLine.substring("import ".length(), importLine.length() - 1);
                    assertTrue(
                            resolvedFqns.contains(fqn),
                            "FQN " + fqn + " from import statement not found in resolved imports of " + file.getFileName());
                }
            }
        }
    }
}
