package ai.brokk.analyzer.complexity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.testutil.InlineCoreProject;
import org.junit.jupiter.api.Test;

public class JavaCognitiveComplexityTest {

    @Test
    void testSimpleMethodComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method() {}
                }
                """;
        assertComplexity(code, "com.example.Test.method", 0);
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
        assertComplexity(code, "com.example.Test.method", 1);
    }

    @Test
    void testNestedIfComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method(boolean a, boolean b) {
                        if (a) {
                            if (b) {
                                System.out.println("b");
                            }
                        }
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.method", 3);
    }

    @Test
    void testElseIfDoesNotAddNestingComplexity() {
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
        assertComplexity(code, "com.example.Test.method", 2);
    }

    @Test
    void testPmdLoopAndIfExampleComplexity() {
        String code =
                """
                package com.example;
                import java.util.List;
                public class Test {
                    public void updateContacts(List<Contact> contacts) {
                        for (Contact contact : contacts) {
                            if (contact.department.equals("Finance")) {
                                contact.title = "Finance Specialist";
                            } else if (contact.department.equals("Sales")) {
                                contact.title = "Sales Specialist";
                            }
                        }
                    }
                    static class Contact {
                        String department;
                        String title;
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.updateContacts", 4);
    }

    @Test
    void testSwitchCasesComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method(int x) {
                        switch (x) {
                            case 1: break;
                            case 2: break;
                            default: break;
                        }
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.method", 2);
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
        assertComplexity(code, "com.example.Test.method", 1);
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
        assertComplexity(code, "com.example.Test.method", 1);
    }

    @Test
    void testBooleanOperatorSequencesComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public void method(boolean a, boolean b, boolean c) {
                        if (a && b || c) {}
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.method", 3);
    }

    @Test
    void testConstructorComplexity() {
        String code =
                """
                package com.example;
                public class Test {
                    public Test(boolean enabled) {
                        if (enabled) {
                            System.out.println("enabled");
                        }
                    }
                }
                """;
        assertComplexity(code, "com.example.Test.Test", 1);
    }

    private void assertComplexity(String source, String fqName, int expected) {
        try (var testProject =
                InlineCoreProject.code(source, "com/example/Test.java").build()) {
            IAnalyzer analyzer = testProject.getAnalyzer();
            CodeUnit cu = analyzer.getDefinitions(fqName).stream()
                    .filter(CodeUnit::isFunction)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Could not find function: " + fqName));

            int actual = analyzer.computeCognitiveComplexity(cu);
            assertEquals(expected, actual, "Complexity for " + fqName + " mismatch");
        }
    }
}
