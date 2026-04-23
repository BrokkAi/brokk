package ai.brokk.util;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.Nullable;

/** Minimal image <-> bytes utilities that do not require network or app wiring. */
public final class ImageBytesUtil {
    private ImageBytesUtil() {}

    public static byte @Nullable [] imageToPngBytes(@Nullable Image image) throws IOException {
        if (image == null) {
            return null;
        }

        BufferedImage bufferedImage;
        if (image instanceof BufferedImage bi) {
            bufferedImage = bi;
        } else {
            bufferedImage = new BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            var g = bufferedImage.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }

        try (var baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "PNG", baos);
            return baos.toByteArray();
        }
    }

    public static @Nullable Image pngBytesToImage(byte[] bytes) throws IOException {
        try (var bais = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(bais);
        }
    }
}
