import React from "react";
import { Box, Text } from "ink";
import { COLORS } from "../theme.js";

export interface ChatMessage {
  author: "user" | "assistant" | "system";
  text: string;
}

export interface ChatPanelProps {
  messages: ChatMessage[];
  rowLimit?: number;
}

interface ChatMessageViewProps {
  message: ChatMessage;
  messageIndex: number;
}

function renderInline(markdown: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = [];
  let remaining = markdown;
  let key = 0;

  while (remaining.length > 0) {
    const match =
      remaining.match(/^(\*\*([^*]+)\*\*)/) ??
      remaining.match(/^(\*([^*]+)\*)/) ??
      remaining.match(/^(\[([^\]]+)\]\(([^)]+)\))/) ??
      remaining.match(/^(\`([^`]+)\`)/);

    if (!match) {
      nodes.push(<Text key={`t-${key++}`}>{remaining}</Text>);
      break;
    }

    const startIndex = match.index ?? 0;
    if (startIndex > 0) {
      nodes.push(<Text key={`t-${key++}`}>{remaining.slice(0, startIndex)}</Text>);
    }

    const full = match[1] ?? "";
    if (full.startsWith("**")) {
      nodes.push(
        <Text key={`b-${key++}`} bold>
          {match[2] ?? ""}
        </Text>,
      );
    } else if (full.startsWith("*")) {
      nodes.push(
        <Text key={`i-${key++}`} italic>
          {match[2] ?? ""}
        </Text>,
      );
    } else if (full.startsWith("[")) {
      const label = match[2] ?? "";
      const href = match[3] ?? "";
      nodes.push(
        <Text key={`l-${key++}`} underline color={COLORS.running}>
          {label} ({href})
        </Text>,
      );
    } else if (full.startsWith("`")) {
      nodes.push(
        <Text key={`c-${key++}`} color={COLORS.dim}>
          [{match[2] ?? ""}]
        </Text>,
      );
    }

    remaining = remaining.slice(startIndex + full.length);
  }

  return nodes;
}

function renderMarkdownLines(markdown: string): React.JSX.Element[] {
  const lines = markdown.replace(/\r\n/g, "\n").split("\n");
  const rendered: React.JSX.Element[] = [];
  let inCodeFence = false;

  lines.forEach((rawLine, index) => {
    const line = rawLine.trimEnd();

    if (line.startsWith("```")) {
      inCodeFence = !inCodeFence;
      return;
    }

    if (inCodeFence) {
      rendered.push(
        <Text key={`code-${index}`} color={COLORS.dim}>
          {`  ${rawLine}`}
        </Text>,
      );
      return;
    }

    if (line.length === 0) {
      rendered.push(<Text key={`blank-${index}`}> </Text>);
      return;
    }

    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) {
      rendered.push(
        <Text key={`h-${index}`} bold color={COLORS.running}>
          {renderInline(heading[2] ?? "")}
        </Text>,
      );
      return;
    }

    const quote = line.match(/^>\s+(.+)$/);
    if (quote) {
      rendered.push(
        <Text key={`q-${index}`} color={COLORS.dim}>
          {`| ${quote[1] ?? ""}`}
        </Text>,
      );
      return;
    }

    const bullet = line.match(/^[-*+]\s+(.+)$/);
    if (bullet) {
      rendered.push(<Text key={`b-${index}`}>{`- `}{renderInline(bullet[1] ?? "")}</Text>);
      return;
    }

    const ordered = line.match(/^(\d+)\.\s+(.+)$/);
    if (ordered) {
      rendered.push(<Text key={`o-${index}`}>{`${ordered[1]}. `}{renderInline(ordered[2] ?? "")}</Text>);
      return;
    }

    rendered.push(<Text key={`p-${index}`}>{renderInline(rawLine)}</Text>);
  });

  return rendered;
}

const ChatMessageView = React.memo(function ChatMessageView(props: ChatMessageViewProps): React.JSX.Element {
  const renderedMarkdown = React.useMemo(() => renderMarkdownLines(props.message.text), [props.message.text]);
  return (
    <Box flexDirection="column" marginBottom={1}>
      <Text
        color={
          props.message.author === "user" ? COLORS.user : props.message.author === "assistant" ? COLORS.assistant : COLORS.system
        }
      >
        {props.message.author.toUpperCase()}:
      </Text>
      <Box marginLeft={2} flexDirection="column">
        {props.message.author === "assistant" || props.message.author === "system" ? (
          renderedMarkdown
        ) : (
          <Text>{props.message.text}</Text>
        )}
      </Box>
    </Box>
  );
});

export const ChatPanel = React.memo(function ChatPanel(props: ChatPanelProps): React.JSX.Element {
  const rowLimit = props.rowLimit ?? 18;
  const tail = props.messages.slice(-rowLimit);
  const tailStartIndex = props.messages.length - tail.length;
  return (
    <Box flexDirection="column" flexGrow={1} flexShrink={1}>
      {tail.map((message, index) => (
        <ChatMessageView
          key={`${tailStartIndex + index}-${message.author}`}
          message={message}
          messageIndex={tailStartIndex + index}
        />
      ))}
    </Box>
  );
});
