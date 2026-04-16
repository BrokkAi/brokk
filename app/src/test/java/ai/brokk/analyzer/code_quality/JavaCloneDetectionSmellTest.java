package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import org.junit.jupiter.api.Test;

public class JavaCloneDetectionSmellTest extends AbstractCloneDetectionSmellTest {

    @Test
    void flagsRenamedVariableClonesAcrossFiles() {
        String a =
                """
                package com.example;
                class Alpha {
                    int compute(int input) {
                        int total = input + 1;
                        if (total > 10) {
                            return total * 2;
                        }
                        return total - 3;
                    }
                }
                """;
        String b =
                """
                package com.example;
                class Beta {
                    int calculate(int seed) {
                        int amount = seed + 1;
                        if (amount > 10) {
                            return amount * 2;
                        }
                        return amount - 3;
                    }
                }
                """;
        var findings = analyze("com/example/Alpha.java", a, "com/example/Beta.java", b);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream()
                .anyMatch(f -> f.enclosingFqName().contains("Alpha.compute")
                        && f.peerEnclosingFqName().contains("Beta.calculate")));
    }

    @Test
    void astRefinementSuppressesDifferentControlFlow() {
        String a =
                """
                package com.example;
                class Alpha {
                    int compute(int input) {
                        int total = input + 1;
                        if (total > 10) {
                            total = total * 2;
                        } else {
                            total = total - 3;
                        }
                        return total;
                    }
                }
                """;
        String b =
                """
                package com.example;
                class Beta {
                    int calculate(int seed) {
                        int amount = seed + 1;
                        while (amount > 10) {
                            amount = amount - 1;
                        }
                        amount = amount * 2;
                        return amount;
                    }
                }
                """;
        var strictWeights = new IAnalyzer.CloneSmellWeights(12, 50, 2, 3, 85);
        var findings = analyze("com/example/Alpha.java", a, "com/example/Beta.java", b, strictWeights);
        assertTrue(
                findings.isEmpty(),
                "Expected Java AST refinement to reject token-similar but structurally different methods");
    }

    @Test
    void treatsExtraLoggingAsEquivalentClone() {
        String a =
                """
                package com.example;
                class Alpha {
                    int compute(int input) {
                        int total = input + 1;
                        if (total > 10) {
                            return total * 2;
                        }
                        return total - 3;
                    }
                }
                """;
        String b =
                """
                package com.example;
                class Beta {
                    int calculate(int seed) {
                        log(seed);
                        int amount = seed + 1;
                        if (amount > 10) {
                            log(amount);
                            return amount * 2;
                        }
                        log(amount - 3);
                        return amount - 3;
                    }
                    void log(int value) {}
                }
                """;
        var lenient = new IAnalyzer.CloneSmellWeights(12, 55, 2, 3, 70);
        var findings = analyze("com/example/Alpha.java", a, "com/example/Beta.java", b, lenient);
        assertTrue(findings.stream()
                .anyMatch(f -> f.enclosingFqName().contains("Alpha.compute")
                        && f.peerEnclosingFqName().contains("Beta.calculate")));
    }

    @Test
    void treatsTryCatchWrappedVariantAsEquivalentClone() {
        String a =
                """
                package com.example;
                class Alpha {
                    int compute(int input) {
                        int total = input + 1;
                        if (total > 10) {
                            return total * 2;
                        }
                        return total - 3;
                    }
                }
                """;
        String b =
                """
                package com.example;
                class Beta {
                    int calculate(int seed) {
                        try {
                            int amount = seed + 1;
                            if (amount > 10) {
                                return amount * 2;
                            }
                            return amount - 3;
                        } catch (RuntimeException e) {
                            throw e;
                        }
                    }
                }
                """;
        var lenient = new IAnalyzer.CloneSmellWeights(12, 50, 2, 3, 65);
        var findings = analyze("com/example/Alpha.java", a, "com/example/Beta.java", b, lenient);
        assertTrue(findings.stream()
                .anyMatch(f -> f.enclosingFqName().contains("Alpha.compute")
                        && f.peerEnclosingFqName().contains("Beta.calculate")));
    }
}
