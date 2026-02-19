package ai.brokk.analyzer.rust.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.SourceContent;
import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterRust;

class RustMacroExpansionTest {

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
            TSNode root = tree.getRootNode();
            TSNode enumNode = root.getChild(0); // enum_item

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
}
