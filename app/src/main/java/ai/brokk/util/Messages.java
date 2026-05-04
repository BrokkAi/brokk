package ai.brokk.util;

import static java.util.Objects.requireNonNull;

import ai.brokk.LlmOutputMeta;
import ai.brokk.concurrent.ComputedValue;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;

public class Messages {
    private static final Logger logger = LogManager.getLogger(Messages.class);

    // Simple OpenAI token count estimator for approximate counting
    // It can remain static as it's stateless based on model ID
    private static final OpenAiTokenCountEstimator tokenCountEstimator = new OpenAiTokenCountEstimator("gpt-4o");

    public static void init() {
        // tokenizer is surprisingly heavyweigh to initialize, this is just to give a hook to force that early
        logger.debug("Messages helper initializing");
    }

    /**
     * We render these as "System" messages in the output. We don't use actual System messages since those are only
     * allowed at the very beginning for some models.
     */
    public static CustomMessage customSystem(String text) {
        return new CustomMessage(Map.of("text", text));
    }

    public static CustomMessage customSystem(String text, LlmOutputMeta meta) {
        if (meta.isTerminal()) {
            return new CustomMessage(Map.of("text", text, "isTerminal", true));
        }
        return new CustomMessage(Map.of("text", text));
    }

    public static List<ChatMessage> forLlm(Collection<ChatMessage> messages) {
        return messages.stream()
                .map(m -> switch (m) {
                    case CustomMessage cm -> new UserMessage(getText(cm));
                    // strip out the metadata we use for stashing UI action type
                    case UserMessage um -> um.name() != null ? new UserMessage(um.contents()) : um;
                    // We had code here strip out reasoning from AiMessage but that makes CodeAgent worse
                    default -> m;
                })
                .toList();
    }

    /** Extracts text content from a ChatMessage. This logic is independent of Models state, so can remain static. */
    public static String getText(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case AiMessage am -> {
                var text = am.text();
                if (text != null && !text.isBlank()) {
                    yield text;
                }
                var reasoning = am.reasoningContent();
                yield reasoning == null ? "" : reasoning;
            }
            case UserMessage um ->
                um.contents().stream()
                        .filter(c -> c instanceof TextContent)
                        .map(c -> ((TextContent) c).text())
                        .collect(Collectors.joining("\n"));
            case ToolExecutionResultMessage tr -> "%s -> %s".formatted(tr.toolName(), tr.text());
            case CustomMessage cm -> requireNonNull(cm.attributes().get("text")).toString();
            default ->
                throw new UnsupportedOperationException(message.getClass().toString());
        };
    }

    /** Helper method to create a ChatMessage of the specified type */
    public static ChatMessage create(String text, ChatMessageType type) {
        return create(text, type, LlmOutputMeta.DEFAULT);
    }

    public static ChatMessage create(String text, ChatMessageType type, LlmOutputMeta meta) {
        boolean isReasoning = meta.isReasoning();
        return switch (type) {
            case USER -> new UserMessage(text);
            case AI -> isReasoning ? new AiMessage("", text) : new AiMessage(text);
            case CUSTOM -> customSystem(text, meta);
            default -> {
                logger.warn("Unsupported message type: {}, using AiMessage as fallback", type);
                yield new AiMessage(text);
            }
        };
    }

    /** Primary difference from getText: 1. Includes tool requests 2. Includes placeholder for images */
    public static String getRepr(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case CustomMessage cm -> requireNonNull(cm.attributes().get("text")).toString();
            case AiMessage am -> {
                var reasoning = am.reasoningContent();
                var text = am.text();
                var hasReasoning = reasoning != null && !reasoning.isBlank();
                var hasText = text != null && !text.isBlank();
                var hasTools = am.hasToolExecutionRequests();

                var parts = new ArrayList<String>();
                if (hasReasoning) {
                    parts.add("Reasoning:\n" + reasoning);
                    if (hasText) {
                        parts.add("Text:\n" + text);
                    }
                } else if (hasText) {
                    parts.add(text);
                }

                if (hasTools) {
                    var toolText = am.toolExecutionRequests().stream()
                            .map(Messages::getRepr)
                            .collect(Collectors.joining("\n"));
                    parts.add("Tool calls:\n" + toolText);
                }

                yield String.join("\n", parts);
            }
            case UserMessage um -> {
                yield um.contents().stream()
                        .map(c -> {
                            if (c instanceof TextContent textContent) {
                                return textContent.text();
                            } else if (c instanceof ImageContent) {
                                return "[Image]";
                            } else {
                                throw new UnsupportedOperationException(
                                        c.getClass().toString());
                            }
                        })
                        .collect(Collectors.joining("\n"));
            }
            case ToolExecutionResultMessage tr -> "%s -> %s".formatted(tr.toolName(), tr.text());
            default ->
                throw new UnsupportedOperationException(message.getClass().toString());
        };
    }

    public static String getRepr(ToolExecutionRequest tr) {
        return "%s(%s)".formatted(tr.name(), tr.arguments());
    }

    /**
     * Returns a passive, historical representation of a tool execution request
     * that does not resemble executable tool-call syntax.
     * Format: Tool `toolName` was invoked with {"arg": "value"}
     */
    public static String getRedactedRepr(ToolExecutionRequest tr) {
        return "Tool `%s` was invoked with %s".formatted(tr.name(), tr.arguments());
    }

    /**
     * Estimates the token count of a text string. This can remain static as it only depends on the static token count
     * estimator.
     */
    public static int getApproximateTokens(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        return tokenCountEstimator.encode(text).size();
    }

    public static int getApproximateTokens(Collection<String> texts) {
        return texts.parallelStream().mapToInt(Messages::getApproximateTokens).sum();
    }

    @Blocking
    public static int getApproximateTokens(Context ctx) {
        var texts = ctx.allFragments()
                .map(ContextFragment::text)
                .map(ComputedValue::join)
                .toList();
        return getApproximateTokens(texts);
    }

    public static int getApproximateMessageTokens(Collection<ChatMessage> messages) {
        return messages.parallelStream()
                .mapToInt(m -> getApproximateTokens(getText(m)))
                .sum();
    }

    public static boolean isReasoningMessage(ChatMessage message) {
        return message instanceof AiMessage aiMessage
                && aiMessage.reasoningContent() != null
                && !aiMessage.reasoningContent().isBlank();
    }

    public static boolean isTerminalMessage(ChatMessage message) {
        return message instanceof CustomMessage cm && cm.attributes().get("isTerminal") != null;
    }

    public static String format(List<ChatMessage> messages) {
        return messages.stream()
                .map(message -> {
                    var text = getRepr(message);
                    return (CharSequence)
                            """
                                    <message type=%s>
                                    %s
                                    </message>
                                    """
                                    .formatted(
                                            message.type().name().toLowerCase(Locale.ROOT),
                                            text.indent(2).stripTrailing());
                })
                .collect(Collectors.joining("\n"));
    }

    public static String getReprForDisplay(ChatMessage message) {
        return switch (message) {
            case SystemMessage sm -> sm.text();
            case CustomMessage cm -> requireNonNull(cm.attributes().get("text")).toString();
            case AiMessage am -> {
                var reasoning = am.reasoningContent();
                var text = am.text();
                var hasReasoning = reasoning != null && !reasoning.isBlank();
                var hasText = text != null && !text.isBlank();
                var hasTools = am.hasToolExecutionRequests();

                var parts = new ArrayList<String>();
                if (hasReasoning) {
                    parts.add(reasoning);
                }
                if (hasText) {
                    parts.add(text);
                }
                if (hasTools) {
                    var toolText = am.toolExecutionRequests().stream()
                            .map(Messages::getRedactedRepr)
                            .collect(Collectors.joining("\n"));
                    parts.add(toolText);
                }
                yield String.join("\n\n", parts);
            }
            case UserMessage um ->
                um.contents().stream()
                        .map(c -> {
                            if (c instanceof TextContent textContent) {
                                return textContent.text();
                            } else if (c instanceof ImageContent) {
                                return "[Image]";
                            } else {
                                throw new UnsupportedOperationException(
                                        c.getClass().toString());
                            }
                        })
                        .collect(Collectors.joining("\n"));
            case ToolExecutionResultMessage tr -> "%s -> %s".formatted(tr.toolName(), tr.text());
            default ->
                throw new UnsupportedOperationException(message.getClass().toString());
        };
    }

    public static String formatForDisplay(List<ChatMessage> messages) {
        return messages.stream()
                .map(Messages::getReprForDisplay)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * One slice of pre-rendered task markdown after legacy framing has been stripped.
     * Produced by {@link #parseLegacyFraming(String)}.
     */
    public record FramedSegment(ChatMessageType type, String content) {}

    private static final Pattern LEGACY_OPEN_TAG = Pattern.compile("^\\s*<message type=(\\w+)>\\s*$");

    private static final Pattern LEGACY_CLOSE_TAG = Pattern.compile("^\\s*</message>\\s*$");

    private static final Pattern LEGACY_SECTION_LABEL = Pattern.compile("(?m)^(?:Reasoning|Text|Tool calls):\\s*$");

    private static final int MAX_FRAMING_DEPTH = 32;

    /**
     * Parses task markdown that may contain legacy {@code <message type=X>...</message>} framing
     * (produced by {@link #format(List)}) into a list of structured segments.
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
    public static List<FramedSegment> parseLegacyFraming(String markdown) {
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
            var openMatcher = LEGACY_OPEN_TAG.matcher(line);
            if (openMatcher.matches()) {
                if (typeStack.size() >= MAX_FRAMING_DEPTH) {
                    logger.warn(
                            "parseLegacyFraming: depth limit {} exceeded; falling back to CUSTOM segment",
                            MAX_FRAMING_DEPTH);
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
            if (LEGACY_CLOSE_TAG.matcher(line).matches()) {
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
        var withoutLabels = LEGACY_SECTION_LABEL.matcher(deindented).replaceAll("");
        return withoutLabels.replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** Strips legacy framing while preserving readable content; used where per-segment structure isn't needed. */
    public static String stripLegacyFraming(String markdown) {
        return parseLegacyFraming(markdown).stream().map(FramedSegment::content).collect(Collectors.joining("\n\n"));
    }
}
