package io.github.jbellis.brokk.difftool.ui.unified;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Headless tests for UnifiedDiffColorResolver utility class. These tests verify color logic without requiring GUI
 * components.
 */
class UnifiedDiffColorResolverTest {

    @Nested
    @DisplayName("Background Color Resolution")
    class BackgroundColorResolution {

        @Test
        @DisplayName("Addition lines return green colors")
        void additionLinesReturnGreenColors() {
            Color lightColor =
                    UnifiedDiffColorResolver.getBackgroundColor(UnifiedDiffDocument.LineType.ADDITION, false);
            Color darkColor = UnifiedDiffColorResolver.getBackgroundColor(UnifiedDiffDocument.LineType.ADDITION, true);

            assertNotNull(lightColor, "Light theme addition color should not be null");
            assertNotNull(darkColor, "Dark theme addition color should not be null");

            // Verify green dominance (green component should be highest or equal highest)
            assertTrue(
                    lightColor.getGreen() >= lightColor.getRed(), "Light addition color should have green dominance");
            assertTrue(
                    lightColor.getGreen() >= lightColor.getBlue(), "Light addition color should have green dominance");
        }

        @Test
        @DisplayName("Deletion lines return red colors")
        void deletionLinesReturnRedColors() {
            Color lightColor =
                    UnifiedDiffColorResolver.getBackgroundColor(UnifiedDiffDocument.LineType.DELETION, false);
            Color darkColor = UnifiedDiffColorResolver.getBackgroundColor(UnifiedDiffDocument.LineType.DELETION, true);

            assertNotNull(lightColor, "Light theme deletion color should not be null");
            assertNotNull(darkColor, "Dark theme deletion color should not be null");

            // Verify red dominance (red component should be highest or equal highest)
            assertTrue(lightColor.getRed() >= lightColor.getGreen(), "Light deletion color should have red dominance");
            assertTrue(lightColor.getRed() >= lightColor.getBlue(), "Light deletion color should have red dominance");
        }

        @Test
        @DisplayName("Context lines return null (default background)")
        void contextLinesReturnNull() {
            Color lightColor = UnifiedDiffColorResolver.getBackgroundColor(UnifiedDiffDocument.LineType.CONTEXT, false);
            Color darkColor = UnifiedDiffColorResolver.getBackgroundColor(UnifiedDiffDocument.LineType.CONTEXT, true);

            assertNull(lightColor, "Context lines should use default background (null) in light theme");
            assertNull(darkColor, "Context lines should use default background (null) in dark theme");
        }

        @Test
        @DisplayName("Header lines return blue colors")
        void headerLinesReturnBlueColors() {
            Color lightColor = UnifiedDiffColorResolver.getBackgroundColor(UnifiedDiffDocument.LineType.HEADER, false);
            Color darkColor = UnifiedDiffColorResolver.getBackgroundColor(UnifiedDiffDocument.LineType.HEADER, true);

            assertNotNull(lightColor, "Light theme header color should not be null");
            assertNotNull(darkColor, "Dark theme header color should not be null");

            // Verify blue dominance (blue component should be highest or equal highest)
            assertTrue(lightColor.getBlue() >= lightColor.getRed(), "Light header color should have blue dominance");
            assertTrue(lightColor.getBlue() >= lightColor.getGreen(), "Light header color should have blue dominance");
        }

        @ParameterizedTest
        @EnumSource(UnifiedDiffDocument.LineType.class)
        @DisplayName("All line types handle both light and dark themes")
        void allLineTypesHandleBothThemes(UnifiedDiffDocument.LineType lineType) {
            // Should not throw exceptions for any line type
            assertDoesNotThrow(() -> {
                UnifiedDiffColorResolver.getBackgroundColor(lineType, false);
                UnifiedDiffColorResolver.getBackgroundColor(lineType, true);
            });
        }
    }

    @Nested
    @DisplayName("Color Enhancement")
    class ColorEnhancement {

        @Test
        @DisplayName("Enhanced colors are more visible than originals")
        void enhancedColorsAreMoreVisible() {
            // Test with a moderate color that has room for enhancement in both directions
            Color original = new Color(150, 180, 150);

            Color lightEnhanced = UnifiedDiffColorResolver.enhanceColorVisibility(original, false);
            Color darkEnhanced = UnifiedDiffColorResolver.enhanceColorVisibility(original, true);

            assertNotNull(lightEnhanced);
            assertNotNull(darkEnhanced);

            // Light theme should make colors darker (more visible)
            assertTrue(lightEnhanced.getRed() < original.getRed(), "Light theme enhancement should darken colors");
            assertTrue(lightEnhanced.getGreen() < original.getGreen(), "Light theme enhancement should darken colors");
            assertTrue(lightEnhanced.getBlue() < original.getBlue(), "Light theme enhancement should darken colors");

            // Dark theme should make colors brighter (more visible)
            assertTrue(darkEnhanced.getRed() > original.getRed(), "Dark theme enhancement should brighten colors");
            assertTrue(darkEnhanced.getGreen() > original.getGreen(), "Dark theme enhancement should brighten colors");
            assertTrue(darkEnhanced.getBlue() > original.getBlue(), "Dark theme enhancement should brighten colors");
        }

        @Test
        @DisplayName("Null input returns null")
        void nullInputReturnsNull() {
            assertNull(UnifiedDiffColorResolver.enhanceColorVisibility(null, false));
            assertNull(UnifiedDiffColorResolver.enhanceColorVisibility(null, true));
        }

        @Test
        @DisplayName("Enhanced colors stay within valid RGB range")
        void enhancedColorsStayWithinValidRange() {
            // Test with extreme colors
            Color white = new Color(255, 255, 255);
            Color black = new Color(0, 0, 0);

            Color whiteEnhanced = UnifiedDiffColorResolver.enhanceColorVisibility(white, true);
            Color blackEnhanced = UnifiedDiffColorResolver.enhanceColorVisibility(black, false);

            assertNotNull(whiteEnhanced);
            assertNotNull(blackEnhanced);

            // Check RGB values are within valid range [0, 255]
            assertTrue(whiteEnhanced.getRed() <= 255 && whiteEnhanced.getRed() >= 0);
            assertTrue(whiteEnhanced.getGreen() <= 255 && whiteEnhanced.getGreen() >= 0);
            assertTrue(whiteEnhanced.getBlue() <= 255 && whiteEnhanced.getBlue() >= 0);

            assertTrue(blackEnhanced.getRed() <= 255 && blackEnhanced.getRed() >= 0);
            assertTrue(blackEnhanced.getGreen() <= 255 && blackEnhanced.getGreen() >= 0);
            assertTrue(blackEnhanced.getBlue() <= 255 && blackEnhanced.getBlue() >= 0);
        }

        @Test
        @DisplayName("Enhanced background colors for all line types")
        void enhancedBackgroundColorsForAllLineTypes() {
            for (UnifiedDiffDocument.LineType lineType : UnifiedDiffDocument.LineType.values()) {
                for (boolean isDarkTheme : new boolean[] {false, true}) {
                    // Should not throw exceptions
                    assertDoesNotThrow(() -> {
                        Color enhanced = UnifiedDiffColorResolver.getEnhancedBackgroundColor(lineType, isDarkTheme);

                        // If original was null (context lines), enhanced should also be null
                        Color original = UnifiedDiffColorResolver.getBackgroundColor(lineType, isDarkTheme);
                        if (original == null) {
                            assertNull(
                                    enhanced,
                                    String.format(
                                            "Enhanced color should be null for %s when original is null", lineType));
                        } else {
                            assertNotNull(
                                    enhanced, String.format("Enhanced color should not be null for %s", lineType));
                        }
                    });
                }
            }
        }
    }

    @Nested
    @DisplayName("Text and Gutter Colors")
    class TextAndGutterColors {

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("Line number text colors provide contrast")
        void lineNumberTextColorsProvideContrast(boolean isDarkTheme) {
            Color textColor = UnifiedDiffColorResolver.getLineNumberTextColor(isDarkTheme);
            Color gutterBg = UnifiedDiffColorResolver.getDefaultGutterBackground(isDarkTheme);

            assertNotNull(textColor);
            assertNotNull(gutterBg);

            // Calculate simple contrast (not exact WCAG, but basic check)
            double textBrightness = (textColor.getRed() + textColor.getGreen() + textColor.getBlue()) / 3.0;
            double bgBrightness = (gutterBg.getRed() + gutterBg.getGreen() + gutterBg.getBlue()) / 3.0;

            double contrast = Math.abs(textBrightness - bgBrightness);
            assertTrue(
                    contrast > 50, // Arbitrary but reasonable threshold
                    String.format(
                            "Text and background should have sufficient contrast. Text: %s, Background: %s, Contrast: %.2f",
                            textColor, gutterBg, contrast));
        }

        @Test
        @DisplayName("Dark theme colors are darker than light theme colors")
        void darkThemeColorsAreDarker() {
            Color lightBg = UnifiedDiffColorResolver.getDefaultGutterBackground(false);
            Color darkBg = UnifiedDiffColorResolver.getDefaultGutterBackground(true);

            double lightBrightness = (lightBg.getRed() + lightBg.getGreen() + lightBg.getBlue()) / 3.0;
            double darkBrightness = (darkBg.getRed() + darkBg.getGreen() + darkBg.getBlue()) / 3.0;

            assertTrue(
                    darkBrightness < lightBrightness,
                    "Dark theme background should be darker than light theme background");
        }

        @Test
        @DisplayName("All color methods return non-null values")
        void allColorMethodsReturnNonNullValues() {
            for (boolean isDarkTheme : new boolean[] {false, true}) {
                assertNotNull(UnifiedDiffColorResolver.getLineNumberTextColor(isDarkTheme));
                assertNotNull(UnifiedDiffColorResolver.getDefaultGutterBackground(isDarkTheme));
                assertNotNull(UnifiedDiffColorResolver.getDefaultGutterForeground(isDarkTheme));
            }
        }
    }

    @Nested
    @DisplayName("Edge Cases and Robustness")
    class EdgeCasesAndRobustness {

        @Test
        @DisplayName("Color enhancement with edge case RGB values")
        void colorEnhancementWithEdgeCaseRgbValues() {
            // Test with various edge case colors
            Color[] testColors = {
                new Color(0, 0, 0), // Black
                new Color(255, 255, 255), // White
                new Color(128, 128, 128), // Gray
                new Color(255, 0, 0), // Pure red
                new Color(0, 255, 0), // Pure green
                new Color(0, 0, 255), // Pure blue
            };

            for (Color testColor : testColors) {
                for (boolean isDarkTheme : new boolean[] {false, true}) {
                    assertDoesNotThrow(() -> {
                        Color enhanced = UnifiedDiffColorResolver.enhanceColorVisibility(testColor, isDarkTheme);
                        assertNotNull(enhanced);

                        // Verify RGB components are in valid range
                        assertTrue(enhanced.getRed() >= 0 && enhanced.getRed() <= 255);
                        assertTrue(enhanced.getGreen() >= 0 && enhanced.getGreen() <= 255);
                        assertTrue(enhanced.getBlue() >= 0 && enhanced.getBlue() <= 255);
                    });
                }
            }
        }
    }
}
