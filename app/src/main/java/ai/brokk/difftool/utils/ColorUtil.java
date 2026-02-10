package ai.brokk.difftool.utils;

import ai.brokk.gui.mop.ThemeColors;
import com.github.difflib.patch.AbstractDelta;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import org.jetbrains.annotations.Nullable;

public class ColorUtil {

    /** Converts a Color to a hex string suitable for use in HTML/CSS. */
    public static String toHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    public static Color brighter(Color color) {
        return brighter(color, 0.05f);
    }

    public static Color darker(Color color) {
        return brighter(color, -0.05f);
    }

    /** Create a brighter color by changing the b component of a hsb-color (b=brightness, h=hue, s=saturation) */
    public static Color brighter(Color color, float factor) {
        float[] hsbvals;

        hsbvals = new float[3];
        Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), hsbvals);

        return setBrightness(color, hsbvals[2] + factor);
    }

    public static Color setBrightness(Color color, float brightness) {
        float[] hsbvals;

        hsbvals = new float[3];
        Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), hsbvals);
        hsbvals[2] = brightness;
        hsbvals[2] = Math.min(hsbvals[2], 1.0f);
        hsbvals[2] = Math.max(hsbvals[2], 0.0f);

        color = new Color(Color.HSBtoRGB(hsbvals[0], hsbvals[1], hsbvals[2]));

        return color;
    }

    public static Color getColor(AbstractDelta<String> delta, boolean darkTheme) {
        return switch (delta.getType()) {
            case INSERT -> ThemeColors.getDiffAdded(darkTheme);
            case DELETE -> ThemeColors.getDiffDeleted(darkTheme);
            case CHANGE -> ThemeColors.getDiffChanged(darkTheme);
            case EQUAL -> throw new IllegalStateException();
        };
    }

    /**
     * Determines if a color is dark based on its relative luminance per ITU-R BT.709.
     *
     * @param c the color to check
     * @return true if the color is dark (luminance < 0.5), false otherwise
     */
    public static boolean isDarkColor(Color c) {
        double r = c.getRed() / 255.0;
        double g = c.getGreen() / 255.0;
        double b = c.getBlue() / 255.0;
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return lum < 0.5;
    }

    /**
     * Returns a contrasting text color (white or black) for the given background color.
     *
     * @param bg the background color
     * @return Color.WHITE for dark backgrounds, Color.BLACK for light backgrounds
     */
    public static Color contrastingText(Color bg) {
        return isDarkColor(bg) ? Color.WHITE : Color.BLACK;
    }

    /**
     * Creates a high-contrast version of an icon by re-coloring it based on the button's background color.
     * Only applies in high-contrast mode; returns the original icon otherwise.
     *
     * @param originalIcon the original icon
     * @param buttonBackground the background color of the button containing the icon
     * @param isHighContrast whether high-contrast mode is active
     * @return a re-colored icon for high-contrast mode, or the original icon otherwise
     */
    public static @Nullable Icon createHighContrastIcon(
            @Nullable Icon originalIcon, Color buttonBackground, boolean isHighContrast) {
        if (!isHighContrast || originalIcon == null) {
            return originalIcon;
        }

        // Determine the contrasting color based on the button background
        Color iconColor = contrastingText(buttonBackground);

        // Create a new icon with the contrasting color
        int w = originalIcon.getIconWidth();
        int h = originalIcon.getIconHeight();
        if (w <= 0 || h <= 0) {
            return originalIcon;
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            // Paint the original icon
            originalIcon.paintIcon(null, g2, 0, 0);

            // Re-color all non-transparent pixels to the contrasting color
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pixel = img.getRGB(x, y);
                    int alpha = (pixel >> 24) & 0xff;
                    if (alpha > 0) {
                        // Preserve alpha, but use the contrasting color
                        int rgb = iconColor.getRGB() & 0x00ffffff;
                        img.setRGB(x, y, (alpha << 24) | rgb);
                    }
                }
            }
        } finally {
            g2.dispose();
        }

        return new ImageIcon(img);
    }
}
