package io.github.jbellis.brokk.agents;

import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.Messages;
import org.junit.jupiter.api.Test;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;

import java.io.IOException;
import java.util.Objects;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

public class CodeAgentJavaParseTest extends CodeAgentTest {
    // Helper for Java parse-phase tests: create file, mark edited, and invoke parseJavaPhase
    private record JavaParseResult(ProjectFile file, CodeAgent.Step step) {}

    private JavaParseResult runParseJava(String fileName, String src) throws IOException {
        var javaFile = contextManager.toFile(fileName);
        javaFile.write(src);
        contextManager.addEditableFile(javaFile);

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = new CodeAgent.EditState(
                List.of(), // pending blocks
                0, // parse failures
                0, // apply failures
                0, // build failures
                1, // blocksAppliedWithoutBuild
                "", // lastBuildError
                new HashSet<>(Set.of(javaFile)), // changedFiles includes the Java file
                new HashMap<>(), // originalFileContents
                new HashMap<>() // javaLintDiagnostics
        );
        var step = codeAgent.parseJavaPhase(cs, es, null);
        return new JavaParseResult(javaFile, step);
    }

    // PJ-1: parseJavaPhase stores diagnostics and continues on syntax errors
    @Test
    void testParseJavaPhase_withSyntaxErrors_storesDiagnostics_andContinues() throws IOException {
        var badSource = """
                class Bad { void m( { } }
                """;
        var res = runParseJava("Bad.java", badSource);

        var diagMap = res.step().es().javaLintDiagnostics();
        assertFalse(diagMap.isEmpty(), "Expected diagnostics to be stored");
        var diags = requireNonNull(diagMap.get(res.file()), "Expected entry for the edited file");
        assertTrue(
                diags.stream().anyMatch(d -> Objects.equals(d.categoryId(), CategorizedProblem.CAT_SYNTAX)),
                "Expected at least one SYNTAX-category diagnostic");
        assertTrue(res.step().es().changedFiles().contains(res.file()), "Changed files should still include the Java file");
    }

    // PJ-1b: parseJavaPhase stores diagnostics for undefined variable errors and continues
    @Test
    void testParseJavaPhase_withUndefinedVariable_storesDiagnostics_andContinues() throws IOException {
        var src = """
                class Undeclared {
                  void m() {
                    x = 42;
                  }
                }
                """;
        var res = runParseJava("Undeclared.java", src);

        var diagMap = res.step().es().javaLintDiagnostics();
        assertFalse(diagMap.isEmpty(), "Expected identifier diagnostics to be captured");
        var diags = requireNonNull(diagMap.get(res.file()), "Expected entry for the edited file");
        assertTrue(
                diags.stream().anyMatch(d -> d.problemId() == IProblem.UndefinedName),
                diags.toString());
    }

    // PJ-2: parseJavaPhase continues on clean parse (no syntax errors), diagnostics empty
    @Test
    void testParseJavaPhase_cleanParse_continues_andDiagnosticsEmpty() throws IOException {
        var okSource = """
                class Ok {
                  void m() {}
                }
                """;
        var res = runParseJava("Ok.java", okSource);

        assertTrue(res.step().es().javaLintDiagnostics().isEmpty(), res.step().es().javaLintDiagnostics().toString());
        assertEquals(1, res.step().es().blocksAppliedWithoutBuild(), "Edits-since-last-build should remain unchanged");
        assertTrue(res.step().es().changedFiles().contains(res.file()), "Changed files should remain unchanged");
    }

    // PJ-3: parseJavaPhase - blank file leads to continue, diagnostics empty
    @Test
    void testParseJavaPhase_blankFile_ok() throws IOException {
        var res = runParseJava("Blank.java", "");

        assertTrue(res.step().es().javaLintDiagnostics().isEmpty(), res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-4: parseJavaPhase - import resolution errors are ignored, diagnostics empty
    @Test
    void testParseJavaPhase_importErrorsIgnored_continue() throws IOException {
        var src = """
                import not.exists.Missing;
                class ImportErr { void m() {} }
                """;
        var res = runParseJava("ImportErr.java", src);

        assertTrue(res.step().es().javaLintDiagnostics().isEmpty(), res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-5: parseJavaPhase - type mismatch errors are ignored, diagnostics empty
    @Test
    void testParseJavaPhase_typeMismatchIgnored_continue() throws IOException {
        var src = """
                class TypeMismatch {
                  void m() {
                    int x = "s";
                  }
                }
                """;
        var res = runParseJava("TypeMismatch.java", src);

        assertTrue(res.step().es().javaLintDiagnostics().isEmpty(), res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-9: parseJavaPhase - uninitialized local variable should store diagnostics and continue
    @Test
    void testParseJavaPhase_uninitializedLocal_storesDiagnostics_andContinues() throws IOException {
        var src = """
                class UninitLocal {
                  int f() {
                    int x;
                    return x; // use before definite assignment
                  }
                }
                """;
        var res = runParseJava("UninitLocal.java", src);

        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.problemId() == IProblem.UninitializedLocalVariable),
                diags.toString());
    }

    // PJ-10: parseJavaPhase - missing return value in non-void method should store diagnostics and continue
    @Test
    void testParseJavaPhase_missingReturn_storesDiagnostics_andContinues() throws IOException {
        var src = """
                class NeedsReturn {
                  int f(boolean b) {
                    if (b) return 1;
                    // missing return on some control path
                  }
                }
                """;
        var res = runParseJava("NeedsReturn.java", src);

        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.problemId() == IProblem.ShouldReturnValue),
                diags.toString());
    }

    // PJ-6: parseJavaPhase - multiple files with syntax/identifier errors aggregate diagnostics and continue
    @Test
    void testParseJavaPhase_multipleFiles_collectsDiagnostics_andContinues() throws IOException {
        var f1 = contextManager.toFile("Bad1.java");
        var s1 = """
                class Bad1 { void m( { int a = b; } }
                """; // syntax + undefined identifier
        f1.write(s1);
        contextManager.addEditableFile(f1);

        var f2 = contextManager.toFile("Bad2.java");
        var s2 = """
                class Bad2 { void n(){ y++; }
                """; // missing closing brace + undefined identifier
        f2.write(s2);
        contextManager.addEditableFile(f2);

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = new CodeAgent.EditState(List.of(), 0, 0, 0, 1, "", new HashSet<>(Set.of(f1, f2)), new HashMap<>(), new HashMap<>());

        var result = codeAgent.parseJavaPhase(cs, es, null);
        var diags = result.es().javaLintDiagnostics();
        assertFalse(diags.isEmpty(), "Diagnostics should be present");
        // Ensure diagnostics map contains entries for the files we created
        assertTrue(diags.containsKey(f1), "Diagnostics should include Bad1.java entry");
        assertTrue(diags.containsKey(f2), "Diagnostics should include Bad2.java entry");
        assertEquals(1, result.es().blocksAppliedWithoutBuild(), "Edits-since-last-build should remain unchanged");
    }

    // PJ-7: parseJavaPhase - undefined class/type usage should be ignored (continue, diagnostics empty)
    @Test
    void testParseJavaPhase_undefinedClassIgnored_continue() throws IOException {
        var src = """
                class MissingClassUse {
                  void m() {
                    MissingType x;
                  }
                }
                """;
        var res = runParseJava("MissingClassUse.java", src);

        assertTrue(res.step().es().javaLintDiagnostics().isEmpty(), res.step().es().javaLintDiagnostics().toString());
        assertEquals(1, res.step().es().blocksAppliedWithoutBuild(), "Edits-since-last-build should remain unchanged");
        assertTrue(res.step().es().changedFiles().contains(res.file()), "Changed files should remain unchanged");
    }

    // PJ-8: parseJavaPhase - undefined method usage should be ignored (continue, diagnostics empty)
    @Test
    void testParseJavaPhase_undefinedMethodIgnored_continue() throws IOException {
        var src = """
                class MissingMethodUse {
                  void m(){
                    String s="";
                    s.noSuchMethod();
                    MissingMethodUse.noSuchStatic();
                  }
                }
                """;
        var res = runParseJava("MissingMethodUse.java", src);

        assertTrue(res.step().es().javaLintDiagnostics().isEmpty(), res.step().es().javaLintDiagnostics().toString());
        assertEquals(1, res.step().es().blocksAppliedWithoutBuild(), "Edits-since-last-build should remain unchanged");
        assertTrue(res.step().es().changedFiles().contains(res.file()), "Changed files should remain unchanged");
    }

    // PJ-11: parseJavaPhase - missing external types in signatures/static access should be ignored (false positives)
    @Test
    void testParseJavaPhase_missingExternalTypesInSignaturesIgnored_continue() throws IOException {
        var src = """
                import org.apache.logging.log4j.LogManager; // missing dep
                class FakeCA {
                  // Missing 'Logger' and 'LogManager' types should not be reported
                  Logger log = LogManager.getLogger(FakeCA.class);
                  // Missing return type 'TaskResult' should not be reported
                  TaskResult run() { return null; }
                }
                """;
        var res = runParseJava("FakeCA.java", src);

        assertTrue(res.step().es().javaLintDiagnostics().isEmpty(), res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-12: parseJavaPhase - undefined name that is actually a missing type qualifier should be ignored
    @Test
    void testParseJavaPhase_missingTypeUsedAsQualifierIgnored_continue() throws IOException {
        var src = """
                class QualifierMissingType {
                  void m() {
                    // MissingType is a missing class; used as a qualifier before '.'
                    var p = MissingType.STATIC_FIELD;
                  }
                }
                """;
        var res = runParseJava("QualifierMissingType.java", src);

        assertTrue(res.step().es().javaLintDiagnostics().isEmpty(), res.step().es().javaLintDiagnostics().toString());
    }
}
