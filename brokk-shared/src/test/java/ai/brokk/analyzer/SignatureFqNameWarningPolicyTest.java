package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.InlineTestProjectCreator;
import org.junit.jupiter.api.Test;

final class SignatureFqNameWarningPolicyTest {

    @Test
    void dynamicAnalyzersDoNotWarnOnParenthesizedFqNames() {
        try (var project = InlineTestProjectCreator.code(
                        "export const uiConfig = {};", "app/(dashboard)/hooks/uiConfig.ts")
                .addFileContents("export const value = 1;", "app/(dashboard)/hooks/value.js")
                .build()) {
            var typescriptAnalyzer = new TypescriptAnalyzer(project);
            var javascriptAnalyzer = new JavascriptAnalyzer(project);

            assertFalse(typescriptAnalyzer.warnOnSignatureInFqNameLookup());
            assertFalse(javascriptAnalyzer.warnOnSignatureInFqNameLookup());
        }
    }

    @Test
    void routeGroupFqNameStillLooksUpInTypescript() {
        try (var project = InlineTestProjectCreator.code(
                        "export const uiConfig = {};", "app/(dashboard)/hooks/uiConfig.ts")
                .build()) {
            var analyzer = new TypescriptAnalyzer(project);

            var definitions = analyzer.getDefinitions("app.(dashboard).hooks.uiConfig.ts.uiConfig");

            assertEquals(1, definitions.size());
            assertEquals(
                    "app.(dashboard).hooks.uiConfig.ts.uiConfig",
                    definitions.getFirst().fqName());
        }
    }

    @Test
    void overloadAwareAnalyzersWarnOnSignatureFqNames() {
        try (var project = InlineTestProjectCreator.code("class C { void m(int i) {} }", "C.java")
                .addFileContents("void f(int i) {}", "f.cpp")
                .addFileContents("class C { void M(int i) {} }", "C.cs")
                .addFileContents("class C { def m(i: Int): Unit = () }", "C.scala")
                .build()) {
            assertTrue(new JavaAnalyzer(project).warnOnSignatureInFqNameLookup());
            assertTrue(new CppAnalyzer(project).warnOnSignatureInFqNameLookup());
            assertTrue(new CSharpAnalyzer(project).warnOnSignatureInFqNameLookup());
            assertTrue(new ScalaAnalyzer(project).warnOnSignatureInFqNameLookup());
        }
    }
}
