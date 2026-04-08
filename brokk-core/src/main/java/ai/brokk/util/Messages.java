package ai.brokk.util;

import static com.knuddels.jtokkit.api.EncodingType.O200K_BASE;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;

public final class Messages {
    private static final EncodingRegistry ENCODING_REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding TOKEN_ENCODING = ENCODING_REGISTRY.getEncoding(O200K_BASE);

    private Messages() {}

    public static int getApproximateTokens(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return TOKEN_ENCODING.encode(text).size();
    }
}
