package ai.brokk.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FileUtil {
    private static final Logger logger = LogManager.getLogger(FileUtil.class);

    private FileUtil() {
        /* utility class – no instances */
    }

    /**
     * Deletes {@code path} and everything beneath it. Does **not** follow symlinks; logs but ignores individual delete
     * failures.
     */
    public static boolean deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return false;
        }

        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    logger.warn("Failed to delete {}", p, e);
                }
            });
            return !Files.exists(path);
        } catch (IOException e) {
            logger.error("Failed to walk or initiate deletion for directory: {}", path, e);
            return false;
        }
    }

    /**
     * Computes 0-based character offsets for the start of each logical line in the given content.
     * Handles LF, CRLF, and CR line separators by scanning the actual characters in content.
     *
     * <p>The returned array length equals the number of logical lines as produced by content.split("\\R", -1).</p>
     */
    public static int[] computeLineStarts(String content) {
        var starts = new ArrayList<Integer>();
        starts.add(0); // first line always starts at 0
        for (int i = 0, n = content.length(); i < n; ) {
            char ch = content.charAt(i);
            if (ch == '\r') {
                if (i + 1 < n && content.charAt(i + 1) == '\n') {
                    i += 2; // CRLF
                } else {
                    i += 1; // CR only
                }
                starts.add(i);
            } else if (ch == '\n') {
                i += 1; // LF only
                starts.add(i);
            } else {
                i += 1;
            }
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            arr[i] = starts.get(i);
        }
        return arr;
    }

    /**
     * Returns the 0-based line index for the given character offset using precomputed line starts.
     * If offset falls between two lines, returns the index of the line that starts at or before the offset.
     */
    public static int findLineIndexForOffset(int[] lineStarts, int charOffset) {
        int lo = 0, hi = lineStarts.length - 1, idx = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts[mid] <= charOffset) {
                idx = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return idx;
    }
}
