package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.project.IProject;
import ai.brokk.testutil.InlineTestProjectCreator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class JavaUsagesTest {
    private IProject project;
    private JavaAnalyzer analyzer;

    @BeforeEach
    public void setup() throws Exception {
        project = InlineTestProjectCreator.code(
                        """
            package com.example;
            /** Javadoc with MyClass reference */
            public class MyClass {
                private int myField;
                public void myMethod(int myParam) {
                    int myLocal = 10;
                    myMethod(myParam);
                    this.myField = 5;
                    new MyClass();
                }
            }
            """,
                        "com/example/MyClass.java")
                .build();
        analyzer = new JavaAnalyzer(project);
    }

    @AfterEach
    public void teardown() throws Exception {
        project.close();
    }

    @Test
    public void testIsDeclarationReference() {
        ProjectFile file = project.getAnalyzableFiles(Languages.JAVA).iterator().next();
        String content = file.read().orElse("");

        // 1. Method declaration name "myMethod"
        assertIsReference(file, content, "myMethod(int myParam)", false);

        // 2. Parameter name "myParam"
        assertIsReference(file, content, "myParam) {", false);

        // 3. Local variable name "myLocal"
        assertIsReference(file, content, "myLocal = 10", false);

        // 4. Comment/Javadoc
        assertIsReference(file, content, "MyClass reference", false);

        // 5. Method call
        assertIsReference(file, content, "myMethod(myParam);", true);

        // 6. Field access
        assertIsReference(file, content, "myField = 5", true);

        // 7. Object creation
        assertIsReference(file, content, "MyClass();", true);
    }

    private void assertIsReference(ProjectFile file, String content, String snippet, boolean expected) {
        int start = content.indexOf(snippet);
        assertTrue(start >= 0, "Snippet not found: " + snippet);

        // Isolate the identifier part of the snippet if needed, but here we'll just check the start
        // To be precise, we find the first alphanumeric word in the snippet
        int end = start;
        while (end < content.length() && Character.isJavaIdentifierPart(content.charAt(end))) {
            end++;
        }

        int startByte = content.substring(0, start).getBytes(StandardCharsets.UTF_8).length;
        int endByte = content.substring(0, end).getBytes(StandardCharsets.UTF_8).length;

        boolean actual = analyzer.isDeclarationReference(file, startByte, endByte);
        if (expected) {
            assertTrue(actual, "Expected reference at: " + snippet);
        } else {
            assertFalse(actual, "Expected non-reference (declaration/comment) at: " + snippet);
        }
    }
}
