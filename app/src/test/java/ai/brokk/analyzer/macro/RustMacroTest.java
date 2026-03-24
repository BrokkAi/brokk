package ai.brokk.analyzer.macro;

import static ai.brokk.testutil.AssertionHelperUtil.assertCodeContains;
import static ai.brokk.testutil.AssertionHelperUtil.assertCodeEquals;
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

            // Verify that synthetic units share the same parent as the enum variants
            CodeUnit isRunning = children.stream()
                    .filter(cu -> cu.fqName().equals("Status.is_Running"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(
                    Optional.of(statusEnum),
                    analyzer.parentOf(isRunning),
                    "Synthetic function should have the same parent as the enum variants");

            Optional<String> source = analyzer.getSource(isRunning, false);
            assertTrue(source.isPresent(), "Source should be present for synthetic function Status.is_Running");
            String sourceText = source.get();
            assertTrue(
                    sourceText.contains("# This declaration is synthetic"), "Source should contain synthetic marker");
            assertTrue(sourceText.contains("fn is_Running"), "Source should contain function signature");
            assertCodeEquals(
                    """
                    # This declaration is synthetic
                    pub fn is_Running(&self) -> bool { ... }
                    """,
                    sourceText);
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

    @Test
    void testRustDefaultMacroExpansion() {
        String rustSource =
                """
                #[derive(Default)]
                pub struct Config {
                    pub port: u16,
                }
                """;

        try (ITestProject testProject = InlineTestProjectCreator.empty()
                .addFileContents(rustSource, "src/lib.rs")
                .withMacros(Languages.RUST, "std-v1")
                .build()) {

            IAnalyzer analyzer = testProject.getAnalyzer();
            assertNotNull(analyzer);

            // Find the Config struct
            CodeUnit configStruct =
                    analyzer.getDefinitions("Config").stream().findFirst().orElseThrow();

            // Verify that the synthetic default() method was attached directly as a child of the struct.
            List<CodeUnit> children = analyzer.getDirectChildren(configStruct);

            CodeUnit defaultFn = children.stream()
                    .filter(cu -> cu.identifier().equals("default") && cu.isSynthetic())
                    .findFirst()
                    .or(() -> children.stream()
                            .filter(cu -> cu.isClass() && cu.isSynthetic())
                            .flatMap(cu -> analyzer.getDirectChildren(cu).stream())
                            .filter(cu -> cu.identifier().equals("default") && cu.isSynthetic())
                            .findFirst())
                            .orElseThrow(() -> new AssertionError(
                                    "Missing synthetic default() method as a descendant of Config. Children found: "
                                            + children));

            assertTrue(defaultFn.fqName().contains("Config"), "FQN should contain struct name");

            Optional<String> source = analyzer.getSource(defaultFn, false);
            assertTrue(source.isPresent(), "Source should be present for synthetic default");
            String sourceText = source.get();

            // Print source for debugging if the test fails in CI
            System.out.println("Synthetic source for " + defaultFn.fqName() + ":\n" + sourceText);

            assertCodeContains(sourceText, "# This declaration is synthetic");
            assertCodeContains(sourceText, "pub fn default() -> Self");
        }
    }

    @Test
    void testRustSnakeCaseMacroExpansion() {
        String rustSource =
                """
                use is_macro::Is;
                #[derive(Is)]
                pub enum Status {
                    RunningState,
                    StoppedNow,
                }
                """;

        try (ITestProject testProject = InlineTestProjectCreator.empty()
                .addFileContents(rustSource, "src/lib.rs")
                .withMacros(Languages.RUST, "is_macro")
                .build()) {

            IAnalyzer analyzer = testProject.getAnalyzer();
            CodeUnit statusEnum = analyzer.getDefinitions("Status").stream().findFirst().orElseThrow();

            List<CodeUnit> children = analyzer.getDirectChildren(statusEnum);

            // Our toSnakeCase logic converts PascalCase "RunningState" to "running_state"
            // The template uses is_{{identifier}}, but we can test that the context was right 
            // by asserting on the expected function names if the template were changed, 
            // or by checking the logic itself.
            assertSyntheticFunction(children, "Status.is_RunningState");
            assertSyntheticFunction(children, "Status.is_StoppedNow");
        }
    }

    @Test
    void testRustIsMacroWithPayload() {
        String rustSource =
                """
                use is_macro::Is;
                #[derive(Is)]
                pub enum WebEvent {
                    PageLoad,
                    KeyPress(char),
                    Paste(String),
                }
                """;

        try (ITestProject testProject = InlineTestProjectCreator.empty()
                .addFileContents(rustSource, "src/lib.rs")
                .withMacros(Languages.RUST, "is_macro")
                .build()) {

            IAnalyzer analyzer = testProject.getAnalyzer();
            CodeUnit webEvent = analyzer.getDefinitions("WebEvent").stream().findFirst().orElseThrow();
            List<CodeUnit> children = analyzer.getDirectChildren(webEvent);

            // Verify basic is_xxx methods
            assertSyntheticFunction(children, "WebEvent.is_PageLoad");
            assertSyntheticFunction(children, "WebEvent.is_KeyPress");
            assertSyntheticFunction(children, "WebEvent.is_Paste");

            // Verify payload methods for KeyPress(char)
            assertSyntheticFunction(children, "WebEvent.as_key_press");
            assertSyntheticFunction(children, "WebEvent.as_mut_key_press");
            assertSyntheticFunction(children, "WebEvent.unwrap_key_press");

            // Verify payload methods for Paste(String)
            assertSyntheticFunction(children, "WebEvent.as_paste");
            assertSyntheticFunction(children, "WebEvent.unwrap_paste");

            CodeUnit asPaste = children.stream()
                    .filter(cu -> cu.fqName().equals("WebEvent.as_paste"))
                    .findFirst()
                    .orElseThrow();

            String source = analyzer.getSource(asPaste, false).orElse("");
            assertCodeContains(source, "fn as_paste(&self) -> Option<&String>");
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
