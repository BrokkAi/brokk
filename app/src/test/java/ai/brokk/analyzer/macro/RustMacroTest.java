package ai.brokk.analyzer.macro;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Languages;
import ai.brokk.testutil.ITestProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class RustMacroTest {

    @Test
    void testRustIsMacroExpansion() {
        String rustSource =
                """
                use is_macro::Is;
                #[derive(Is)]
                pub enum Status {
                    Running,
                    Stopped,
                    Initial,
                }
                """;

        try (ITestProject testProject = InlineTestProjectCreator.empty()
                .addFileContents(rustSource, "src/lib.rs")
                .withMacros(Languages.RUST, "is_macro")
                .build()) {

            IAnalyzer analyzer = testProject.getAnalyzer();
            assertNotNull(analyzer, "Analyzer should not be null");

            // Find the Status enum CodeUnit
            List<CodeUnit> statusDefinitions =
                    analyzer.getDefinitions("Status").stream().toList();
            assertEquals(1, statusDefinitions.size(), "Should find exactly one Status enum definition");
            CodeUnit statusEnum = statusDefinitions.getFirst();

            // Get children of the enum
            List<CodeUnit> children = analyzer.getDirectChildren(statusEnum);

            // Expecting 3 variants + 3 generated is_* functions
            // The is_macro.yml template generates: is_{{identifier}}
            assertSyntheticFunction(children, "Status.is_Running");
            assertSyntheticFunction(children, "Status.is_Stopped");
            assertSyntheticFunction(children, "Status.is_Initial");

            // Assert source code check for one of the synthetic units
            CodeUnit isRunning = children.stream()
                    .filter(cu -> cu.fqName().equals("Status.is_Running"))
                    .findFirst()
                    .orElseThrow();

            Optional<String> source = analyzer.getSource(isRunning, false);
            assertTrue(source.isPresent(), "Source should be present for synthetic function Status.is_Running");
            String sourceText = source.get();
            assertTrue(sourceText.contains("fn is_Running"), "Source should contain function signature");
            assertTrue(sourceText.contains("matches!(self, Self::Running { .. })"), "Source should contain expansion logic");
        }
    }

    @Test
    void testLazyStaticMacroBypass() {
        String rustSource =
                """
                use lazy_static::lazy_static;
                lazy_static! {
                    pub static ref SETTINGS: HashMap<String, String> = HashMap::new();
                }
                """;

        try (ITestProject testProject = InlineTestProjectCreator.empty()
                .addFileContents(rustSource, "src/lib.rs")
                .withMacros(Languages.RUST, "lazy_static-v1")
                .build()) {

            IAnalyzer analyzer = testProject.getAnalyzer();
            assertNotNull(analyzer);

            // With BYPASS, SETTINGS should not be expanded/found as a CodeUnit
            List<CodeUnit> definitions =
                    analyzer.getDefinitions("SETTINGS").stream().toList();
            assertEquals(0, definitions.size(), "Should not find expanded static for bypassed macro");
        }
    }

    private void assertSyntheticFunction(List<CodeUnit> units, String fqName) {
        CodeUnit match = units.stream()
                .filter(cu -> cu.fqName().equals(fqName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing expected synthetic function: " + fqName));

        assertTrue(match.isFunction(), fqName + " should be a function");
        assertTrue(match.isSynthetic(), fqName + " should be marked as synthetic");
    }
}
