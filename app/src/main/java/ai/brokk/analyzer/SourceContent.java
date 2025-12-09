package ai.brokk.analyzer;

import ai.brokk.util.TextCanonicalizer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.treesitter.TSNode;

/**
 * Wrapper for a source text and its UTF-8 bytes. Provides safe substring extraction by UTF-8 byte offsets
 * and avoids repeated String.getBytes(StandardCharsets.UTF_8) allocations across callers.
 */
public final class SourceContent {
    private static final Logger log = LogManager.getLogger(SourceContent.class);

    private final String text;
    private final byte[] utf8Bytes;
    private final int byteLength;

    private SourceContent(String text, byte[] utf8Bytes, int byteLength) {
        this.text = text;
        this.utf8Bytes = utf8Bytes;
        this.byteLength = byteLength;
    }

    public static Optional<SourceContent> read(ProjectFile file) {
        var srcOpt = file.read();
        return srcOpt.map(s -> SourceContent.of(TextCanonicalizer.stripUtf8Bom(s)));
    }

    /**
     * Creates a SourceContent wrapper for the provided source text.
     */
    public static SourceContent of(String src) {
        byte[] bytes = src.getBytes(StandardCharsets.UTF_8);
        return new SourceContent(src, bytes, bytes.length);
    }

    /**
     * Safely extracts a substring using UTF-8 byte offsets [startByte, endByte).
     *
     * Behavior:
     *  - If startByte < 0 or endByte < startByte, returns empty string and logs a warning.
     *  - If startByte >= underlying byte length returns empty string and logs a warning.
     *  - If endByte > byte length, endByte is truncated to byte length (logs at debug).
     *  - Zero-length ranges return the empty string.
     *
     * The returned String is constructed directly from the UTF-8 byte slice to avoid re-encoding/parsing errors.
     */
    public String substringFromBytes(int startByte, int endByte) {
        if (startByte < 0 || endByte < startByte) {
            log.warn(
                    "Requested bytes outside valid range for source text (length: {} bytes): startByte={}, endByte={}",
                    byteLength,
                    startByte,
                    endByte);
            return "";
        }

        if (startByte >= byteLength) {
            log.warn("Start byte offset {} exceeds source byte length {}", startByte, byteLength);
            return "";
        }

        if (endByte > byteLength) {
            log.debug("End byte offset {} exceeds source byte length {}, truncating", endByte, byteLength);
            endByte = byteLength;
        }

        int len = endByte - startByte;
        if (len == 0) return "";

        return new String(utf8Bytes, startByte, len, StandardCharsets.UTF_8);
    }

    /**
     * Converts a UTF-8 byte offset into a Java String character index (code units).
     * If the offset is outside bounds it clamps and returns 0 or source length.
     */
    public int byteOffsetToCharPosition(int byteOffset) {
        if (byteOffset <= 0) return 0;
        if (byteOffset >= byteLength) return text.length();

        // Reconstruct prefix string from bytes and return its length in code units.
        String prefix = new String(utf8Bytes, 0, byteOffset, StandardCharsets.UTF_8);
        return prefix.length();
    }

    /**
     * Converts a Java String character index (code unit position) into a UTF-8 byte offset.
     *
     * If charPosition <= 0 returns 0. If charPosition >= source.length() returns byteLength.
     */
    public int charPositionToByteOffset(int charPosition) {
        if (charPosition <= 0) return 0;
        if (charPosition >= text.length()) return byteLength;

        // Build prefix substring and measure UTF-8 byte length.
        String prefix = text.substring(0, charPosition);
        return prefix.getBytes(StandardCharsets.UTF_8).length;
    }

    public String text() {
        return text;
    }

    public byte[] utf8Bytes() {
        return utf8Bytes;
    }

    public int byteLength() {
        return byteLength;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (SourceContent) obj;
        return Objects.equals(this.text, that.text)
                && Arrays.equals(this.utf8Bytes, that.utf8Bytes)
                && this.byteLength == that.byteLength;
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, Arrays.hashCode(utf8Bytes), byteLength);
    }

    @Override
    public String toString() {
        return "SourceContent[" + "text=" + text + ", " + "byteLength=" + byteLength + ']';
    }

    /**
     * Extracts a substring from the source code based on node boundaries using a cached SourceContent.
     */
    public String substringFrom(TSNode node) {
        if (node.isNull()) {
            return "";
        }
        return substringFromBytes(node.getStartByte(), node.getEndByte());
    }
}
