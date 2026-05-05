package ai.brokk.util;

import dev.langchain4j.data.message.ChatMessageType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Parses task markdown that contains legacy {@code <message type=X>...</message>} framing
 * (produced by {@link Messages#format(java.util.List)}) into structured segments.
 *
 * <p><b>TODO(#3462):</b> remove once tasks are persisted as structured messages instead of
 * pre-rendered markdown blobs. This is a transitional shim for data persisted by #3324-era code;
 * once {@code TaskFragmentDto} carries a structured message list, the three call sites
 * ({@link ai.brokk.TaskEntry}, {@link ai.brokk.gui.mop.webview.MOPBridge},
 * {@link ai.brokk.acp.BrokkAcpAgent}) can drop their {@link #parse}/{@link #strip} calls and
 * this class can be removed wholesale.
 */
public final class LegacyFramingParser {
    private static final Logger logger = LogManager.getLogger(LegacyFramingParser.class);

    private LegacyFramingParser() {}

    /** One slice of pre-rendered task markdown after legacy framing has been stripped. */
    public record FramedSegment(ChatMessageType type, String content) {}

    private static final Pattern OPEN_TAG = Pattern.compile("^\\s*<message type=(\\w+)>\\s*$");

    private static final Pattern CLOSE_TAG = Pattern.compile("^\\s*</message>\\s*$");

    private static final Pattern SECTION_LABEL = Pattern.compile("(?m)^(?:Reasoning|Text|Tool calls):\\s*$");

    private static final int MAX_FRAMING_DEPTH = 32;

    /**
     * Parses task markdown that may contain legacy {@code <message type=X>...</message>} framing
     * into a list of structured segments.
     *
     * <p>If no framing is detected the whole markdown is returned as a single CUSTOM segment so that
     * post-fix data flows through unchanged. This is meant for display-side replay where structured
     * messages are no longer available on the fragment but the persisted markdown still carries them.
     *
     * <p>Uses a line-based stack scanner so it correctly handles empty bodies and arbitrarily nested
     * framing. Each closing tag emits a segment if its accumulated content is non-empty after cleaning;
     * nested wrappers contribute additional segments only when they carry their own content outside the
     * inner blocks. Emission order follows close-tag order (inner blocks emit before their parent).
     *
     * <p>If the open-tag depth exceeds {@value #MAX_FRAMING_DEPTH}, the parser falls back to a single
     * CUSTOM segment containing the original markdown to prevent unbounded allocation on adversarial
     * input.
     */
    public static List<FramedSegment> parse(String markdown) {
        if (markdown.isEmpty()) {
            return List.of();
        }
        if (!markdown.contains("<message type=")) {
            return List.of(new FramedSegment(ChatMessageType.CUSTOM, markdown));
        }
        var typeStack = new ArrayDeque<ChatMessageType>();
        var contentStack = new ArrayDeque<StringBuilder>();
        var result = new ArrayList<FramedSegment>();
        for (var line : markdown.split("\n", -1)) {
            var openMatcher = OPEN_TAG.matcher(line);
            if (openMatcher.matches()) {
                if (typeStack.size() >= MAX_FRAMING_DEPTH) {
                    logger.warn(
                            "parse: depth limit {} exceeded; falling back to CUSTOM segment", MAX_FRAMING_DEPTH);
                    return List.of(new FramedSegment(ChatMessageType.CUSTOM, markdown));
                }
                ChatMessageType type;
                try {
                    type = ChatMessageType.valueOf(openMatcher.group(1).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    type = ChatMessageType.CUSTOM;
                }
                typeStack.push(type);
                contentStack.push(new StringBuilder());
                continue;
            }
            if (CLOSE_TAG.matcher(line).matches()) {
                if (typeStack.isEmpty()) {
                    continue;
                }
                var type = typeStack.pop();
                var cleaned = cleanFramedContent(contentStack.pop().toString());
                if (!cleaned.isEmpty()) {
                    result.add(new FramedSegment(type, cleaned));
                }
                continue;
            }
            if (!contentStack.isEmpty()) {
                contentStack.peek().append(line).append('\n');
            }
        }
        if (result.isEmpty()) {
            return List.of(new FramedSegment(ChatMessageType.CUSTOM, markdown));
        }
        return result;
    }

    /** Strips legacy framing while preserving readable content; used where per-segment structure isn't needed. */
    public static String strip(String markdown) {
        return parse(markdown).stream().map(FramedSegment::content).collect(Collectors.joining("\n\n"));
    }

    private static String cleanFramedContent(String content) {
        if (content.isEmpty()) {
            return "";
        }
        var minIndent = content.lines()
                .filter(line -> !line.isBlank())
                .mapToInt(line -> {
                    int n = 0;
                    while (n < line.length() && line.charAt(n) == ' ') n++;
                    return n;
                })
                .min()
                .orElse(0);
        var deindented = content.lines()
                .map(line -> line.length() >= minIndent ? line.substring(minIndent) : line)
                .collect(Collectors.joining("\n"));
        var withoutLabels = SECTION_LABEL.matcher(deindented).replaceAll("");
        return withoutLabels.replaceAll("\\n{3,}", "\n\n").strip();
    }
}
