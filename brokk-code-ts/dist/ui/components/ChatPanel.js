import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import React from "react";
import { Box, Text } from "ink";
import { COLORS } from "../theme.js";
function renderInline(markdown) {
    const nodes = [];
    let remaining = markdown;
    let key = 0;
    while (remaining.length > 0) {
        const match = remaining.match(/^(\*\*([^*]+)\*\*)/) ??
            remaining.match(/^(\*([^*]+)\*)/) ??
            remaining.match(/^(\[([^\]]+)\]\(([^)]+)\))/) ??
            remaining.match(/^(\`([^`]+)\`)/);
        if (!match) {
            nodes.push(_jsx(Text, { children: remaining }, `t-${key++}`));
            break;
        }
        const startIndex = match.index ?? 0;
        if (startIndex > 0) {
            nodes.push(_jsx(Text, { children: remaining.slice(0, startIndex) }, `t-${key++}`));
        }
        const full = match[1] ?? "";
        if (full.startsWith("**")) {
            nodes.push(_jsx(Text, { bold: true, children: match[2] ?? "" }, `b-${key++}`));
        }
        else if (full.startsWith("*")) {
            nodes.push(_jsx(Text, { italic: true, children: match[2] ?? "" }, `i-${key++}`));
        }
        else if (full.startsWith("[")) {
            const label = match[2] ?? "";
            const href = match[3] ?? "";
            nodes.push(_jsxs(Text, { underline: true, color: COLORS.running, children: [label, " (", href, ")"] }, `l-${key++}`));
        }
        else if (full.startsWith("`")) {
            nodes.push(_jsxs(Text, { color: COLORS.dim, children: ["[", match[2] ?? "", "]"] }, `c-${key++}`));
        }
        remaining = remaining.slice(startIndex + full.length);
    }
    return nodes;
}
function renderMarkdownLines(markdown) {
    const lines = markdown.replace(/\r\n/g, "\n").split("\n");
    const rendered = [];
    let inCodeFence = false;
    lines.forEach((rawLine, index) => {
        const line = rawLine.trimEnd();
        if (line.startsWith("```")) {
            inCodeFence = !inCodeFence;
            return;
        }
        if (inCodeFence) {
            rendered.push(_jsx(Text, { color: COLORS.dim, children: `  ${rawLine}` }, `code-${index}`));
            return;
        }
        if (line.length === 0) {
            rendered.push(_jsx(Text, { children: " " }, `blank-${index}`));
            return;
        }
        const heading = line.match(/^(#{1,6})\s+(.+)$/);
        if (heading) {
            rendered.push(_jsx(Text, { bold: true, color: COLORS.running, children: renderInline(heading[2] ?? "") }, `h-${index}`));
            return;
        }
        const quote = line.match(/^>\s+(.+)$/);
        if (quote) {
            rendered.push(_jsx(Text, { color: COLORS.dim, children: `| ${quote[1] ?? ""}` }, `q-${index}`));
            return;
        }
        const bullet = line.match(/^[-*+]\s+(.+)$/);
        if (bullet) {
            rendered.push(_jsxs(Text, { children: [`- `, renderInline(bullet[1] ?? "")] }, `b-${index}`));
            return;
        }
        const ordered = line.match(/^(\d+)\.\s+(.+)$/);
        if (ordered) {
            rendered.push(_jsxs(Text, { children: [`${ordered[1]}. `, renderInline(ordered[2] ?? "")] }, `o-${index}`));
            return;
        }
        rendered.push(_jsx(Text, { children: renderInline(rawLine) }, `p-${index}`));
    });
    return rendered;
}
const ChatMessageView = React.memo(function ChatMessageView(props) {
    const renderedMarkdown = React.useMemo(() => renderMarkdownLines(props.message.text), [props.message.text]);
    return (_jsxs(Box, { flexDirection: "column", marginBottom: 1, children: [_jsxs(Text, { color: props.message.author === "user" ? COLORS.user : props.message.author === "assistant" ? COLORS.assistant : COLORS.system, children: [props.message.author.toUpperCase(), ":"] }), _jsx(Box, { marginLeft: 2, flexDirection: "column", children: props.message.author === "assistant" || props.message.author === "system" ? (renderedMarkdown) : (_jsx(Text, { children: props.message.text })) })] }));
});
export const ChatPanel = React.memo(function ChatPanel(props) {
    const rowLimit = props.rowLimit ?? 18;
    const tail = props.messages.slice(-rowLimit);
    const tailStartIndex = props.messages.length - tail.length;
    return (_jsx(Box, { flexDirection: "column", flexGrow: 1, flexShrink: 1, children: tail.map((message, index) => (_jsx(ChatMessageView, { message: message, messageIndex: tailStartIndex + index }, `${tailStartIndex + index}-${message.author}`))) }));
});
