package ai.brokk.analyzer.usages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class LocalUsageInferenceTest {

    private static final ProjectFile FILE =
            new ProjectFile(Path.of("/tmp").normalize().toAbsolutePath(), "test.ts");
    private static final CodeUnit ENCLOSING = CodeUnit.module(FILE, "", "test.ts");
    private static final IAnalyzer.Range RANGE = new IAnalyzer.Range(0, 1, 0, 0, 0);

    @Test
    public void directSeed_resolvesReceiverAccess() {
        var result = LocalUsageInference.infer(List.of(
                new LocalUsageEvent.DeclareSymbol("x"),
                new LocalUsageEvent.SeedSymbol("x", Set.of(target("a", "Foo", true, 0.95))),
                new LocalUsageEvent.ReceiverAccess("x", "bar", ReferenceKind.METHOD_CALL, RANGE, ENCLOSING)));

        assertEquals(1, result.size());
        var resolved = result.iterator().next();
        assertEquals("bar", resolved.identifier());
        assertEquals("Foo", resolved.receiverTarget().exportedName());
    }

    @Test
    public void aliasPropagation_multiHopDegradesConfidence() {
        var result = LocalUsageInference.infer(List.of(
                new LocalUsageEvent.DeclareSymbol("x"),
                new LocalUsageEvent.SeedSymbol("x", Set.of(target("a", "Foo", true, 0.95))),
                new LocalUsageEvent.DeclareSymbol("y"),
                new LocalUsageEvent.AliasSymbol("y", "x"),
                new LocalUsageEvent.DeclareSymbol("z"),
                new LocalUsageEvent.AliasSymbol("z", "y"),
                new LocalUsageEvent.ReceiverAccess("x", "bar", ReferenceKind.METHOD_CALL, RANGE, ENCLOSING),
                new LocalUsageEvent.ReceiverAccess("y", "bar", ReferenceKind.METHOD_CALL, RANGE, ENCLOSING),
                new LocalUsageEvent.ReceiverAccess("z", "bar", ReferenceKind.METHOD_CALL, RANGE, ENCLOSING)));

        var confidences = result.stream()
                .map(ResolvedReceiverCandidate::confidence)
                .sorted()
                .toList();
        assertEquals(3, confidences.size());
        assertTrue(confidences.get(2) > confidences.get(1));
        assertTrue(confidences.get(1) > confidences.get(0));
    }

    @Test
    public void innerScopeDeclaration_blocksOuterSeededSymbol() {
        var result = LocalUsageInference.infer(List.of(
                new LocalUsageEvent.DeclareSymbol("x"),
                new LocalUsageEvent.SeedSymbol("x", Set.of(target("a", "Foo", true, 0.95))),
                new LocalUsageEvent.EnterScope(),
                new LocalUsageEvent.DeclareSymbol("x"),
                new LocalUsageEvent.ReceiverAccess("x", "bar", ReferenceKind.METHOD_CALL, RANGE, ENCLOSING),
                new LocalUsageEvent.ExitScope(),
                new LocalUsageEvent.ReceiverAccess("x", "bar", ReferenceKind.METHOD_CALL, RANGE, ENCLOSING)));

        assertEquals(1, result.size());
    }

    @Test
    public void targetsOverCap_areDropped() {
        var result = LocalUsageInference.infer(
                List.of(
                        new LocalUsageEvent.DeclareSymbol("x"),
                        new LocalUsageEvent.SeedSymbol(
                                "x",
                                Set.of(
                                        target("a", "A", true, 0.95),
                                        target("a", "B", true, 0.95),
                                        target("a", "C", true, 0.95),
                                        target("a", "D", true, 0.95),
                                        target("a", "E", true, 0.95))),
                        new LocalUsageEvent.ReceiverAccess("x", "bar", ReferenceKind.METHOD_CALL, RANGE, ENCLOSING)),
                LocalUsageInference.Limits.defaults());

        assertEquals(0, result.size());
    }

    @Test
    public void ambiguityPenalty_lowersConfidenceForWiderTargetSets() {
        var result = LocalUsageInference.infer(List.of(
                new LocalUsageEvent.DeclareSymbol("x"),
                new LocalUsageEvent.SeedSymbol("x", Set.of(target("a", "Foo", true, 0.95))),
                new LocalUsageEvent.ReceiverAccess("x", "bar", ReferenceKind.METHOD_CALL, RANGE, ENCLOSING),
                new LocalUsageEvent.DeclareSymbol("y"),
                new LocalUsageEvent.SeedSymbol(
                        "y", Set.of(target("a", "Foo", true, 0.95), target("a", "Bar", true, 0.95))),
                new LocalUsageEvent.ReceiverAccess("y", "bar", ReferenceKind.METHOD_CALL, RANGE, ENCLOSING)));

        var confidences = result.stream()
                .map(ResolvedReceiverCandidate::confidence)
                .sorted()
                .toList();
        assertEquals(3, confidences.size());
        assertTrue(confidences.get(2) > confidences.get(1));
        assertEquals(confidences.get(1), confidences.get(0));
    }

    private static ReceiverTargetRef target(
            String moduleSpecifier, String exportedName, boolean instanceReceiver, double confidence) {
        return new ReceiverTargetRef(moduleSpecifier, exportedName, instanceReceiver, confidence);
    }
}
