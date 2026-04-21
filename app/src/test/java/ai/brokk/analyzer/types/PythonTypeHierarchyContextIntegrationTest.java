package ai.brokk.analyzer.types;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.PythonAnalyzer;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.testutil.InlineTestProjectCreator;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public final class PythonTypeHierarchyContextIntegrationTest {

    @Test
    void testDiamondInheritanceSupportingFragments_areDeduplicatedInContext() throws Exception {
        var builder = InlineTestProjectCreator.code(
                        """
                class A:
                    def method_a(self):
                        pass
                """,
                        "diamond/a.py")
                .addFileContents(
                        """
                from .a import A

                class B(A):
                    def method_b(self):
                        pass
                """,
                        "diamond/b.py")
                .addFileContents(
                        """
                from .a import A

                class C(A):
                    def method_c(self):
                        pass
                """,
                        "diamond/c.py")
                .addFileContents(
                        """
                from .b import B
                from .c import C

                class D(B, C):
                    def method_d(self):
                        pass
                """,
                        "diamond/d.py")
                .addFileContents("# Package marker\n", "diamond/__init__.py");

        try (var testProject = builder.build()) {
            var testAnalyzer = new PythonAnalyzer(testProject);
            var cm = new TestContextManager(testProject.getRoot(), new TestConsoleIO(), testAnalyzer);

            var summaryFragment = new ContextFragments.SummaryFragment(
                    cm, "diamond.d.D", ContextFragment.SummaryType.CODEUNIT_SKELETON);

            var supporting = summaryFragment.supportingFragments();
            var supportingIdentifiers = supporting.stream()
                    .filter(f -> f instanceof ContextFragments.SummaryFragment)
                    .map(f -> ((ContextFragments.SummaryFragment) f).getTargetIdentifier())
                    .collect(Collectors.toSet());

            assertEquals(Set.of("diamond.b.B", "diamond.c.C"), supportingIdentifiers);

            var context = new Context(cm);
            context = context.addFragments(summaryFragment);

            var allFragments = context.allFragments().toList();
            var summaryFragmentsInContext = allFragments.stream()
                    .filter(f -> f instanceof ContextFragments.SummaryFragment)
                    .map(f -> ((ContextFragments.SummaryFragment) f).getTargetIdentifier())
                    .toList();

            var uniqueIdentifiers = new HashSet<>(summaryFragmentsInContext);
            assertEquals(uniqueIdentifiers.size(), summaryFragmentsInContext.size());
        }
    }
}
