package ai.brokk.util;

public final class TextCanonicalizer {
    private TextCanonicalizer() {
        /* utility class – no instances */
    }

    /**
     * Strips a leading UTF-8 BOM (EF BB BF) from the provided byte array, if present. Returns the original array if no
     * BOM is present.
     */
    public static byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            byte[] withoutBom = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, withoutBom, 0, bytes.length - 3);
            return withoutBom;
        }
        return bytes;
    }

    /**
     * Strips a leading UTF-8 BOM (U+FEFF) from the provided String, if present. Returns the original string if no BOM
     * is present.
     */
    public static String stripUtf8Bom(String s) {
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }
}
