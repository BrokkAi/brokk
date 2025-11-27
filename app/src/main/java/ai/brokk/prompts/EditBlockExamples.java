package ai.brokk.prompts;

import java.util.ArrayList;
import java.util.Set;

public class EditBlockExamples {
    /**
     * Builds example SEARCH/REPLACE blocks that demonstrate correct formatting.
     *
     * <p>The examples use a single Java source file (src/main/java/com/acme/Foo.java) and show: - A "Before" workspace
     * excerpt (when MERGE_AGENT_MARKERS is enabled, this includes an actual conflict block) - A line-based SEARCH edit
     * - Optional syntax-aware edits (BRK_FUNCTION and BRK_CLASS) when enabled - A full-file replacement using
     * BRK_ENTIRE_FILE - Optional conflict-range fix using BRK_CONFLICT_1 when MERGE_AGENT_MARKERS is enabled
     *
     * <p>The examples are illustrative only and intended to show the exact wire format, not working diffs against a
     * real repository.
     */
    static String buildExamples(Set<CodePrompts.InstructionsFlags> flags) {
        var parts = new ArrayList<String>();

        // ---------- BEFORE: current workspace excerpt ----------
        // If MERGE_AGENT_MARKERS is enabled, show an actual conflict block in the file.
        var before = flags.contains(CodePrompts.InstructionsFlags.MERGE_AGENT_MARKERS)
                ? """
                ### Before: Current Workspace excerpt (with conflict markers present)

                <workspace_example>
                  <file path="src/main/java/com/acme/Foo.java" fragmentid="1">
                  package com.acme;

                  import java.util.List;
                  import java.util.Objects;

                  public class Foo {
                      public int compute(int a, int b) {
                          // naive implementation
                          return a + b;
                      }

                      /** A friendly greeting. */
                      public String greet(String name) {
                          return "Hello, " + name + "!";
                      }

                      // The Merge Agent has wrapped a Git-style conflict inside custom markers.
                      BRK_CONFLICT_BEGIN_1
                      <<<<<<< HEAD
                      private static int fib(int n) {
                          if (n <= 1) return n;
                          return fib(n - 1) + fib(n - 2);
                      }
                      =======
                      private static int fib(int n) {
                          if (n < 2) return n;
                          int a = 0, b = 1;
                          for (int i = 2; i <= n; i++) {
                              int tmp = a + b;
                              a = b;
                              b = tmp;
                          }
                          return b;
                      }
                      >>>>>>> feature/iterative-fib
                      BRK_CONFLICT_END_1
                  }
                  </file>
                </workspace_example>
                """
                : """
                ### Before: Current Workspace excerpt

                <workspace_example>
                  <file path="src/main/java/com/acme/Foo.java" fragmentid="1">
                  package com.acme;

                  import java.util.List;
                  import java.util.Objects;

                  public class Foo {
                      public int compute(int a, int b) {
                          // naive implementation
                          return a + b;
                      }

                      /** A friendly greeting. */
                      public String greet(String name) {
                          return "Hello, " + name + "!";
                      }

                      private static int fib(int n) {
                          if (n <= 1) return n;
                          return fib(n - 1) + fib(n - 2);
                      }
                  }
                  </file>
                </workspace_example>
                """;

        parts.add(before);

        int ex = 1;

        // ---------- Example 1: Line-based SEARCH ----------
        parts.add(
                """
                        ### Example %d — Line-based SEARCH (modify a fragment outside of a method)

                        ```
                        src/main/java/com/acme/Foo.java
                        <<<<<<< SEARCH
                        import java.util.List;
                        import java.util.Objects;
                        =======
                        import java.util.List;
                        >>>>>>> REPLACE
                        ```
                        """
                        .formatted(ex++));

        // ---------- Syntax-aware examples (only if enabled) ----------
        if (flags.contains(CodePrompts.InstructionsFlags.SYNTAX_AWARE)) {
            // BRK_FUNCTION: replace a single method by fully qualified name
            parts.add(
                    """
                            ### Example %d — Syntax-aware SEARCH for a function (BRK_FUNCTION)

                            This replaces the method's signature and body, including the header comment block (e.g., JavaDoc).

                            ```
                            src/main/java/com/acme/Foo.java
                            <<<<<<< SEARCH
                            BRK_FUNCTION com.acme.Foo.greet
                            =======
                            /**
                             * Returns a greeting for the given name.
                             * @param name the name to greet.
                             * @return the greeting string.
                             */
                            public String greet(String name) {
                                return "Hi, " + name;
                            }
                            >>>>>>> REPLACE
                            ```
                            """
                            .formatted(ex++));

            // BRK_CLASS: replace the entire class body by fully qualified name
            // Note: For BRK_CLASS, provide the class block (not package/imports) as the replacement.
            parts.add(
                    """
                            ### Example %d — Syntax-aware SEARCH for an entire class (BRK_CLASS)

                            ```
                            src/main/java/com/acme/Foo.java
                            <<<<<<< SEARCH
                            BRK_CLASS com.acme.Foo
                            =======
                            public class Foo {
                                public int compute(int a, int b) {
                                    return Math.addExact(a, b);
                                }

                                public String greet(String name) {
                                    return "Hello, " + name + "!";
                                }

                                private static int fib(int n) {
                                    if (n < 2) return n;
                                    int a = 0, b = 1;
                                    for (int i = 2; i <= n; i++) {
                                        int tmp = a + b;
                                        a = b;
                                        b = tmp;
                                    }
                                    return b;
                                }
                            }
                            >>>>>>> REPLACE
                            ```
                            """
                            .formatted(ex++));
        }

        // ---------- Example: Full-file replacement using BRK_ENTIRE_FILE ----------
        parts.add(
                """
                        ### Example %d — Full-file replacement (BRK_ENTIRE_FILE)

                        ```
                        src/main/java/com/acme/Foo.java
                        <<<<<<< SEARCH
                        BRK_ENTIRE_FILE
                        =======
                        package com.acme;

                        public class Foo {
                            public int compute(int a, int b) {
                                return Math.addExact(a, b);
                            }

                            public String greet(String name) {
                                return "Hello, " + name + "!";
                            }

                            private static int fib(int n) {
                                if (n < 2) return n;
                                int a = 0, b = 1;
                                for (int i = 2; i <= n; i++) {
                                    int next = Math.addExact(a, b);
                                    a = b;
                                    b = next;
                                }
                                return b;
                            }
                        }
                        >>>>>>> REPLACE
                        ```
                        """
                        .formatted(ex++));

        // ---------- Conflict-range fix (only if enabled) ----------
        if (flags.contains(CodePrompts.InstructionsFlags.MERGE_AGENT_MARKERS)) {
            parts.add(
                    """
                            ### Example %d — Conflict range fix (BRK_CONFLICT markers)

                            The SEARCH is a **single line** that targets the entire conflict region, regardless of its contents.
                            Replace that region with the resolved implementation.

                            ```
                            src/main/java/com/acme/Foo.java
                            <<<<<<< SEARCH
                            BRK_CONFLICT_1
                            =======
                            private static int fib(int n) {
                                if (n < 2) return n;
                                int a = 0, b = 1;
                                for (int i = 2; i <= n; i++) {
                                    int tmp = Math.addExact(a, b);
                                    a = b;
                                    b = tmp;
                                }
                                return b;
                            }
                            >>>>>>> REPLACE
                            ```
                            """
                            .formatted(ex++));
        }

        return String.join("\n\n", parts).strip();
    }
}
