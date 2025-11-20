package ai.brokk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.brokk.util.IndentUtil;
import org.junit.jupiter.api.Test;

class IndentUtilTest {

    @Test
    void testComputeIndentScale_FromSteps() {
        // Equal steps -> scale 1.0
        assertEquals(1.0, IndentUtil.computeIndentScale(4, 4), 1e-9);
        // Larger target step -> scale > 1.0
        assertEquals(2.0, IndentUtil.computeIndentScale(8, 4), 1e-9);
        // Smaller target step -> scale < 1.0
        assertEquals(0.5, IndentUtil.computeIndentScale(2, 4), 1e-9);

        // Non-positive steps -> default to 1.0
        assertEquals(1.0, IndentUtil.computeIndentScale(0, 4), 1e-9);
        assertEquals(1.0, IndentUtil.computeIndentScale(-1, 4), 1e-9);
        assertEquals(1.0, IndentUtil.computeIndentScale(4, 0), 1e-9);
        assertEquals(1.0, IndentUtil.computeIndentScale(4, -1), 1e-9);
    }

    @Test
    void testComputeIndentScale_FromLines() {
        String[] target = {"sig() {", "    body;"};
        String[] replace = {"sig() {", "    body;"};

        int baseTargetIndent = IndentUtil.countLeadingWhitespace(target[0]);
        int baseReplaceIndent = IndentUtil.countLeadingWhitespace(replace[0]);

        // Both have body step = 4 -> scale 1.0
        assertEquals(1.0, IndentUtil.computeIndentScale(target, replace, baseTargetIndent, baseReplaceIndent), 1e-9);

        // Replacement body indents by 4, target indents by 8 -> scale 2.0
        String[] target2 = {"sig() {", "        body;"};
        assertEquals(2.0, IndentUtil.computeIndentScale(target2, replace, baseTargetIndent, baseReplaceIndent), 1e-9);

        // No body line -> scale falls back to 1.0
        String[] target3 = {"sig() {"};
        assertEquals(1.0, IndentUtil.computeIndentScale(target3, replace, baseTargetIndent, baseReplaceIndent), 1e-9);
    }

    @Test
    void testCountLeadingWhitespace() {
        assertEquals(0, IndentUtil.countLeadingWhitespace("noindent"));
        assertEquals(4, IndentUtil.countLeadingWhitespace("    four"));
        assertEquals(2, IndentUtil.countLeadingWhitespace("  two "));
        assertEquals(1, IndentUtil.countLeadingWhitespace("\tbody")); // tab counts as whitespace
        assertEquals(0, IndentUtil.countLeadingWhitespace(""));
    }

    @Test
    void testFindFirstIndentStep() {
        String[] lines1 = {"sig() {", "", "    body;"};
        int base = IndentUtil.countLeadingWhitespace(lines1[0]);
        assertEquals(4, IndentUtil.findFirstIndentStep(lines1, base));

        String[] lines2 = {"sig() {", "  ", "\tbody;"};
        base = IndentUtil.countLeadingWhitespace(lines2[0]);
        // tab is one whitespace char
        assertEquals(1, IndentUtil.findFirstIndentStep(lines2, base));

        String[] lines3 = {"sig() {"};
        base = IndentUtil.countLeadingWhitespace(lines3[0]);
        assertEquals(-1, IndentUtil.findFirstIndentStep(lines3, base));
    }

    @Test
    void testCountLeadingBlankLines() {
        String[] lines1 = {"", "  ", "x"};
        assertEquals(2, IndentUtil.countLeadingBlankLines(lines1));

        String[] lines2 = {"x", "", ""};
        assertEquals(0, IndentUtil.countLeadingBlankLines(lines2));

        String[] lines3 = {};
        assertEquals(0, IndentUtil.countLeadingBlankLines(lines3));
    }
}
