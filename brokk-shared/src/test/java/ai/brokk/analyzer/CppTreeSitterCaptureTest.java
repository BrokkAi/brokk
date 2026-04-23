package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.analyzer.cpp.Constants;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCpp;

public class CppTreeSitterCaptureTest {
    @Test
    void capturesTestMarkerForCommonMacroShapes() throws IOException {
        List<String> sources = List.of(
                """
                #include <gtest/gtest.h>

                TEST(SampleTest, NoAssertions) {
                    int value = 42;
                    value++;
                }
                """,
                """
                #include <gtest/gtest.h>

                TEST /* comment */ (SampleTest, NoAssertions) {
                    int value = 42;
                    value++;
                }
                """,
                """
                BOOST_AUTO_TEST_CASE(NoAssertions) {
                    int value = 42;
                    value++;
                }
                """,
                """
                TEST_CASE("NoAssertions") {
                    int value = 42;
                    value++;
                }
                """,
                """
                SCENARIO("NoAssertions") {
                    int value = 42;
                    value++;
                }
                """,
                """
                TEST_CLASS(SampleTests) {
                public:
                    TEST_METHOD(NoAssertions) {
                        int value = 42;
                        value++;
                    }
                };
                """);

        TSQuery query = loadDefinitionsQuery();
        for (int i = 0; i < sources.size(); i++) {
            String source = sources.get(i);
            SourceContent content = SourceContent.of(source);
            try (TSTree tree = parse(source)) {
                TSNode root = tree.getRootNode();
                assertNotNull(root, "rootNode");
                List<Capture> captures = capturesFor(query, root, content);
                boolean hasTestMarker = captures.stream().anyMatch(c -> Constants.TEST_MARKER_CAPTURE.equals(c.name()));

                Path dumpDir = Path.of("build", "treesitter-dumps");
                Files.createDirectories(dumpDir);
                Path dumpPath = dumpDir.resolve("cpp-case-" + i + ".txt");
                Files.writeString(
                        dumpPath,
                        ("=== case " + i + " ===\n"
                                + source
                                + "\n--- root ---\n"
                                + root
                                + "\n--- captures ---\n"
                                + captures.stream().map(Object::toString).reduce("", (a, b) -> a + b + "\n")),
                        StandardCharsets.UTF_8);

                assertTrue(hasTestMarker, "expected @" + Constants.TEST_MARKER_CAPTURE + " capture for case " + i);
            }
        }
    }

    private static TSTree parse(String source) {
        var parser = new TSParser();
        parser.setLanguage(new TreeSitterCpp());
        TSTree tree = parser.parseString(null, source);
        assertNotNull(tree, "tree");
        return tree;
    }

    private static TSQuery loadDefinitionsQuery() throws IOException {
        try (InputStream in =
                CppTreeSitterCaptureTest.class.getClassLoader().getResourceAsStream("treesitter/cpp/definitions.scm")) {
            assertNotNull(in, "treesitter/cpp/definitions.scm");
            String scm = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(scm.isBlank(), "definitions.scm");
            return new TSQuery(new TreeSitterCpp(), scm);
        }
    }

    private static List<Capture> capturesFor(TSQuery query, TSNode root, SourceContent content) {
        var out = new ArrayList<Capture>();
        try (TSQueryCursor cursor = new TSQueryCursor()) {
            cursor.exec(query, root, content.text());
            TSQueryMatch match = new TSQueryMatch();
            while (cursor.nextMatch(match)) {
                for (TSQueryCapture capture : match.getCaptures()) {
                    TSNode node = capture.getNode();
                    if (node == null) {
                        continue;
                    }
                    String captureName = query.getCaptureNameForId(capture.getIndex());
                    out.add(new Capture(
                            captureName,
                            node.getType(),
                            node.getStartByte(),
                            node.getEndByte(),
                            content.substringFrom(node).strip()));
                }
            }
        }
        return out;
    }

    private record Capture(String name, String nodeType, int startByte, int endByte, String text) {}
}
