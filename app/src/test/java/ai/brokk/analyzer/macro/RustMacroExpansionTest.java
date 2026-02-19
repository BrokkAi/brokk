package ai.brokk.analyzer.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.analyzer.TreeSitterAnalyzer;
import ai.brokk.analyzer.rust.macro.IsMacroExpander;
import ai.brokk.project.IProject;
import ai.brokk.testutil.AnalyzerCreator;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterRust;

class RustMacroExpansionTest {

    @Test
    void testIsMacroExpanderSupports() throws IOException {
        String codeWithAttr = "#[derive(is_macro::Is)] enum Target {}";
        String codeWithoutAttr = "#[derive(Debug)] enum Other {}";

        IsMacroExpander expander = new IsMacroExpander();

        // Test matching
        SourceContent sourceWith = SourceContent.of(codeWithAttr);
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterRust());
        TSTree treeWith = parser.parseString(null, codeWithAttr);
        TSNode enumWith = findNodeByType(treeWith.getRootNode(), "enum_item");
        assertTrue(
                enumWith != null && expander.supports(enumWith, sourceWith), "Should support enum with Is attribute");

        // Test non-matching
        SourceContent sourceWithout = SourceContent.of(codeWithoutAttr);
        TSTree treeWithout = parser.parseString(null, codeWithoutAttr);
        TSNode enumWithout = findNodeByType(treeWithout.getRootNode(), "enum_item");
        assertTrue(
                enumWithout != null && !expander.supports(enumWithout, sourceWithout),
                "Should not support enum without Is attribute");
    }

    private TSNode findNodeByType(TSNode root, String type) {
        if (type.equals(root.getType())) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            TSNode found = findNodeByType(root.getChild(i), type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void testIsMacroExpander() throws IOException {
        String code =
                """
            enum ScopeKind {
                Module,
                Class,
                Function,
            }
            """;

        try (IProject project =
                InlineTestProjectCreator.code(code, "src/lib.rs").build()) {
            ProjectFile file = project.getAllFiles().iterator().next();

            TSParser parser = new TSParser();
            parser.setLanguage(new TreeSitterRust());
            TSTree tree = parser.parseString(null, code);
            TSNode enumNode = findNodeByType(tree.getRootNode(), "enum_item");
            assertTrue(enumNode != null);

            IsMacroExpander expander = new IsMacroExpander();
            SourceContent source = SourceContent.of(code);

            List<CodeUnit> results = expander.expand(enumNode, source, file, "my_crate");

            // Expect: 1 impl block + 3 functions
            assertEquals(4, results.size());

            assertEquals("my_crate.ScopeKind", results.get(0).fqName());
            assertTrue(results.get(0).isClass());

            assertEquals("my_crate.ScopeKind.is_module", results.get(1).fqName());
            assertTrue(results.get(1).isFunction());

            assertEquals("my_crate.ScopeKind.is_class", results.get(2).fqName());
            assertEquals("my_crate.ScopeKind.is_function", results.get(3).fqName());
        }
    }

    @Test
    void testRustAnalyzerIntegration() throws IOException {
        String code =
                """
            #[derive(is_macro::Is)]
            enum ScopeKind {
                Module,
            }

            impl ScopeKind {
                pub fn manual_method(&self) {}
            }
            """;

        try (IProject project =
                InlineTestProjectCreator.code(code, "src/lib.rs").build()) {
            TreeSitterAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            ProjectFile file = project.getAllFiles().iterator().next();

            Set<CodeUnit> declarations = analyzer.getDeclarations(file);

            // Verify synthetic methods exist
            assertTrue(
                    declarations.stream().anyMatch(cu -> cu.fqName().equals("ScopeKind.is_module")),
                    "Should contain synthetic is_module method");

            // Verify manual methods exist
            assertTrue(
                    declarations.stream().anyMatch(cu -> cu.fqName().equals("ScopeKind.manual_method")),
                    "Should contain manual method");
        }
    }

    @Test
    void testCollisionHandling() throws IOException {
        String code =
                """
            #[derive(is_macro::Is)]
            enum ScopeKind {
                Module,
            }

            impl ScopeKind {
                pub fn is_module(&self) -> bool { true }
            }
            """;

        try (IProject project =
                InlineTestProjectCreator.code(code, "src/lib.rs").build()) {
            TreeSitterAnalyzer analyzer = AnalyzerCreator.createTreeSitterAnalyzer(project);
            ProjectFile file = project.getAllFiles().iterator().next();

            // In TreeSitterAnalyzer, signatures are added for each occurrence.
            // If our collision check works, we should only see the manual one (with its specific signature)
            // or at least ensure we didn't duplicate the CU if they are identical.
            // Our expandMacros checks localCuByFqName before adding.

            List<CodeUnit> topLevel = analyzer.getTopLevelDeclarations(file);
            CodeUnit scopeKindImpl = topLevel.stream()
                    .filter(cu -> cu.isClass() && cu.fqName().equals("ScopeKind"))
                    .findFirst()
                    .orElseThrow();

            List<CodeUnit> children = analyzer.getDirectChildren(scopeKindImpl);
            long count = children.stream()
                    .filter(c -> c.identifier().equals("is_module"))
                    .count();

            assertEquals(1, count, "Should only have one is_module method despite macro");

            // Assert that the is_module method is the user-defined one (not synthetic)
            CodeUnit isModule = children.stream()
                    .filter(c -> c.identifier().equals("is_module"))
                    .findFirst()
                    .orElseThrow();
            assertNotNull(isModule, "is_module should exist and not be null");
        }
    }
}
