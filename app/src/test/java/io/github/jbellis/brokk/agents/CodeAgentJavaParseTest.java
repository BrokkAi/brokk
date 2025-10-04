package io.github.jbellis.brokk.agents;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.Messages;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.jupiter.api.Test;

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

    // PJ-1: parseJavaPhase retries on syntax errors
    @Test
    void testParseJavaPhase_withSyntaxErrors_requestsRetry() throws IOException {
        var badSource = """
                class Bad { void m( { } }
                """;
        var res = runParseJava("Bad.java", badSource);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var retry = (CodeAgent.Step.Retry) res.step();

        // nextRequest prompt should mention syntax/identifier errors
        var retryText = Messages.getText(requireNonNull(retry.cs().nextRequest()));
        assertNotNull(retryText);
        assertTrue(retryText.contains("Java syntax or identifier errors were detected"));

        // diagnostics captured and state updated as a "failure" retry
        var diagMap = retry.es().javaLintDiagnostics();
        assertFalse(diagMap.isEmpty(), "Expected diagnostics to be stored");
        assertFalse(retry.es().lastBuildError().isEmpty(), "Expected diagnostic summary to be captured");
        assertEquals(1, retry.es().consecutiveBuildFailures(), "Should increment consecutive build failures");
        assertEquals(0, retry.es().blocksAppliedWithoutBuild(), "Should reset edits-since-last-build to 0");
        assertTrue(retry.es().changedFiles().contains(res.file()), "Changed files should still include the Java file");
    }

    // PJ-2: parseJavaPhase continues on clean parse (no syntax errors), diagnostics empty
    @Test
    void testParseJavaPhase_cleanParse_continues_andDiagnosticsEmpty() throws IOException {
        var okSource =
                """
                class Ok {
                  void m() {}
                }
                """;
        var res = runParseJava("Ok.java", okSource);

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
        assertEquals(1, res.step().es().blocksAppliedWithoutBuild(), "Edits-since-last-build should remain unchanged");
        assertTrue(res.step().es().changedFiles().contains(res.file()), "Changed files should remain unchanged");
    }

    // PJ-3: parseJavaPhase - blank file leads to continue, diagnostics empty
    @Test
    void testParseJavaPhase_blankFile_ok() throws IOException {
        var res = runParseJava("Blank.java", "");

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-4: parseJavaPhase - import resolution errors are ignored, diagnostics empty
    @Test
    void testParseJavaPhase_importErrorsIgnored_continue() throws IOException {
        var src =
                """
                import not.exists.Missing;
                class ImportErr { void m() {} }
                """;
        var res = runParseJava("ImportErr.java", src);

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-5: parseJavaPhase - type mismatch errors are ignored, diagnostics empty
    @Test
    void testParseJavaPhase_typeMismatchIgnored_continue() throws IOException {
        var src =
                """
                class TypeMismatch {
                  void m() {
                    int x = "s";
                  }
                }
                """;
        var res = runParseJava("TypeMismatch.java", src);

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-9: parseJavaPhase - uninitialized local variable should store diagnostics and retry
    @Test
    void testParseJavaPhase_uninitializedLocal_requestsRetry() throws IOException {
        var src =
                """
                class UninitLocal {
                  int f() {
                    int x;
                    return x; // use before definite assignment
                  }
                }
                """;
        var res = runParseJava("UninitLocal.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.problemId() == IProblem.UninitializedLocalVariable), diags.toString());
    }

    // PJ-10: parseJavaPhase - missing return value in non-void method should store diagnostics and retry
    @Test
    void testParseJavaPhase_missingReturn_requestsRetry() throws IOException {
        var src =
                """
                class NeedsReturn {
                  int f(boolean b) {
                    if (b) return 1;
                    // missing return on some control path
                  }
                }
                """;
        var res = runParseJava("NeedsReturn.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.ShouldReturnValue), diags.toString());
    }

    // PJ-6: parseJavaPhase - multiple files with syntax/identifier errors aggregate diagnostics and retry
    @Test
    void testParseJavaPhase_multipleFiles_collectsDiagnostics_andRequestsRetry() throws IOException {
        var f1 = contextManager.toFile("Bad1.java");
        var s1 = """
                class Bad1 { void m( { int a = b; } }
                """; // syntax + undefined
        f1.write(s1);
        contextManager.addEditableFile(f1);

        var f2 = contextManager.toFile("Bad2.java");
        var s2 = """
                class Bad2 { void n(){ y++; }
                """; // missing closing brace +
        // undefined identifier
        f2.write(s2);
        contextManager.addEditableFile(f2);

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = new CodeAgent.EditState(
                List.of(), 0, 0, 0, 1, "", new HashSet<>(Set.of(f1, f2)), new HashMap<>(), new HashMap<>());

        var result = codeAgent.parseJavaPhase(cs, es, null);
        assertInstanceOf(CodeAgent.Step.Retry.class, result);

        var diags = result.es().javaLintDiagnostics();
        assertFalse(diags.isEmpty(), "Diagnostics should be present");
        // Ensure diagnostics map contains entries for the files we created
        assertTrue(diags.containsKey(f1), "Diagnostics should include Bad1.java entry");
        assertTrue(diags.containsKey(f2), "Diagnostics should include Bad2.java entry");
        assertEquals(0, result.es().blocksAppliedWithoutBuild(), "Edits-since-last-build should reset on retry");
    }

    // PJ-7: parseJavaPhase - undefined class/type usage should be ignored (continue, diagnostics empty)
    @Test
    void testParseJavaPhase_undefinedClassIgnored_continue() throws IOException {
        var src =
                """
                class MissingClassUse {
                  void m() {
                    MissingType x;
                  }
                }
                """;
        var res = runParseJava("MissingClassUse.java", src);

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
        assertEquals(1, res.step().es().blocksAppliedWithoutBuild(), "Edits-since-last-build should remain unchanged");
        assertTrue(res.step().es().changedFiles().contains(res.file()), "Changed files should remain unchanged");
    }

    // PJ-8: parseJavaPhase - undefined method usage should be ignored (continue, diagnostics empty)
    @Test
    void testParseJavaPhase_undefinedMethodIgnored_continue() throws IOException {
        var src =
                """
                class MissingMethodUse {
                  void m(){
                    String s="";
                    s.noSuchMethod();
                    MissingMethodUse.noSuchStatic();
                  }
                }
                """;
        var res = runParseJava("MissingMethodUse.java", src);

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
        assertEquals(1, res.step().es().blocksAppliedWithoutBuild(), "Edits-since-last-build should remain unchanged");
        assertTrue(res.step().es().changedFiles().contains(res.file()), "Changed files should remain unchanged");
    }

    // PJ-11: parseJavaPhase - missing external types in signatures/static access should be ignored (false positives)
    @Test
    void testParseJavaPhase_missingExternalTypesInSignaturesIgnored_continue() throws IOException {
        var src =
                """
                import org.apache.logging.log4j.LogManager; // missing dep
                class FakeCA {
                  // Missing 'Logger' and 'LogManager' types should not be reported
                  Logger log = LogManager.getLogger(FakeCA.class);
                  // Missing return type 'TaskResult' should not be reported
                  TaskResult run() { return null; }
                }
                """;
        var res = runParseJava("FakeCA.java", src);

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-12: parseJavaPhase - undefined name that is actually a missing type qualifier should be ignored
    @Test
    void testParseJavaPhase_missingTypeUsedAsQualifierIgnored_continue() throws IOException {
        var src =
                """
                class QualifierMissingType {
                  void m() {
                    // MissingType is a missing class; used as a qualifier before '.'
                    var p = MissingType.STATIC_FIELD;
                  }
                }
                """;
        var res = runParseJava("QualifierMissingType.java", src);

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-13: override mismatch should surface diagnostics ("must override or implement a supertype method")
    @Test
    void testParseJavaPhase_overrideMismatch_requestsRetry() throws IOException {
        var src =
                """
                class OverrideErr {
                  @Override
                  public String getGitTopLevel() { return ""; }
                }
                """;
        var res = runParseJava("OverrideErr.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.description().contains("override or implement a supertype method")),
                diags.toString());
    }

    // PJ-14: class must implement inherited abstract method should surface diagnostics
    @Test
    void testParseJavaPhase_mustImplementAbstractMethod_requestsRetry() throws IOException {
        var src =
                """
                abstract class AbstractHighlight {
                  abstract int getChunk(int a);
                }
                class HighlightOriginal extends AbstractHighlight {
                  HighlightOriginal() {}
                  // missing getChunk implementation
                }
                """;
        var res = runParseJava("HighlightOriginal.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.description().contains("must implement the inherited abstract method")),
                diags.toString());
    }

    // PJ-15: invalid foreach target should surface diagnostics
    @Test
    void testParseJavaPhase_invalidForEach_requestsRetry() throws IOException {
        var src =
                """
                class ForEachBad {
                  void m() {
                    Object files = new Object();
                    for (String s : files) { }
                  }
                }
                """;
        var res = runParseJava("ForEachBad.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.description()
                        .contains("Can only iterate over an array or an instance of java.lang.Iterable")),
                diags.toString());
    }

    // PJ-16: ThreadLocal.withInitial with lambda that returns void should surface diagnostics
    @Test
    void testParseJavaPhase_withInitialLambdaMismatch_requestsRetry() throws IOException {
        var src =
                """
                class TLBad {
                  ThreadLocal<String> tl = ThreadLocal.withInitial(() -> { });
                }
                """;
        var res = runParseJava("TLBad.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream()
                        .anyMatch(d -> d.description().contains("withInitial")
                                && d.description().contains("not applicable")),
                diags.toString());
    }

    // PJ-17: undefined constructor IOException(Object) should surface diagnostics
    @Test
    void testParseJavaPhase_undefinedConstructorIOException_requestsRetry() throws IOException {
        var src =
                """
                class BadCtor {
                  void m() throws java.io.IOException {
                    Object o = new Object();
                    throw new java.io.IOException(o);
                  }
                }
                """;
        var res = runParseJava("BadCtor.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.description().contains("constructor IOException(Object) is undefined")),
                diags.toString());
    }

    // PJ-18: incompatible return types for inherited methods should surface diagnostics
    @Test
    void testParseJavaPhase_incompatibleReturnTypesForInheritedMethods_requestsRetry() throws IOException {
        var src =
                """
                interface PathFragment {
                  int files();
                }
                record GitFileFragment() implements PathFragment {
                  @Override
                  public String files() { return "x"; }
                }
                """;
        var res = runParseJava("GitFileFragment.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.description().contains("return type is incompatible")),
                diags.toString());
    }

    // PJ-19: missing external type via 'var' inference should be ignored (classpath)
    @Test
    void testParseJavaPhase_missingExternalTypeViaVarInferenceIgnored_continue() throws IOException {
        var src =
                """
                class MissingExternalTypeViaVar {
                  static MissingExternal resolve(String rev) { return null; } // unknown type
                  void m() {
                    var id = resolve("x"); // inference depends on MissingExternal
                  }
                }
                """;
        var res = runParseJava("MissingExternalTypeViaVar.java", src);

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-20: parseJavaPhase - redefined local variable should store diagnostics and retry
    @Test
    void testParseJavaPhase_redefinedLocal_requestsRetry() throws IOException {
        var src =
                """
                class RedefLocal {
                  void m() {
                    int x = 1;
                    int x = 2; // redefinition
                  }
                }
                """;
        var res = runParseJava("RedefLocal.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.RedefinedLocal), diags.toString());
    }

    // PJ-21: parseJavaPhase - when there are no edits since last build, skip parsing (no diagnostics)
    @Test
    void testParseJavaPhase_noEdits_skipsParsing_diagnosticsEmpty() throws IOException {
        var javaFile = contextManager.toFile("NoEdits.java");
        javaFile.write(
                """
                class NoEdits { void m( { } }
                """); // would be a syntax error if
        // parsed
        contextManager.addEditableFile(javaFile);

        var cs = createConversationState(List.of(), new UserMessage("req"));
        var es = new CodeAgent.EditState(
                List.of(), // pending blocks
                0, // parse failures
                0, // apply failures
                0, // build failures
                0, // blocksAppliedWithoutBuild => skip parse phase
                "", // lastBuildError
                new HashSet<>(Set.of(javaFile)), // changedFiles includes the Java file
                new HashMap<>(), // originalFileContents
                new HashMap<>() // javaLintDiagnostics
                );
        var step = codeAgent.parseJavaPhase(cs, es, null);

        assertInstanceOf(CodeAgent.Step.Continue.class, step);
        assertTrue(
                step.es().javaLintDiagnostics().isEmpty(),
                step.es().javaLintDiagnostics().toString());
    }

    // PJ-22: parseJavaPhase - redefined argument (duplicate parameter) should store diagnostics and retry
    @Test
    void testParseJavaPhase_redefinedArgument_requestsRetry() throws IOException {
        var src =
                """
                class RedefArg {
                  void m(int a, int a) { }
                }
                """;
        var res = runParseJava("RedefArg.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.RedefinedArgument), diags.toString());
    }

    // PJ-23: parseJavaPhase - finally block that does not complete normally should store diagnostics and retry
    @Test
    void testParseJavaPhase_finallyMustCompleteNormally_requestsRetry() throws IOException {
        var src =
                """
                class FinallyBad {
                  void f() {
                    while (true) {
                      try {
                        break;
                      } finally {
                        continue; // finally does not complete normally
                      }
                    }
                  }
                }
                """;
        var res = runParseJava("FinallyBad.java", src);

        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.problemId() == IProblem.FinallyMustCompleteNormally), diags.toString());
    }

    // PJ-24: duplicate case inside switch should be reported
    @Test
    void testParseJavaPhase_duplicateCase_requestsRetry() throws IOException {
        var src =
                """
                class SwDupCase {
                  void m(int x) {
                    switch (x) {
                      case 1: break;
                      case 1: break;
                    }
                  }
                }
                """;
        var res = runParseJava("SwDupCase.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.DuplicateCase), diags.toString());
    }

    // PJ-25: duplicate default inside switch should be reported
    @Test
    void testParseJavaPhase_duplicateDefault_requestsRetry() throws IOException {
        var src =
                """
                class SwDupDefault {
                  void m(int x) {
                    switch (x) {
                      default: break;
                      default: break;
                    }
                  }
                }
                """;
        var res = runParseJava("SwDupDefault.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.DuplicateDefaultCase), diags.toString());
    }

    // PJ-27: void method returning a value should be reported
    @Test
    void testParseJavaPhase_voidMethodReturnsValue_requestsRetry() throws IOException {
        var src =
                """
                class VoidReturnVal {
                  void m() { return 1; }
                }
                """;
        var res = runParseJava("VoidReturnVal.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.VoidMethodReturnsValue), diags.toString());
    }

    // PJ-28: non-abstract/non-native method missing a body should be reported
    @Test
    void testParseJavaPhase_methodRequiresBody_requestsRetry() throws IOException {
        var src =
                """
                class MissingBody {
                  void m();
                }
                """;
        var res = runParseJava("MissingBody.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.MethodRequiresBody), diags.toString());
    }

    // PJ-29: var initialized to null should be reported
    @Test
    void testParseJavaPhase_varInitializedToNull_requestsRetry() throws IOException {
        var src =
                """
                class VarNullInit {
                  void m() { var x = null; }
                }
                """;
        var res = runParseJava("VarNullInit.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.VarLocalInitializedToNull), diags.toString());
    }

    // PJ-30: var initialized to a void expression should be reported
    @Test
    void testParseJavaPhase_varInitializedToVoid_requestsRetry() throws IOException {
        var src =
                """
                class VarVoidInit {
                  void m() { var x = System.out.println("x"); }
                }
                """;
        var res = runParseJava("VarVoidInit.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.VarLocalInitializedToVoid), diags.toString());
    }

    // PJ-31: var with array initializer literal should be reported
    @Test
    void testParseJavaPhase_varArrayInitializer_requestsRetry() throws IOException {
        var src =
                """
                class VarArrayInit {
                  void m() { var a = {1, 2}; }
                }
                """;
        var res = runParseJava("VarArrayInit.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(
                diags.stream().anyMatch(d -> d.problemId() == IProblem.VarLocalCannotBeArrayInitalizers),
                diags.toString());
    }

    // PJ-32: unreachable code should be reported
    @Test
    void testParseJavaPhase_unreachableCode_requestsRetry() throws IOException {
        var src =
                """
                class Unreachable {
                  int m() {
                    return 1;
                    int x = 2; // unreachable
                  }
                }
                """;
        var res = runParseJava("Unreachable.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.CodeCannotBeReached), diags.toString());
    }

    // PJ-33: return inside a static initializer should be reported
    @Test
    void testParseJavaPhase_returnInInitializer_requestsRetry() throws IOException {
        var src =
                """
                class ReturnInInit {
                  static {
                    return;
                  }
                }
                """;
        var res = runParseJava("ReturnInInit.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.CannotReturnInInitializer), diags.toString());
    }

    // PJ-35: invalid break outside loop/switch should be reported
    @Test
    void testParseJavaPhase_invalidBreak_requestsRetry() throws IOException {
        var src =
                """
                class BadBreak {
                  void m() {
                    break;
                  }
                }
                """;
        var res = runParseJava("BadBreak.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.InvalidBreak), diags.toString());
    }

    // PJ-36: invalid continue outside loop should be reported
    @Test
    void testParseJavaPhase_invalidContinue_requestsRetry() throws IOException {
        var src =
                """
                class BadContinue {
                  void m() {
                    continue;
                  }
                }
                """;
        var res = runParseJava("BadContinue.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.InvalidContinue), diags.toString());
    }

    // PJ-37: undefined label should be reported
    @Test
    void testParseJavaPhase_undefinedLabel_requestsRetry() throws IOException {
        var src =
                """
                class UndefLabel {
                  void m() {
                    while (true) {
                      break missing; // break to undefined label
                    }
                  }
                }
                """;
        var res = runParseJava("UndefLabel.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.UndefinedLabel), diags.toString());
    }

    // PJ-38: invalid primitive type in synchronized should be reported
    @Test
    void testParseJavaPhase_invalidTypeToSynchronized_requestsRetry() throws IOException {
        var src =
                """
                class SyncBadType {
                  void m() {
                    synchronized (1) {
                    }
                  }
                }
                """;
        var res = runParseJava("SyncBadType.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.InvalidTypeToSynchronized), diags.toString());
    }

    // PJ-39: null in synchronized should be reported
    @Test
    void testParseJavaPhase_invalidNullToSynchronized_requestsRetry() throws IOException {
        var src =
                """
                class SyncNull {
                  void m() {
                    synchronized (null) {
                    }
                  }
                }
                """;
        var res = runParseJava("SyncNull.java", src);
        assertInstanceOf(CodeAgent.Step.Retry.class, res.step());
        var diags = requireNonNull(res.step().es().javaLintDiagnostics().get(res.file()));
        assertTrue(diags.stream().anyMatch(d -> d.problemId() == IProblem.InvalidNullToSynchronized), diags.toString());
    }

    // PJ-40 — Unknown supertype makes @Override a false positive
    @Test
    void testParseJavaPhase_overrideOnUnknownSuperType_ignored() throws IOException {
        var src =
                """
            class GitRepo implements MissingSuper {
              @Override
              public java.nio.file.Path getGitTopLevel() { return null; }
            }
            """;
        var res = runParseJava("GitRepo.java", src);
        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-41 — Unknown argument type makes method applicability a false positive
    @Test
    void testParseJavaPhase_paramMismatchFromUnknownProvider_ignored() throws IOException {
        var src =
                """
            class ColorMismatch extends javax.swing.JComponent {
              void m() {
                Object backgroundColor = MissingFactory.color(); // unresolved provider => shaky type info
                setOpaque(true);
                setBackground(backgroundColor); // JDT reports: setBackground(Color) not applicable for (Object)
              }
            }
            """;
        var res = runParseJava("ColorMismatch.java", src);

        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
    }

    // PJ-42 — Unknown iterable type makes enhanced-for a false positive
    @Test
    void testParseJavaPhase_foreachTargetUnknownType_ignored() throws IOException {
        var src =
                """
            class ForEachUnknown {
              void m() {
                var allFragments = MissingProvider.all();
                for (var frag : allFragments) {
                  System.out.println(frag);
                }
              }
            }
            """;
        var res = runParseJava("ForEachUnknown.java", src);
        assertInstanceOf(CodeAgent.Step.Continue.class, res.step());
        assertTrue(
                res.step().es().javaLintDiagnostics().isEmpty(),
                res.step().es().javaLintDiagnostics().toString());
    }
}
