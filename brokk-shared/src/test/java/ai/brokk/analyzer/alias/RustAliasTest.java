package ai.brokk.analyzer.alias;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.analyzer.RustAnalyzer;
import ai.brokk.project.ICoreProject;
import ai.brokk.testutil.InlineCoreProject;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class RustAliasTest {

    @Test
    void testIsTypeAlias() throws IOException {
        String code =
                """
                type MyResult<T> = Result<T, Error>;
                struct MyStruct;
                fn my_func() {}
                """;
        ICoreProject project = InlineCoreProject.code(code, "src/main.rs").build();
        RustAnalyzer analyzer = new RustAnalyzer(project);
        ProjectFile file = new ProjectFile(project.getRoot(), "src/main.rs");

        CodeUnit aliasCu = analyzer.getDeclarations(file).stream()
                .filter(cu -> cu.identifier().equals("MyResult"))
                .findFirst()
                .orElseThrow();

        CodeUnit structCu = analyzer.getDeclarations(file).stream()
                .filter(cu -> cu.identifier().equals("MyStruct"))
                .findFirst()
                .orElseThrow();

        CodeUnit fnCu = analyzer.getDeclarations(file).stream()
                .filter(cu -> cu.identifier().equals("my_func"))
                .findFirst()
                .orElseThrow();

        assertTrue(analyzer.isTypeAlias(aliasCu), "MyResult should be identified as a type alias");
        assertFalse(analyzer.isTypeAlias(structCu), "MyStruct should NOT be identified as a type alias");
        assertFalse(analyzer.isTypeAlias(fnCu), "my_func should NOT be identified as a type alias");
    }
}
