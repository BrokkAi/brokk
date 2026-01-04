package ai.brokk.analyzer.tests;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.ContextManager;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

@NullMarked
public class RustTestDetectionTest {

    @Test
    void testContainsTestsDetection() throws Exception {
        String testFileContent = """
            #[cfg(test)]
            mod tests {
                #[test]
                fn it_works() {
                    assert_eq!(2 + 2, 4);
                }
            }
            """;

        String regularFileContent = """
            pub fn add(a: i32, b: i32) -> i32 {
                a + b
            }
            """;

        // We use a filename that specifically does NOT match ContextManager.TEST_FILE_PATTERN
        // to ensure we are testing the semantic analyzer check.
        String testFileName = "logic.rs";
        String regularFileName = "lib.rs";

        IProject project = InlineTestProjectCreator.code(testFileContent, testFileName)
                .addFileContents(regularFileContent, regularFileName)
                .build();

        RustAnalyzer analyzer = new RustAnalyzer(project);
        analyzer.update();

        ProjectFile testFile = new ProjectFile(project.getRoot(), testFileName);
        ProjectFile regularFile = new ProjectFile(project.getRoot(), regularFileName);

        // Assert analyzer semantic detection
        assertTrue(analyzer.containsTests(testFile), "File with #[cfg(test)] should be detected as containing tests");
        assertFalse(analyzer.containsTests(regularFile), "File without markers should not be detected as containing tests");

        // Assert ContextManager integration
        // ContextManager.isTestFile should return true for logic.rs because the analyzer confirms it contains tests,
        // even though "logic.rs" doesn't match the filename regex.
        assertTrue(ContextManager.isTestFile(testFile, analyzer), 
            "ContextManager should identify file as test file via analyzer despite non-matching filename");
            
        assertFalse(ContextManager.isTestFile(regularFile, analyzer),
            "ContextManager should not identify regular lib.rs as test file");
    }
}
