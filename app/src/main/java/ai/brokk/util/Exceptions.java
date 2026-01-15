package ai.brokk.util;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class Exceptions {
    public static String formatThrowable(Throwable th) {
        var baos = new ByteArrayOutputStream();
        try (var ps = new PrintStream(baos)) {
            th.printStackTrace(ps);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
