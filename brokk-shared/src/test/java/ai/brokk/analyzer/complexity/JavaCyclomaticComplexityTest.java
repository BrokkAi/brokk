package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class JavaCyclomaticComplexityTest {

    @Test
    void testSimpleMethodComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method() {}
                }
                """;
        assertComplexity(code, "com.example.Test.method", 1);
    }

    @Test
    void testIfComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method(boolean a) {
                        if (a) System.out.println("a");
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.method", 2);
    }

    @Test
    void testIfElseComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method(int x) {
                        if (x > 0) {}
                        else if (x < 0) {}
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.method", 3);
    }

    @Test
    void testBooleanLogicComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method(boolean a, boolean b) {
                        if (a && b) {}
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.method", 3);
    }

    @Test
    void testLoopComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method() {
                        for (int i = 0; i < 10; i++) {}
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.method", 2);
    }

    @Test
    void testSwitchComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method(int x) {
                        switch (x) {
                            case 1: break;
                            case 2: break;
                            case 3: break;
                            default: break;
                        }
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.method", 4);
    }

    @Test
    void testTryCatchComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method() {
                        try {
                        } catch (Exception e) {
                        }
                    }
                }
                """;
        // try-catch adds 1 for the catch block
        assertComplexity(code, "com.example.Test.method", 2);
    }

    @Test
    void testTernaryComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public int method(boolean a) {
                        return a ? 1 : 0;
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.method", 2);
    }

    private void assertComplexity(String source, String fqName, int expected) {
        try (var testProject =
                InlineCoreProject.code(source, "com/example/Test.java").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            CodeUnit cu = analyzer.getDefinitions(fqName).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Could not find function: " + fqName));

            int actual = analyzer.computeCyclomaticComplexity(cu);
            assertEquals(expected, actual, "Complexity for " + fqName + " mismatch");
        }
    }
}
