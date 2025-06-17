package io.github.jbellis.brokk.util;

import com.google.common.base.Splitter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>Represents a java stack trace.</p>
 *
 * <p>The first line contains the error that happened and all following lines (stack trace
 * elements) indicate which pieces of code lead to this error.</p>
 */
public class StackTrace {
    /**
     * The first line of the stack trace containing the error that happened.
     */
    private final String exceptionType;
    private final String originalStackTrace;

    /**
     * The stack trace lines of the stack trace indicating which pieces of code lead to the error mentioned at the first
     * line.
     */
    private final List<StackTraceElement> stackTraceLines;

    /**
     * Creates a new instance of {@code StackTrace}.
     *
     * @param firstLine       the first line of the stack trace containing the error that happened
     * @param stackTraceLines the stack trace lines of the stack trace indicating which pieces of code lead to the error
     *                        mentioned at the first line
     */
    public StackTrace(String firstLine, List<StackTraceElement> stackTraceLines, String originalStackTrace) {
        this.exceptionType = parseExceptionType(firstLine);
        this.stackTraceLines = stackTraceLines;
        this.originalStackTrace = originalStackTrace;
    }

    /**
     * Gets the exception type parsed from the first line of the stack trace.
     *
     * @return the exception type, or null if the first line couldn't be parsed
     */
    public String getExceptionType() {
        return this.exceptionType;
    }

    /**
     * Gets the stack trace lines of the stack trace.
     *
     * @return the stack trace lines of the stack trace indicating which pieces of code lead to the error mentioned at
     * the first line
     */
    public List<StackTraceElement> getFrames() {
        return this.stackTraceLines;
    }

    /**
     * Returns the original stack trace.
     *
     * @return the original stack trace
     */
    public String getOriginalText() {
        return originalStackTrace;
    }

    /**
     * Returns all lines of the stack trace of the specified package.
     *
     * @param packageName the package name to get the stack trace lines from
     * @return a List of StackTraceElements of the specified package
     */
    public List<StackTraceElement> getFrames(String packageName) {
        var linesOfPackage = new ArrayList<StackTraceElement>();
        
        // Remove leading slash if present
        String normalizedPackage = packageName.startsWith("/") ? packageName.substring(1) : packageName;

        for (StackTraceElement line : this.stackTraceLines) {
            String className = line.getClassName();
            // Handle module prefix like "java.base/java.util.concurrent"
            String[] parts = className.split("/", 2);
            String relevantPart = parts.length > 1 ? parts[1] : parts[0];
            
            if (relevantPart.startsWith(normalizedPackage)) {
                linesOfPackage.add(line);
            }
        }

        return linesOfPackage;
    }

    // A typical stack trace element looks like follows:
    // com.myPackage.myClass.myMethod(myClass.java:1)
    // component        example             allowed signs
    // ---------------- ------------------- ------------------------------------------------------------
    // package name:    com.myPackage       alphabetical / numbers
    // class name:      myClass             alphabetical / numbers / $-sign for anonymous inner classes
    // method name:     myMethod            alphabetical / numbers / $-sign for lambda expressions
    // file name:       myClass.java        alphabetical / numbers
    // line number:     1                   integer

    // The following lines show some example stack trace elements:
    // org.junit.Assert.fail(Assert.java:86)                                            // typical stack trace element
    // sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)                      // native method
    // org.junit.runners.ParentRunner$1.schedule(ParentRunner.java:71)                  // anonymous inner classes
    // org.junit.runners.ParentRunner.access$000(ParentRunner.java:58)                  // lambda expressions
    // org.apache.maven.surefire.junit4.JUnit4TestSet.execute(JUnit4TestSet.java:53)    // numbers for package and class names

    // Using the predefined structure of a stack trace element and allowed signs for its components, the following
    // regular expression can be used to parse stack trace elements and it's components. Parentheses ('(', ')') are used
    // to extract the components and '?:' is used to group the signs but not creating capture groups.
    //
    // Pattern groups:
    // Group 1: Full class and method name (e.g. "org.junit.Assert.fail")
    // Group 2: Source file (e.g. "Assert.java") or null
    // Group 3: Line number (e.g. "86") or null
    // Group 4: Special source (e.g. "Native Method") or null
    private static final String STACK_TRACE_LINE_REGEX = ".*\\s+at\\s+([^(]+)\\((?:([^:]+):([0-9]+)|([^)]+))\\).*$";
    private static final Pattern STACK_TRACE_LINE_PATTERN = Pattern.compile(STACK_TRACE_LINE_REGEX);
    
    private static @Nullable String parseExceptionType(String firstLine) {
        List<String> parts = Splitter.on(':').splitToList(firstLine);
        if (parts.isEmpty()) {
            return null;
        }
        
        List<String> typeParts = Splitter.on('.').splitToList(parts.get(0));
        return typeParts.getLast();
    }

    /**
     * Reads a java stack trace represented as a {@code String} and maps it to a {@code StackTrace} object with
     * {@code java.lang.StackTraceElement}s.
     *
     * @param stackTraceString the java stack trace as a {@code String}
     * @return a StackTrace containing the first (error) line and a list of {@code StackTraceElements},
     *         or null if no stack trace could be parsed
     */
    public static @Nullable StackTrace parse(String stackTraceString) {
        List<String> lines = Splitter.on('\n').splitToList(stackTraceString);
        
        // Find the exception line (which might have a prefix) and the first stack trace line
        // int exceptionLineIndex = -1; // Unused variable
        int firstStackLineIndex = -1;
        
        // First find a stack trace line ("... at ...")
        for (int i = 0; i < lines.size(); i++) {
            if (STACK_TRACE_LINE_PATTERN.matcher(lines.get(i)).matches()) {
                firstStackLineIndex = i;
                break;
            }
        }
        
        // If we didn't find a stack trace line, return null
        if (firstStackLineIndex == -1 || firstStackLineIndex == 0) {
            return null;
        }
        
        // Check the line before the stack trace line for exception type
        String firstLine = lines.get(firstStackLineIndex - 1);
        String exceptionType = parseExceptionType(firstLine);
        if (exceptionType == null) {
            return null;
        }
        
        // Parse stack trace lines
        List<StackTraceElement> stackTraceLines = new ArrayList<>();
        for (int i = firstStackLineIndex; i < lines.size(); i++) {
            Matcher matcher = STACK_TRACE_LINE_PATTERN.matcher(lines.get(i));
            if (!matcher.matches()) {
                continue;
            }
            
            String classAndMethod = matcher.group(1).trim();
            int lastDot = classAndMethod.lastIndexOf('.');
            String className = lastDot > 0 ? classAndMethod.substring(0, lastDot) : classAndMethod;
            String methodName = lastDot > 0 ? classAndMethod.substring(lastDot + 1) : "unknown";
            
                        String fileName = matcher.group(2);
            int lineNumber = -1;
            
            if (matcher.group(3) != null) {
                lineNumber = Integer.parseInt(matcher.group(3));
                        } else if (matcher.group(4) != null && matcher.group(4).equals("Native Method")) {
                lineNumber = -2;
            }

            StackTraceElement element = new StackTraceElement(
                    className,
                    methodName,
                    fileName,
                    lineNumber
            );

            stackTraceLines.add(element);
        }
        
        // Build original stack trace from the relevant lines only
        StringBuilder relevantTrace = new StringBuilder();
        relevantTrace.append(firstLine).append("\n");
        for (int i = firstStackLineIndex; i < lines.size(); i++) {
            if (STACK_TRACE_LINE_PATTERN.matcher(lines.get(i)).matches()) {
                relevantTrace.append(lines.get(i)).append("\n");
            }
        }
        String cleanedTrace = relevantTrace.length() > 0
                ? relevantTrace.substring(0, relevantTrace.length() - 1)
                : relevantTrace.toString();
        
        return new StackTrace(firstLine, stackTraceLines, cleanedTrace);
    }
}
