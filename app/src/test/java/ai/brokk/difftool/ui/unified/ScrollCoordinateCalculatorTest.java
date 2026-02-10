package ai.brokk.difftool.ui.unified;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Point;
import java.awt.Rectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprehensive headless tests for ScrollCoordinateCalculator. These tests verify the coordinate calculation logic that
 * was causing scrolling issues without requiring any GUI components.
 */
class ScrollCoordinateCalculatorTest {

    @Nested
    @DisplayName("Text Area Coordinate Range Calculation")
    class TextAreaCoordinateRange {

        @Test
        @DisplayName("Basic coordinate range calculation")
        void basicCoordinateRangeCalculation() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200);
            Point viewPosition = new Point(0, 50);

            Point range = ScrollCoordinateCalculator.calculateTextAreaCoordinateRange(clipBounds, viewPosition);

            assertEquals(150, range.x, "Start Y should be clipBounds.y + viewPosition.y");
            assertEquals(350, range.y, "End Y should be start + clipBounds.height");
        }

        @Test
        @DisplayName("Zero viewport position")
        void zeroViewportPosition() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200);
            Point viewPosition = new Point(0, 0);

            Point range = ScrollCoordinateCalculator.calculateTextAreaCoordinateRange(clipBounds, viewPosition);

            assertEquals(100, range.x);
            assertEquals(300, range.y);
        }

        @Test
        @DisplayName("Negative viewport position")
        void negativeViewportPosition() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200);
            Point viewPosition = new Point(0, -25);

            Point range = ScrollCoordinateCalculator.calculateTextAreaCoordinateRange(clipBounds, viewPosition);

            assertEquals(75, range.x);
            assertEquals(275, range.y);
        }

        @ParameterizedTest
        @CsvSource({
            "0, 0, 400, 200, 0, 0, 0, 200", // Top of document
            "0, 100, 400, 200, 0, 50, 150, 350", // Middle scroll
            "0, 50, 400, 150, 0, 100, 150, 300", // Different clip size
            "0, 0, 400, 100, 0, 500, 500, 600" // Large scroll offset
        })
        @DisplayName("Various coordinate scenarios")
        void variousCoordinateScenarios(
                int clipX, int clipY, int clipW, int clipH, int viewX, int viewY, int expectedStart, int expectedEnd) {
            Rectangle clipBounds = new Rectangle(clipX, clipY, clipW, clipH);
            Point viewPosition = new Point(viewX, viewY);

            Point range = ScrollCoordinateCalculator.calculateTextAreaCoordinateRange(clipBounds, viewPosition);

            assertEquals(expectedStart, range.x);
            assertEquals(expectedEnd, range.y);
        }
    }

    @Nested
    @DisplayName("Coordinate Conversion")
    class CoordinateConversion {

        @Test
        @DisplayName("Text area to row header coordinate conversion")
        void textAreaToRowHeaderConversion() {
            Point viewPosition = new Point(0, 100);

            int textAreaY = 250;
            int rowHeaderY = ScrollCoordinateCalculator.convertToRowHeaderCoordinate(textAreaY, viewPosition);

            assertEquals(150, rowHeaderY, "Row header Y should be textArea Y minus viewport Y");
        }

        @Test
        @DisplayName("Zero viewport conversion")
        void zeroViewportConversion() {
            Point viewPosition = new Point(0, 0);

            int textAreaY = 100;
            int rowHeaderY = ScrollCoordinateCalculator.convertToRowHeaderCoordinate(textAreaY, viewPosition);

            assertEquals(100, rowHeaderY, "With zero viewport, coordinates should be identical");
        }

        @ParameterizedTest
        @CsvSource({
            "100, 50, 50", // Normal scroll
            "100, 0, 100", // No scroll
            "100, 100, 0", // Equal offset
            "50, 100, -50", // Negative result (line above viewport)
            "200, 75, 125" // Various positions
        })
        @DisplayName("Conversion with various viewport positions")
        void conversionWithVariousViewports(int textAreaY, int viewportY, int expected) {
            Point viewPosition = new Point(0, viewportY);

            int result = ScrollCoordinateCalculator.convertToRowHeaderCoordinate(textAreaY, viewPosition);

            assertEquals(expected, result);
        }
    }

    @Nested
    @DisplayName("Line Visibility Checks")
    class LineVisibilityChecks {

        @Test
        @DisplayName("Fully visible line")
        void fullyVisibleLine() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200);

            // Line completely within bounds
            assertTrue(ScrollCoordinateCalculator.isLineVisible(150, 20, clipBounds));
        }

        @Test
        @DisplayName("Line above visible area")
        void lineAboveVisibleArea() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200);

            // Line completely above clip bounds
            assertFalse(ScrollCoordinateCalculator.isLineVisible(50, 20, clipBounds));
        }

        @Test
        @DisplayName("Line below visible area")
        void lineBelowVisibleArea() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200);

            // Line completely below clip bounds (clipBounds ends at y=300)
            assertFalse(ScrollCoordinateCalculator.isLineVisible(350, 20, clipBounds));
        }

        @Test
        @DisplayName("Partially visible line at top")
        void partiallyVisibleLineAtTop() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200);

            // Line starts above but extends into visible area
            assertTrue(ScrollCoordinateCalculator.isLineVisible(90, 20, clipBounds));
        }

        @Test
        @DisplayName("Partially visible line at bottom")
        void partiallyVisibleLineAtBottom() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200);

            // Line starts in visible area but extends below
            assertTrue(ScrollCoordinateCalculator.isLineVisible(290, 20, clipBounds));
        }

        @Test
        @DisplayName("Line exactly at boundary")
        void lineExactlyAtBoundary() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200); // ends at y=300

            // Line exactly at top boundary
            assertTrue(ScrollCoordinateCalculator.isLineVisible(100, 20, clipBounds));

            // Line exactly at bottom boundary
            assertTrue(ScrollCoordinateCalculator.isLineVisible(280, 20, clipBounds));
        }

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

        @Test
        @DisplayName("Valid coordinates")
        void validCoordinates() {
            assertTrue(ScrollCoordinateCalculator.areCoordinatesValid(100, 20, 500));
        }

        @Test
        @DisplayName("Invalid line height")
        void invalidLineHeight() {
            assertFalse(ScrollCoordinateCalculator.areCoordinatesValid(100, 0, 500));
            assertFalse(ScrollCoordinateCalculator.areCoordinatesValid(100, -5, 500));
        }

        @Test
        @DisplayName("Line far above component")
        void lineFarAboveComponent() {
            // Line more than tolerance above (tolerance = lineHeight * 2)
            assertFalse(ScrollCoordinateCalculator.areCoordinatesValid(-100, 20, 500));
        }

        @Test
        @DisplayName("Line far below component")
        void lineFarBelowComponent() {
            // Line more than tolerance below component height
            assertFalse(ScrollCoordinateCalculator.areCoordinatesValid(600, 20, 500));
        }

        @Test
        @DisplayName("Line within tolerance above component")
        void lineWithinToleranceAbove() {
            // Line within tolerance above (tolerance = lineHeight * 2 = 40)
            assertTrue(ScrollCoordinateCalculator.areCoordinatesValid(-30, 20, 500));
        }

        @Test
        @DisplayName("Line within tolerance below component")
        void lineWithinToleranceBelow() {
            // Line within tolerance below component height
            assertTrue(ScrollCoordinateCalculator.areCoordinatesValid(520, 20, 500));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -10})
        @DisplayName("Invalid line heights")
        void invalidLineHeights(int invalidHeight) {
            assertFalse(ScrollCoordinateCalculator.areCoordinatesValid(100, invalidHeight, 500));
        }
    }

    @Nested
    @DisplayName("Effective Paint Region")
    class EffectivePaintRegion {

        @Test
        @DisplayName("Clip bounds within component")
        void clipBoundsWithinComponent() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 200);
            Point viewPosition = new Point(0, 50);

            Rectangle effective =
                    ScrollCoordinateCalculator.calculateEffectivePaintRegion(clipBounds, viewPosition, 500);

            assertEquals(new Rectangle(0, 100, 400, 200), effective);
        }

        @Test
        @DisplayName("Clip bounds extending beyond component")
        void clipBoundsExtendingBeyondComponent() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 500); // extends beyond component
            Point viewPosition = new Point(0, 50);

            Rectangle effective = ScrollCoordinateCalculator.calculateEffectivePaintRegion(
                    clipBounds, viewPosition, 300); // component height = 300

            assertEquals(new Rectangle(0, 100, 400, 200), effective); // clipped to component height
        }

        @Test
        @DisplayName("Clip bounds starting before component")
        void clipBoundsStartingBeforeComponent() {
            Rectangle clipBounds = new Rectangle(0, -50, 400, 200);
            Point viewPosition = new Point(0, 50);

            Rectangle effective =
                    ScrollCoordinateCalculator.calculateEffectivePaintRegion(clipBounds, viewPosition, 500);

            assertEquals(new Rectangle(0, 0, 400, 150), effective); // clipped to start at 0
        }
    }

    @Nested
    @DisplayName("Visible Line Range")
    class VisibleLineRange {

        @Test
        @DisplayName("Valid line range")
        void validLineRange() {
            var range = ScrollCoordinateCalculator.createVisibleLineRange(10, 20);

            assertTrue(range.isValid());
            assertEquals(10, range.getStartLine());
            assertEquals(20, range.getEndLine());
            assertEquals(11, range.getLineCount());
        }

        @Test
        @DisplayName("Invalid line range - negative start")
        void invalidLineRangeNegativeStart() {
            var range = ScrollCoordinateCalculator.createVisibleLineRange(-1, 20);

            assertFalse(range.isValid());
            assertEquals(0, range.getLineCount());
        }

        @Test
        @DisplayName("Invalid line range - end before start")
        void invalidLineRangeEndBeforeStart() {
            var range = ScrollCoordinateCalculator.createVisibleLineRange(20, 10);

            assertFalse(range.isValid());
            assertEquals(0, range.getLineCount());
        }

        @Test
        @DisplayName("Single line range")
        void singleLineRange() {
            var range = ScrollCoordinateCalculator.createVisibleLineRange(5, 5);

            assertTrue(range.isValid());
            assertEquals(1, range.getLineCount());
        }

        @Test
        @DisplayName("Empty valid range")
        void emptyValidRange() {
            var range = ScrollCoordinateCalculator.createVisibleLineRange(0, -1);

            assertFalse(range.isValid());
            assertEquals(0, range.getLineCount());
        }

        @Test
        @DisplayName("Range toString contains useful information")
        void rangeToStringContainsUsefulInformation() {
            var range = ScrollCoordinateCalculator.createVisibleLineRange(10, 15);

            String str = range.toString();
            assertTrue(str.contains("10"));
            assertTrue(str.contains("15"));
            assertTrue(str.contains("6")); // line count
            assertTrue(str.contains("true")); // valid
        }
    }

    @Nested
    @DisplayName("Centered Viewport Calculation")
    class CenteredViewportCalculation {

        @Test
        @DisplayName("Simple centering in middle of scroll range")
        void simpleCenteringInMiddle() {
            // Target region at y=400-450 (midpoint 425), viewport height 200, content allows scrolling to 800
            // Centered: 425 - 100 = 325
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(400, 450, 200, 800);
            assertEquals(325, result);
        }

        @Test
        @DisplayName("Larger target range - multi-line hunk block")
        void largerTargetRange() {
            // Target region at y=300-500 (midpoint 400), viewport height 200
            // Centered: 400 - 100 = 300
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(300, 500, 200, 1000);
            assertEquals(300, result);
        }

        @Test
        @DisplayName("Clamping at top - target near beginning")
        void clampingAtTop() {
            // Target region at y=10-30 (midpoint 20), viewport height 200
            // Centered: 20 - 100 = -80, clamped to 0
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(10, 30, 200, 800);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("Clamping at bottom - target near end")
        void clampingAtBottom() {
            // Target region at y=780-800 (midpoint 790), viewport height 200, maxY=600
            // Centered: 790 - 100 = 690, clamped to 600
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(780, 800, 200, 600);
            assertEquals(600, result);
        }

        @Test
        @DisplayName("viewportHeight <= 0 fallback - zero height")
        void viewportHeightZeroFallback() {
            // With zero viewport height, should fallback to clamped targetStartY
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(150, 200, 0, 800);
            assertEquals(150, result);
        }

        @Test
        @DisplayName("viewportHeight <= 0 fallback - negative height")
        void viewportHeightNegativeFallback() {
            // With negative viewport height, should fallback to clamped targetStartY
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(150, 200, -50, 800);
            assertEquals(150, result);
        }

        @Test
        @DisplayName("viewportHeight <= 0 fallback with clamping at top")
        void viewportHeightZeroFallbackClampTop() {
            // Fallback to targetStartY, but targetStartY is negative - clamp to 0
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(-50, 100, 0, 800);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("viewportHeight <= 0 fallback with clamping at bottom")
        void viewportHeightZeroFallbackClampBottom() {
            // Fallback to targetStartY, but targetStartY exceeds maxY - clamp to maxY
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(900, 1000, 0, 800);
            assertEquals(800, result);
        }

        @Test
        @DisplayName("Target region equals single point")
        void singlePointTarget() {
            // Target at y=400-400 (midpoint 400), viewport height 200
            // Centered: 400 - 100 = 300
            int result = ScrollCoordinateCalculator.calculateCenteredViewportY(400, 400, 200, 800);
            assertEquals(300, result);
        }

        @Test
        @DisplayName("Target larger than viewport - still centers on midpoint")
        void targetLargerThanViewport() {
            // Target region at y=100-500 (midpoint 300), viewport height 100
            // Centered: 300 - 50 = 250
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
        @DisplayName("Does not overflow when targetStartY + targetEndY would overflow int")
        void doesNotOverflowForVeryLargeCoordinates() {
            int start = Integer.MAX_VALUE - 10;
            int end = Integer.MAX_VALUE;
            int viewportHeight = 100;
            int maxY = Integer.MAX_VALUE;

            // Midpoint is MAX-5; centered is (MAX-5) - 50 = MAX-55
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
            // Inverted: (500, 100) should behave the same as (100, 500)
            int normal = ScrollCoordinateCalculator.calculateCenteredViewportY(100, 500, 200, 800);
            int inverted = ScrollCoordinateCalculator.calculateCenteredViewportY(500, 100, 200, 800);
            assertEquals(normal, inverted, "Inverted range should produce identical result to non-inverted");

            // Verify the expected centered value: midpoint 300, centered at 300 - 100 = 200
            assertEquals(200, normal);
        }
    }

    @Nested
    @DisplayName("Edge Cases and Stress Tests")
    class EdgeCasesAndStressTests {

        @Test
        @DisplayName("Zero-sized clip bounds")
        void zeroSizedClipBounds() {
            Rectangle clipBounds = new Rectangle(0, 100, 400, 0);
            Point viewPosition = new Point(0, 50);

            Point range = ScrollCoordinateCalculator.calculateTextAreaCoordinateRange(clipBounds, viewPosition);

            assertEquals(150, range.x);
            assertEquals(150, range.y); // start equals end for zero height
        }

        @Test
        @DisplayName("Very large coordinates")
        void veryLargeCoordinates() {
            Rectangle clipBounds = new Rectangle(0, 100000, 400, 200000);
            Point viewPosition = new Point(0, 50000);

            Point range = ScrollCoordinateCalculator.calculateTextAreaCoordinateRange(clipBounds, viewPosition);

            assertEquals(150000, range.x);
            assertEquals(350000, range.y);
        }

        @Test
        @DisplayName("Maximum integer values")
        void maximumIntegerValues() {
            // Test with values near Integer.MAX_VALUE
            Rectangle clipBounds = new Rectangle(0, 1000000, 400, 1000000);
            Point viewPosition = new Point(0, 1000000);

            assertDoesNotThrow(() -> {
                ScrollCoordinateCalculator.calculateTextAreaCoordinateRange(clipBounds, viewPosition);
            });
        }

        @Test
        @DisplayName("Rapid scroll simulation")
        void rapidScrollSimulation() {
            Rectangle clipBounds = new Rectangle(0, 0, 400, 200);

            // Simulate rapid scrolling through different positions
            for (int scroll = 0; scroll < 1000; scroll += 10) {
                final int currentScroll = scroll; // Make effectively final for lambda
                Point viewPosition = new Point(0, currentScroll);

                assertDoesNotThrow(() -> {
                    Point range = ScrollCoordinateCalculator.calculateTextAreaCoordinateRange(clipBounds, viewPosition);
                    assertEquals(currentScroll, range.x);
                    assertEquals(currentScroll + 200, range.y);
                });
            }
        }
    }
}
