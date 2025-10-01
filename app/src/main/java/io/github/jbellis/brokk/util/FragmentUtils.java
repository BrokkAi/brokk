package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.ContextFragment;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;

public final class FragmentUtils {

    private FragmentUtils() {
        // Private constructor to prevent instantiation
    }

    private static void updateDigest(MessageDigest md, @Nullable String data) {
        if (data != null) {
            md.update(data.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void updateDigest(MessageDigest md, @Nullable byte[] data) {
        if (data != null) {
            md.update(data);
        }
    }

    private static void updateDigest(MessageDigest md, boolean data) {
        md.update((byte) (data ? 1 : 0));
    }

    private static String calculateHashInternal(
            ContextFragment.FragmentType type,
            String description,
            @Nullable String shortDescription,
            @Nullable String textContent,
            @Nullable byte[] imageBytesContent,
            boolean isTextFragment,
            String syntaxStyle,
            @Nullable Set<ProjectFile> files,
            @Nullable String originalClassName,
            @Nullable Map<String, String> meta) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            updateDigest(md, type.name());
            updateDigest(md, description);
            if (shortDescription != null) { // Only include if provided
                updateDigest(md, shortDescription);
            }
            updateDigest(md, textContent);
            updateDigest(md, imageBytesContent);
            updateDigest(md, isTextFragment);
            updateDigest(md, syntaxStyle);

            if (files != null && !files.isEmpty()) {
                var sortedFilesString = files.stream()
                        .map(pf -> pf.getRoot() + "|" + pf.getRelPath())
                        .sorted()
                        .collect(Collectors.joining(";"));
                updateDigest(md, sortedFilesString);
            }

            updateDigest(md, originalClassName);

            if (meta != null && !meta.isEmpty()) {
                var sortedMetaString = meta.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining(";"));
                updateDigest(md, sortedMetaString);
            }

            byte[] digest = md.digest();
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    }

    /** Calculates a content hash for fragments primarily defined by text. */
    public static String calculateContentHash(
            ContextFragment.FragmentType type,
            String description,
            String textContent,
            String syntaxStyle,
            String originalClassName) {
        return calculateHashInternal(
                type, description, null, textContent, null, true, syntaxStyle, null, originalClassName, null);
    }

    /**
     * Calculates a content hash for fragments that might include images and metadata, but not a separate
     * shortDescription. Used by AnonymousImageFragment.
     */
    public static String calculateContentHash(
            ContextFragment.FragmentType type,
            String description,
            @Nullable String textContent,
            @Nullable byte[] imageBytesContent,
            boolean isTextFragment,
            String syntaxStyle,
            @Nullable Set<ProjectFile> files,
            @Nullable String originalClassName,
            @Nullable Map<String, String> meta) {
        return calculateHashInternal(
                type,
                description,
                null,
                textContent,
                imageBytesContent,
                isTextFragment,
                syntaxStyle,
                files,
                originalClassName,
                meta);
    }

    /**
     * Comprehensive content hash calculation, typically used by FrozenFragment. Includes a distinct shortDescription.
     */
    public static String calculateContentHash(
            ContextFragment.FragmentType type,
            String description,
            String shortDescription,
            @Nullable String textContent,
            @Nullable byte[] imageBytesContent,
            boolean isTextFragment,
            String syntaxStyle,
            @Nullable Set<ProjectFile> files,
            @Nullable String originalClassName,
            @Nullable Map<String, String> meta) {
        return calculateHashInternal(
                type,
                description,
                shortDescription,
                textContent,
                imageBytesContent,
                isTextFragment,
                syntaxStyle,
                files,
                originalClassName,
                meta);
    }

    /**
     * Converts an Image to a byte array in PNG format.
     *
     * @param image The image to convert
     * @return PNG bytes, or null if image is null
     * @throws IOException If conversion fails
     */
    @Nullable
    public static byte[] imageToBytes(@Nullable Image image) throws IOException {
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
}
