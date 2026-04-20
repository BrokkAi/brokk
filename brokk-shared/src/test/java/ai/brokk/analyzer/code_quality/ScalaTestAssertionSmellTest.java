package ai.brokk.analyzer.code_quality;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.InlineCoreProject;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ScalaTestAssertionSmellTest extends AbstractBrittleTestSuite {

    @Test
    void flagsSelfComparisonAssertion() {
        String code =
                """
                package com.example

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.scalatest.funsuite.AnyFunSuite

                class SampleTest extends AnyFunSuite {
                  test("same value") {
                    val value = "x"
                    assertEquals(value, value)
                  }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "self-comparison"), findings.toString());
    }

    @Test
    void flagsConstantTruthAndConstantEquality() {
        String code =
                """
                package com.example

                import org.scalatest.funsuite.AnyFunSuite

                class SampleTest extends AnyFunSuite {
                  test("constants") {
                    assert(true)
                    assert(1 == 1)
                  }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "constant-truth"), findings.toString());
        assertTrue(hasReason(findings, "constant-equality"), findings.toString());
    }

    @Test
    void flagsTestWithNoAssertions() {
        String code =
                """
                package com.example

                import org.scalatest.funsuite.AnyFunSuite

                class SampleTest extends AnyFunSuite {
                  test("no assertions") {
                    helper()
                  }
                  def helper(): Int = 1
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "no-assertions"));
    }

    @Test
    void meaningfulAssertionIsNotFlaggedWithDefaultWeights() {
        String code =
                """
                package com.example

                import org.scalatest.funsuite.AnyFunSuite

                class SampleTest extends AnyFunSuite {
                  test("meaningful") {
                    val result = Result("expected")
                    assert(result.name == "expected")
                  }
                }

                case class Result(name: String)
                """;
        var findings = analyze(code);
        assertTrue(findings.isEmpty());
    }

    @Test
    void flagsOnlyNullnessAssertionAsShallow() {
        String code =
                """
                package com.example

                import org.junit.jupiter.api.Assertions.assertNotNull
                import org.scalatest.funsuite.AnyFunSuite

                class SampleTest extends AnyFunSuite {
                  test("nullness") {
                    val result: Object = new Object()
                    assertNotNull(result)
                  }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "nullness-only"), findings.toString());
        assertTrue(hasReason(findings, "shallow-assertions-only"), findings.toString());
    }

    @Test
    void flagsOverspecifiedLiteral() {
        String code =
                """
                package com.example

                import org.scalatest.funsuite.AnyFunSuite

                class SampleTest extends AnyFunSuite {
                  test("overspecified") {
                    val result = "value"
                    assert(result == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                  }
                }
                """;
        var findings = analyze(code);
        assertTrue(hasReason(findings, "overspecified-literal"));
    }

    @Override
    protected String defaultTestPath() {
        return "com/example/SampleTest.scala";
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
