package ai.brokk.difftool.ui.unified;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Point;
import java.awt.Rectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScrollCoordinateCalculatorTest {

    @Nested
    @DisplayName("Text Area Coordinate Range Calculation")
    class TextAreaCoordinateRange {

        @ParameterizedTest
        @CsvSource({
            "0, 100, 400, 200, 0, 0, 100, 300", // Zero viewport position
            "0, 0, 400, 200, 0, 150, 150, 350", // Normal scroll
            "0, 50, 400, 100, 0, -20, 30, 130" // Negative viewport position
        })
        @DisplayName("Various coordinate range scenarios")
        void variousCoordinateRangeScenarios(
                int clipX, int clipY, int clipW, int clipH, int viewX, int viewY, int expectedStart, int expectedEnd) {
            var clipBounds = new Rectangle(clipX, clipY, clipW, clipH);
            var viewPosition = new Point(viewX, viewY);

            var range = ScrollCoordinateCalculator.calculateTextAreaCoordinateRange(clipBounds, viewPosition);

            assertEquals(expectedStart, range.x, "startY");
            assertEquals(expectedEnd, range.y, "endY");
        }
    }

    @Nested
    @DisplayName("Coordinate Conversion")
    class CoordinateConversion {

        @ParameterizedTest
        @CsvSource({
            "200, 0, 200", // Zero viewport
            "200, 100, 100", // Normal scroll
            "50, -30, 80" // Negative viewport position
        })
        @DisplayName("Text area to row header coordinate conversion")
        void textAreaToRowHeaderConversion(int textAreaY, int viewportY, int expected) {
            var viewPosition = new Point(0, viewportY);

            int result = ScrollCoordinateCalculator.convertToRowHeaderCoordinate(textAreaY, viewPosition);

            assertEquals(expected, result);
        }
    }

    @Nested
    @DisplayName("Line Visibility Checks")
    class LineVisibilityChecks {

        @ParameterizedTest
        @CsvSource({
            "150, 20, true", // Completely visible
            "50, 20, false", // Above
            "350, 20, false", // Below
            "90, 20, true", // Partially visible top
            "290, 20, true", // Partially visible bottom
            "100, 20, true", // At top boundary
            "280, 20, true", // At bottom boundary
            "99, 1, false", // Just above
            "300, 1, false" // Just below
        })
        @DisplayName("Various line visibility scenarios")
        void variousLineVisibilityScenarios(int lineY, int lineHeight, boolean expectedVisible) {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200); // y=100 to y=300

            boolean result = ScrollCoordinateCalculator.isLineVisible(lineY, lineHeight, clipBounds);

            assertEquals(
                    expectedVisible,
                    result,
                    String.format(
                            "Line at y=%d height=%d should be %s in bounds %s",
                            lineY, lineHeight, expectedVisible ? "visible" : "not visible", clipBounds));
        }
    }

    @Nested
    @DisplayName("Coordinate Validation")
    class CoordinateValidation {

        @ParameterizedTest
        @CsvSource({
            "100, 20, 500, true", // Valid, within component
            "100, 0, 500, false", // Invalid line height (zero)
            "100, -5, 500, false", // Invalid line height (negative)
            "-100, 20, 500, false", // Far above component (beyond tolerance)
            "600, 20, 500, false", // Far below component (beyond tolerance)
            "-30, 20, 500, true", // Within tolerance above (tolerance = 40)
            "520, 20, 500, true" // Within tolerance below
        })
        @DisplayName("Coordinate validity scenarios")
        void coordinateValidityScenarios(int lineY, int lineHeight, int componentHeight, boolean expected) {
            assertEquals(expected, ScrollCoordinateCalculator.areCoordinatesValid(lineY, lineHeight, componentHeight));
        }
    }

    @Nested
    @DisplayName("Effective Paint Region")
    class EffectivePaintRegion {

        @Test
        @DisplayName("Clip bounds extending beyond component are clamped")
        void clipBoundsExtendingBeyondComponent() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 500);
            Point viewPosition = new Point(0, 50);

            Rectangle effective =
                    ScrollCoordinateCalculator.calculateEffectivePaintRegion(clipBounds, viewPosition, 300);

            assertEquals(new Rectangle(0, 100, 400, 200), effective);
        }

        @Test
        @DisplayName("Clip bounds starting before component are clamped")
        void clipBoundsStartingBeforeComponent() {
            Rectangle clipBounds = new Rectangle(0, -50, 400, 200);
            Point viewPosition = new Point(0, 50);

            Rectangle effective =
                    ScrollCoordinateCalculator.calculateEffectivePaintRegion(clipBounds, viewPosition, 500);

            assertEquals(new Rectangle(0, 0, 400, 150), effective);
        }
    }

    @Nested
    @DisplayName("Centered Viewport Calculation")
    class CenteredViewportCalculation {

        @Test
        @DisplayName("viewportHeight <= 0 fallback - zero height")
        void viewportHeightZeroFallback() {
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(150, 200, 0, 800);
            assertEquals(150, result);
        }

        @Test
        @DisplayName("viewportHeight <= 0 fallback - negative height")
        void viewportHeightNegativeFallback() {
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(150, 200, -50, 800);
            assertEquals(150, result);
        }

        @Test
        @DisplayName("viewportHeight <= 0 fallback with clamping at top")
        void viewportHeightZeroFallbackClampTop() {
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(-50, 100, 0, 800);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("viewportHeight <= 0 fallback with clamping at bottom")
        void viewportHeightZeroFallbackClampBottom() {
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(900, 1000, 0, 800);
            assertEquals(800, result);
        }

        @Test
        @DisplayName("Target larger than viewport - still centers on midpoint")
        void targetLargerThanViewport() {
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(100, 500, 100, 800);
            assertEquals(250, result);
        }

        @Test
        @DisplayName("maxY is zero - always returns 0")
        void maxYIsZero() {
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(100, 200, 200, 0);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("Negative maxY is treated as 0")
        void negativeMaxYTreatedAsZero() {
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(100, 200, 200, -1);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("Does not overflow when targetStartY + targetEndY would overflow int")
        void doesNotOverflowForVeryLargeCoordinates() {
            int start = Integer.MAX_VALUE - 10;
            int end = Integer.MAX_VALUE;
            int viewportHeight = 100;
            int maxY = Integer.MAX_VALUE;

            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(start, end, viewportHeight, maxY);
            assertEquals(Integer.MAX_VALUE - 55, result);
        }

        @ParameterizedTest
        @CsvSource({
            "400, 450, 200, 800, 325", // Simple centering
            "0, 50, 200, 800, 0", // Clamp at top
            "750, 800, 200, 600, 600", // Clamp at bottom
            "500, 500, 100, 1000, 450", // Single point
            "200, 600, 200, 1000, 300" // Large range
        })
        @DisplayName("Parameterized centering scenarios")
        void parameterizedCenteringScenarios(
                int targetStart, int targetEnd, int viewportHeight, int maxY, int expected) {
            int result =
                    ScrollCoordinateCalculator.calculateCenteredViewportY(targetStart, targetEnd, viewportHeight, maxY);
            assertEquals(
                    expected,
                    result,
                    String.format(
                            "Center [%d,%d] in viewport %d with maxY %d should be %d",
                            targetStart, targetEnd, viewportHeight, maxY, expected));
        }

        @Test
        @DisplayName("Inverted range produces same result as non-inverted")
        void invertedRangeProducesSameResult() {
            int normal = ScrollCoordinateCalculator.calculateCenteredViewportY(100, 500, 200, 800);
            int inverted = ScrollCoordinateCalculator.calculateCenteredViewportY(500, 100, 200, 800);
            assertEquals(normal, inverted, "Inverted range should produce identical result to non-inverted");
            assertEquals(200, normal);
        }
    }

    @Nested
    @DisplayName("Visible Line Range")
    class VisibleLineRange {

        @ParameterizedTest
        @CsvSource({
            "10, 20, true, 11", // Normal range
            "-1, 20, false, 0", // Negative start
            "20, 10, false, 0", // End before start
            "5, 5, true, 1", // Single line
            "0, -1, false, 0" // End negative
        })
        @DisplayName("Validity and line count")
        void validityAndLineCount(int startLine, int endLine, boolean expectedValid, int expectedCount) {
            var range = ScrollCoordinateCalculator.createVisibleLineRange(startLine, endLine);

            assertEquals(expectedValid, range.isValid());
            assertEquals(expectedCount, range.getLineCount());
        }

        @Test
        @DisplayName("toString contains start, end, count, and validity")
        void toStringContainsExpectedValues() {
            var range = ScrollCoordinateCalculator.createVisibleLineRange(10, 20);

            var str = range.toString();
            assertTrue(str.contains("10"), "should contain start line");
            assertTrue(str.contains("20"), "should contain end line");
            assertTrue(str.contains("11"), "should contain line count");
            assertTrue(str.contains("true"), "should contain validity");
        }
    }
}
