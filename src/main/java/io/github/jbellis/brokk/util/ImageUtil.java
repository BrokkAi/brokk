package io.github.jbellis.brokk.util;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Base64;

public class ImageUtil {
    private static final Logger logger = LogManager.getLogger(ImageUtil.class);

    public static @Nullable BufferedImage toBuffered(@Nullable Image img) {
        if (img == null) return null;
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

    /**
     * Checks if the given URI likely points to an image by sending a HEAD request
     * and checking the Content-Type header.
     *
     * @param uri    The URI to check.
     * @param client The OkHttpClient to use for the request.
     * @return true if the URI content type starts with "image/", false otherwise.
     */
    public static boolean isImageUri(URI uri, OkHttpClient client) {
        // 1. Try HEAD request
        Request headRequest = new Request.Builder()
                .url(uri.toString())
                .head()
                .build();

        try (Response response = client.newCall(headRequest).execute()) {
            if (response.isSuccessful()) {
                String contentType = response.header("Content-Type");
                if (contentType != null) {
                    String lowerContentType = contentType.toLowerCase(java.util.Locale.ROOT);
                    logger.debug("HEAD request to {} - Content-Type: {}", uri, contentType);
                    if (lowerContentType.startsWith("image/") ||
                        lowerContentType.equals("application/octet-stream") ||
                        lowerContentType.equals("binary/octet-stream")) {
                        return true;
                    }
                    // If content type is not image-like, but HEAD was successful,
                    // we might not need to fallback if it's clearly not an image (e.g. text/html).
                    // However, to be robust against misconfigured servers, we can still fallback.
                    // For now, if HEAD is successful and Content-Type is definitive (and not image), we can stop.
                    // If it's ambiguous, the fallback will help.
                    if (lowerContentType.startsWith("text/") || lowerContentType.startsWith("application/json") || lowerContentType.startsWith("application/xml")) {
                        logger.debug("HEAD request to {} successful with non-image Content-Type: {}. Not an image.", uri, contentType);
                        return false;
                    }
                } else {
                    logger.warn("HEAD request to {} successful but no Content-Type header. Proceeding to fallback.", uri);
                }
            } else {
                logger.warn("HEAD request to {} failed with code: {}. Proceeding to fallback.", uri, response.code());
            }
        } catch (IOException e) {
            logger.warn("IOException during HEAD request to {}: {}. Proceeding to fallback.", uri, e.getMessage());
        }

        // 2. Fallback to Range GET request
        Request rangeGetRequest = new Request.Builder()
                .url(uri.toString())
                .addHeader("Range", "bytes=0-1023") // Request first 1KB
                .get()
                .build();

        try (Response response = client.newCall(rangeGetRequest).execute()) {
            // Successful responses for range requests can be 200 (if server ignores Range) or 206 (Partial Content)
            if (response.isSuccessful()) { // isSuccessful covers 200-299
                String contentType = response.header("Content-Type");
                if (contentType != null) {
                    String lowerContentType = contentType.toLowerCase(java.util.Locale.ROOT);
                    logger.debug("Range GET request to {} - Content-Type: {}", uri, contentType);
                    return lowerContentType.startsWith("image/")
                           || lowerContentType.equals("application/octet-stream")
                           || lowerContentType.equals("binary/octet-stream");
                } else {
                    logger.warn("Range GET request to {} successful but no Content-Type header.", uri);
                }
            } else {
                logger.warn("Range GET request to {} failed with code: {}", uri, response.code());
            }
        } catch (IOException e) {
            logger.error("IOException during Range GET request to {}: {}", uri, e.getMessage());
        }

        return false;
    }

    /**
     * Downloads an image from the given URI.
     *
     * @param uri    The URI to download the image from.
     * @param client The OkHttpClient to use for the request (though ImageIO uses its own mechanism).
     *               It's included for consistency if we later switched to OkHttp for download.
     * @return The downloaded Image, or null if an error occurred or it's not a valid image.
     */
    @Nullable
    public static Image downloadImage(URI uri, OkHttpClient client) {
        // Note: ImageIO.read(URL) handles its own connection.
        // The OkHttpClient isn't directly used here for the download itself but is part of the API
        // in case future implementations want to use it (e.g., for direct byte streaming with OkHttp).
        try {
            // It's good practice to ensure the URI is an image first using isImageUri if this method is called externally without prior check.
            // However, ImageIO.read will also return null if it cannot decode the content.
            return ImageIO.read(uri.toURL());
        } catch (IOException e) {
            logger.warn("Failed to fetch or decode image from URL {}: {}.", uri, e.getMessage());
            return null;
        }
    }
}
