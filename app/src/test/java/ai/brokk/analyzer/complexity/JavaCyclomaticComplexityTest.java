package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.ITestProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JavaCyclomaticComplexityTest {

    private ITestProject testProject;
    private IAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        String javaSource =
                """
            package com.example;

            public class ComplexityExample {
                // Complexity: 1 (Base)
                public void simpleMethod() {
                    System.out.println("Hello");
                }

                // Complexity: 2 (if)
                public void ifMethod(boolean a) {
                    if (a) {
                        System.out.println("a");
                    }
                }

                // Complexity: 3 (if + else if)
                public void ifElseMethod(int x) {
                    if (x > 0) {
                        System.out.println("pos");
                    } else if (x < 0) {
                        System.out.println("neg");
                    }
                }

                // Complexity: 3 (if + &&)
                public void booleanLogic(boolean a, boolean b) {
                    if (a && b) {
                        System.out.println("both");
                    }
                }

                // Complexity: 2 (for loop)
                public void loopMethod() {
                    for (int i = 0; i < 10; i++) {
                        System.out.println(i);
                    }
                }

                // Complexity: 4 (switch with 3 cases, default doesn't count)
                public void switchMethod(int x) {
                    switch (x) {
                        case 1: break;
                        case 2: break;
                        case 3: break;
                        default: break;
                    }
                }

                // Complexity: 3 (try-catch)
                public void tryCatchMethod() {
                    try {
                        System.out.println("try");
                    } catch (Exception e) {
                        System.out.println("catch");
                    }
                }

                // Complexity: 2 (ternary)
                public int ternaryMethod(boolean a) {
                    return a ? 1 : 0;
                }
            }
            """;

        testProject = InlineTestProjectCreator.code(javaSource, "com/example/ComplexityExample.java")
                .build();
        analyzer = testProject.getAnalyzer();
    }

    @AfterEach
    void tearDown() {
        if (testProject != null) {
            testProject.close();
        }
    }

    @Test
    void testSimpleMethodComplexity() {
        assertComplexity("com.example.ComplexityExample.simpleMethod", 1);
    }

    @Test
    void testIfComplexity() {
        assertComplexity("com.example.ComplexityExample.ifMethod", 2);
    }

    @Test
    void testIfElseComplexity() {
        assertComplexity("com.example.ComplexityExample.ifElseMethod", 3);
    }

    @Test
    void testBooleanLogicComplexity() {
        assertComplexity("com.example.ComplexityExample.booleanLogic", 3);
    }

    @Test
    void testLoopComplexity() {
        assertComplexity("com.example.ComplexityExample.loopMethod", 2);
    }

    @Test
    void testSwitchComplexity() {
        assertComplexity("com.example.ComplexityExample.switchMethod", 4);
    }

    @Test
    void testTryCatchComplexity() {
        assertComplexity("com.example.ComplexityExample.tryCatchMethod", 2);
    }

    @Test
    void testTernaryComplexity() {
        assertComplexity("com.example.ComplexityExample.ternaryMethod", 2);
    }

    private void assertComplexity(String fqName, int expected) {
        CodeUnit cu = analyzer.getDefinitions(fqName).stream()
                .filter(CodeUnit::isFunction)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Could not find function: " + fqName));

        int actual = analyzer.computeCyclomaticComplexity(cu);
        assertEquals(expected, actual, "Complexity for " + fqName + " mismatch");
    }
}
