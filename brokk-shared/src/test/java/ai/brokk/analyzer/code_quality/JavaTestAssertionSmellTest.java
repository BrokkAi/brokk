package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class JavaTestAssertionSmellTest extends AbstractBrittleTestSuite {

    @Test
    void flagsSelfComparisonAssertion() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class SampleTest {
                    @Test
                    void sameValue() {
                        String value = "x";
                        assertEquals(value, value);
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "self-comparison"));
    }

    @Test
    void flagsConstantTruthAndConstantEquality() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class SampleTest {
                    @Test
                    void constants() {
                        assertTrue(true);
                        assertEquals(1, 1);
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "constant-truth"));
        assertTrue(hasReason(findings, "constant-equality"));
    }

    @Test
    void flagsTestMethodWithNoAssertions() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;

                public class SampleTest {
                    @Test
                    void noAssertions() {
                        new Service().run();
                    }
                    static class Service {
                        void run() {}
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "no-assertions"));
    }

    @Test
    void meaningfulAssertionIsNotFlaggedWithDefaultWeights() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class SampleTest {
                    @Test
                    void meaningful() {
                        Result result = new Result("expected");
                        assertEquals("expected", result.name());
                    }
                    record Result(String name) {}
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Test
    void flagsOnlyNullnessAssertionAsShallow() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertNotNull;

                public class SampleTest {
                    @Test
                    void shallow() {
                        Object result = new Object();
                        assertNotNull(result);
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "nullness-only"));
        assertTrue(hasReason(findings, "shallow-assertions-only"));
    }

    @Test
    void flagsAnonymousTestDouble() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class SampleTest {
                    interface Clock {
                        long now();
                    }

                    @Test
                    void anonymousDouble() {
                        Clock clock = new Clock() {
                            @Override
                            public long now() {
                                return 42;
                            }
                        };
                        assertEquals(42, clock.now());
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "anonymous-test-double"));
    }

    @Test
    void repeatedAnonymousTestDoublesScoreHigherThanSingleUse() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class SampleTest {
                    interface Clock {
                        long now();
                    }

                    @Test
                    void first() {
                        Clock clock = new Clock() {
                            @Override
                            public long now() {
                                return 42;
                            }
                        };
                        assertEquals(42, clock.now());
                    }

                    @Test
                    void second() {
                        Clock clock = new Clock() {
                            @Override
                            public long now() {
                                return 43;
                            }
                        };
                        assertEquals(43, clock.now());
                    }
                }
                """;
        var findings = analyze(code);
        var anonymousFindings = findings.stream()
                .filter(f -> f.reasons().contains("anonymous-test-double"))
                .toList();
        assertEquals(2, anonymousFindings.size());
        assertTrue(anonymousFindings.stream().allMatch(f -> f.reasons().contains("reusable-test-double-candidate")));
        assertTrue(anonymousFindings.stream()
                .allMatch(f ->
                        f.score() == IAnalyzer.TestAssertionWeights.defaults().repeatedAnonymousTestDoubleWeight()));
    }

    @Test
    void nonTestJavaFileIsSkipped() {
        String code =
                """
                package com.example;
                public class Sample {
                    void assertLookingName() {
                        assertEquals(1, 1);
                    }
                    void assertEquals(int expected, int actual) {}
                }
                """;
        var findings = analyze(code, "com/example/Sample.java");
        assertTrue(findings.isEmpty());
    }

    @Test
    void weightTuningCanSuppressFindings() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;

                public class SampleTest {
                    @Test
                    void constant() {
                        assertTrue(true);
                    }
                }
                """;
        var defaults = analyze(code);
        var tunedWeights = new IAnalyzer.TestAssertionWeights(0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 4, 120);
        var tuned = analyze(code, tunedWeights);
        assertFalse(defaults.isEmpty(), "Default heuristics should flag constant truth");
        assertEquals(0, tuned.size(), "Zeroed smell weights should suppress the same finding");
    }

    @Test
    void flagsAssertJTautologiesAndConstants() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;

                public class SampleTest {
                    @Test
                    void assertj() {
                        String value = "x";
                        assertThat(value).isEqualTo(value);
                        assertThat(true).isTrue();
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "self-comparison"));
        assertTrue(hasReason(findings, "constant-truth"));
    }

    @Test
    void mockitoVerifyCountsAsAssertionEquivalent() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.mockito.Mockito.mock;
                import static org.mockito.Mockito.verify;

                public class SampleTest {
                    interface Sink {
                        void send(String value);
                    }

                    @Test
                    void verifiesInteraction() {
                        Sink sink = mock(Sink.class);
                        sink.send("value");
                        verify(sink).send("value");
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Test
    void flagsOverspecifiedLargeLiteral() {
        String literal = "a".repeat(defaultWeights().largeLiteralLengthThreshold());
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class SampleTest {
                    @Test
                    void largeLiteral() {
                        assertEquals("%s", result());
                    }

                    String result() {
                        return "";
                    }
                }
                """
                        .formatted(literal);
        var findings = analyze(code);
        assertTrue(hasReason(findings, "overspecified-literal"));
    }

    @Test
    void assertThrowsCountsAsAssertionEquivalent() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertThrows;

                public class SampleTest {
                    @Test
                    void throwsMeaningfully() {
                        assertThrows(IllegalArgumentException.class, () -> {
                            throw new IllegalArgumentException("boom");
                        });
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Test
    void junitTrailingMessageDoesNotHideExpectedAndActual() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class SampleTest {
                    @Test
                    void trailingMessage() {
                        assertEquals("expected", "expected", "message");
                    }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "constant-equality"));
    }

    @Test
    void assertJChainedExtractionCountsAsAssertionEquivalent() {
        String code =
                """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.assertj.core.api.Assertions.assertThat;

                public class SampleTest {
                    @Test
                    void chainedAssertJ() {
                        Result result = new Result("expected");
                        assertThat(result).extracting(Result::name).isEqualTo("expected");
                    }
                    record Result(String name) {}
                }
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Override
    protected List<IAnalyzer.TestAssertionSmell> analyze(
            String source, String path, IAnalyzer.TestAssertionWeights weights) {
        try (var testProject = InlineCoreProject.code(source, path).build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            ProjectFile file = new ProjectFile(testProject.getRoot(), path);
            return analyzer.findTestAssertionSmells(file, weights);
        }
    }
}
