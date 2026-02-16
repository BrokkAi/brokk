package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.io.IOException;
import java.util.List;
import java.util.SequencedSet;
import org.junit.jupiter.api.Test;

/**
 * Verifies that Java overloads are correctly distinguished by signature and that
 * lambdas are attached to the correct overload.
 */
public class JavaOverloadLambdaTest {

    @Test
    void testOverloadsAndLambdaAttachment() throws IOException {
        // C.java contains two overloads of m. Only the String overload contains a lambda.
        String javaSource =
                """
                class C {
                  void m(int x) {
                    System.out.println(x);
                  }
                  void m(String s) {
                    Runnable r = () -> System.out.println(s);
                    r.run();
                  }
                }
                """;

        try (IProject project =
                InlineTestProjectCreator.code(javaSource, "C.java").build()) {
            JavaAnalyzer analyzer = new JavaAnalyzer(project);

            // 1. Assert getDefinitions("C.m") returns 2 CodeUnits with distinct signatures
            SequencedSet<CodeUnit> definitions = analyzer.getDefinitions("C.m");
            assertEquals(2, definitions.size(), "Should have exactly 2 overloads for C.m");

            CodeUnit intOverload = definitions.stream()
                    .filter(cu -> "(int)".equals(cu.signature()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing (int) overload"));

            CodeUnit stringOverload = definitions.stream()
                    .filter(cu -> "(String)".equals(cu.signature()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing (String) overload"));

            // 2. Assert getDirectChildren(classCu) includes both overloads
            CodeUnit classCu = analyzer.getDefinitions("C").stream()
                    .filter(CodeUnit::isClass)
                    .findFirst()
                    .orElseThrow();

            List<CodeUnit> classChildren = analyzer.getDirectChildren(classCu);
            assertTrue(classChildren.contains(intOverload), "Class should contain int overload child");
            assertTrue(classChildren.contains(stringOverload), "Class should contain String overload child");

            // 3. Assert lambda attachment
            // In TreeSitterAnalyzer, lambdas are attached to the nearest function-like parent.
            List<CodeUnit> intChildren = analyzer.getDirectChildren(intOverload);
            List<CodeUnit> stringChildren = analyzer.getDirectChildren(stringOverload);

            assertTrue(intChildren.isEmpty(), "m(int) should have no children");

            boolean hasLambda =
                    stringChildren.stream().anyMatch(cu -> cu.fqName().contains("m$anon$"));
            assertTrue(hasLambda, "m(String) should contain the synthetic lambda CodeUnit as a child");

            // Verify lambda metadata via FQN which contains the enclosing method name
            CodeUnit lambdaCu = stringChildren.stream()
                    .filter(cu -> cu.fqName().contains("m$anon$"))
                    .findFirst()
                    .get();

            assertTrue(lambdaCu.isFunction(), "Lambda should be a function kind");
            assertTrue(lambdaCu.isAnonymous(), "Lambda should be marked as anonymous");
        }
    }
}
