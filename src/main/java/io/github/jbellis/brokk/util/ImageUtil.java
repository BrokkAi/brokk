package io.github.jbellis.brokk.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class ImageUtil {
    public static BufferedImage toBuffered(Image img) {
        if (img == null) return null; // Handle null input
        if (img instanceof BufferedImage bi) {
            return bi;
        }
        BufferedImage bimage = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D bGr = bimage.createGraphics();
        bGr.drawImage(img, 0, 0, null);
        bGr.dispose();
        return bimage;
    }

    /**
     * Converts an AWT Image to a LangChain4j Image by encoding it as base64 PNG.
     * 
     * @param awtImage The AWT Image to convert
     * @return A LangChain4j Image
     * @throws IOException If image conversion fails
     */
    public static dev.langchain4j.data.image.Image toL4JImage(Image awtImage) throws IOException {
        if (awtImage == null) {
            throw new IllegalArgumentException("Image cannot be null");
        }
        
        // Convert to BufferedImage if needed
        BufferedImage bufferedImage = toBuffered(awtImage);
        
        // Convert to PNG bytes
        try (var baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();
            
            // Encode as base64 and create LangChain4j Image using the builder pattern
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            return dev.langchain4j.data.image.Image.builder()
                    .base64Data(base64)
                    .mimeType("image/png")
                    .build();
        }
    }
}
