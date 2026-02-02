package ai.brokk.util;

import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a java stack trace.
 *
 * <p>The first line contains the error that happened and all following lines (stack trace elements) indicate which
 * pieces of code lead to this error.
 */
public class StackTrace {
    /** The first line of the stack trace containing the error that happened. */
    @Nullable
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
     * @param firstLine the first line of the stack trace containing the error that happened
     * @param stackTraceLines the stack trace lines of the stack trace indicating which pieces of code lead to the error
     *     mentioned at the first line
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
    public @Nullable String getExceptionType() {
        return this.exceptionType;
    }

    /**
     * Gets the stack trace lines of the stack trace.
     *
     * @return the stack trace lines of the stack trace indicating which pieces of code lead to the error mentioned at
     *     the first line
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
    // org.apache.maven.surefire.junit4.JUnit4TestSet.execute(JUnit4TestSet.java:53)    // numbers for package and class
    // names

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

    // Jackson metadata lines look like: "at [Source: ...]" or "at [through reference chain: ...]"
    private static final Pattern JACKSON_METADATA_PATTERN = Pattern.compile(".*\\s+at\\s+\\[.*\\](?:\\s+\\(.*\\))?.*$");

    private static final Pattern EXCEPTION_TOKEN_PATTERN =
            Pattern.compile("([A-Za-z0-9_.$/]+(?:Exception|Error))(?:[:\\s].*)?$");

    private static final Pattern LAMBDA_METHOD_PATTERN = Pattern.compile("^lambda\\$(.+?)\\$\\d+$");

    private static String normalizeLineForParsing(String line) {
        return line.stripTrailing();
    }

    private static String normalizeMethodName(String methodName) {
        Matcher matcher = LAMBDA_METHOD_PATTERN.matcher(methodName);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return methodName;
    }

    private static @Nullable String parseExceptionType(String line) {
        String header = normalizeLineForParsing(line).strip();
        if (header.isEmpty()) {
            return null;
        }

        if (JACKSON_METADATA_PATTERN.matcher(header).matches() || header.startsWith("at ")) {
            return null;
        }

        int lastDash = header.lastIndexOf(" - ");
        if (lastDash != -1) {
            header = header.substring(lastDash + 3).strip();
        }

        int closingBracket = header.indexOf(']');
        if (header.startsWith("[") && closingBracket != -1 && closingBracket < 32) {
            header = header.substring(closingBracket + 1).strip();
        }

        Matcher tokenMatcher = EXCEPTION_TOKEN_PATTERN.matcher(header);
        String lastToken = null;
        while (tokenMatcher.find()) {
            lastToken = tokenMatcher.group(1);
        }
        if (lastToken != null) {
            int lastSlash = lastToken.lastIndexOf('/');
            String withoutModule = lastSlash == -1 ? lastToken : lastToken.substring(lastSlash + 1);
            int lastDot = withoutModule.lastIndexOf('.');
            return lastDot == -1 ? withoutModule : withoutModule.substring(lastDot + 1);
        }

        List<String> parts = Splitter.on(':').splitToList(header);
        if (parts.isEmpty()) {
            return null;
        }

        String typePart = parts.getFirst().trim();
        int lastSpace = typePart.lastIndexOf(' ');
        if (lastSpace != -1) {
            typePart = typePart.substring(lastSpace + 1);
        }

        List<String> dots = Splitter.on('.').splitToList(typePart);
        if (dots.isEmpty()) {
            return null;
        }

        String name = dots.getLast().trim();
        return name.endsWith("Exception") || name.endsWith("Error") ? name : null;
    }

    private static @Nullable String findExceptionLine(
            List<String> lines, int firstAtLine, int firstStandardFrameIndex) {
        for (int i = firstAtLine - 1; i >= 0; i--) {
            String candidate = normalizeLineForParsing(lines.get(i));
            if (parseExceptionType(candidate) != null) {
                return candidate;
            }
        }

        int forwardLimit = firstStandardFrameIndex != -1 ? firstStandardFrameIndex : lines.size();
        for (int i = firstAtLine; i < forwardLimit; i++) {
            String candidate = normalizeLineForParsing(lines.get(i));
            if (parseExceptionType(candidate) != null) {
                return candidate;
            }
        }

        for (int i = 0; i < lines.size(); i++) {
            String candidate = normalizeLineForParsing(lines.get(i));
            if (parseExceptionType(candidate) != null) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Reads a java stack trace represented as a {@code String} and maps it to a {@code StackTrace} object with
     * {@code java.lang.StackTraceElement}s.
     *
     * @param stackTraceString the java stack trace as a {@code String}
     * @return a StackTrace containing the first (error) line and a list of {@code StackTraceElements}, or null if no
     *     stack trace could be parsed
     */
    public static @Nullable StackTrace parse(String stackTraceString) {
        List<String> lines = Splitter.onPattern("\\R").splitToList(stackTraceString);

        int firstStandardFrameIndex = -1;
        int firstJacksonLineIndex = -1;

        for (int i = 0; i < lines.size(); i++) {
            String line = normalizeLineForParsing(lines.get(i));

            if (firstJacksonLineIndex == -1
                    && JACKSON_METADATA_PATTERN.matcher(line).matches()) {
                firstJacksonLineIndex = i;
            }

            if (firstStandardFrameIndex == -1
                    && STACK_TRACE_LINE_PATTERN.matcher(line).matches()
                    && !JACKSON_METADATA_PATTERN.matcher(line).matches()) {
                firstStandardFrameIndex = i;
            }

            if (firstJacksonLineIndex != -1 && firstStandardFrameIndex != -1) {
                break;
            }
        }

        if (firstStandardFrameIndex == -1 && firstJacksonLineIndex == -1) {
            return null;
        }

        int firstAtLine;
        if (firstStandardFrameIndex != -1 && firstJacksonLineIndex != -1) {
            firstAtLine = Math.min(firstStandardFrameIndex, firstJacksonLineIndex);
        } else if (firstStandardFrameIndex != -1) {
            firstAtLine = firstStandardFrameIndex;
        } else {
            firstAtLine = firstJacksonLineIndex;
        }

        String exceptionLine = findExceptionLine(lines, firstAtLine, firstStandardFrameIndex);
        if (exceptionLine == null) {
            return null;
        }

        List<StackTraceElement> stackTraceLines = new ArrayList<>();
        if (firstStandardFrameIndex != -1) {
            for (int i = firstStandardFrameIndex; i < lines.size(); i++) {
                String line = normalizeLineForParsing(lines.get(i));
                Matcher matcher = STACK_TRACE_LINE_PATTERN.matcher(line);
                if (!matcher.matches() || JACKSON_METADATA_PATTERN.matcher(line).matches()) {
                    continue;
                }

                String classAndMethod = matcher.group(1).trim();
                int lastDot = classAndMethod.lastIndexOf('.');
                String className = lastDot > 0 ? classAndMethod.substring(0, lastDot) : classAndMethod;
                String methodName = lastDot > 0 ? classAndMethod.substring(lastDot + 1) : "unknown";
                methodName = normalizeMethodName(methodName);

                String fileName = matcher.group(2);
                int lineNumber = -1;

                if (matcher.group(3) != null) {
                    lineNumber = Integer.parseInt(matcher.group(3));
                } else if ("Native Method".equals(matcher.group(4))) {
                    lineNumber = -2;
                }

                stackTraceLines.add(new StackTraceElement(className, methodName, fileName, lineNumber));
            }
        }

        var relevantLines = new ArrayList<String>();
        relevantLines.add(exceptionLine);
        for (int i = firstAtLine; i < lines.size(); i++) {
            String line = normalizeLineForParsing(lines.get(i));
            if (STACK_TRACE_LINE_PATTERN.matcher(line).matches()
                    || JACKSON_METADATA_PATTERN.matcher(line).matches()) {
                relevantLines.add(line);
            }
        }

        return new StackTrace(exceptionLine, stackTraceLines, String.join("\n", relevantLines));
    }
}
