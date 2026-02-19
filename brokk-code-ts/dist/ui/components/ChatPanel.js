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
            rendered.push(_jsxs(Text, { children: ["- ", renderInline(bullet[1] ?? "")] }, `b-${index}`));
            return;
        }
        const ordered = line.match(/^(\d+)\.\s+(.+)$/);
        if (ordered) {
            rendered.push(_jsxs(Text, { children: [ordered[1], ". ", renderInline(ordered[2] ?? "")] }, `o-${index}`));
            return;
        }
        rendered.push(_jsx(Text, { children: renderInline(rawLine) }, `p-${index}`));
    });
    return rendered;
}
function buildRows(messages) {
    const rows = [];
    messages.forEach((message, messageIndex) => {
        const authorColor = message.author === "user" ? COLORS.user : message.author === "assistant" ? COLORS.assistant : COLORS.system;
        rows.push({
            key: `h-${messageIndex}`,
            node: (_jsxs(Text, { color: authorColor, children: [message.author.toUpperCase(), ":"] }))
        });
        if (message.author === "assistant" || message.author === "system") {
            const markdownRows = renderMarkdownLines(message.text);
            if (markdownRows.length === 0) {
                rows.push({ key: `m-${messageIndex}-0`, node: _jsx(Text, { children: "  " }) });
            }
            else {
                markdownRows.forEach((row, lineIndex) => {
                    rows.push({
                        key: `m-${messageIndex}-${lineIndex}`,
                        node: _jsx(Box, { marginLeft: 2, children: row })
                    });
                });
            }
        }
        else {
            const userLines = message.text.replace(/\r\n/g, "\n").split("\n");
            userLines.forEach((line, lineIndex) => {
                rows.push({ key: `u-${messageIndex}-${lineIndex}`, node: _jsx(Text, { children: `  ${line}` }) });
            });
        }
        rows.push({ key: `s-${messageIndex}`, node: _jsx(Text, { children: " " }) });
    });
    return rows;
}
export const ChatPanel = React.memo(function ChatPanel(props) {
    const rowLimit = Math.max(3, props.rowLimit ?? 18);
    const allRows = React.useMemo(() => buildRows(props.messages), [props.messages]);
    const tailRows = allRows.slice(-rowLimit);
    const blankRows = Math.max(0, rowLimit - tailRows.length);
    return (_jsxs(Box, { flexDirection: "column", flexGrow: 1, flexShrink: 1, children: [Array.from({ length: blankRows }, (_, index) => (_jsx(Text, { children: " " }, `pad-${index}`))), tailRows.map((row) => (_jsx(Box, { flexShrink: 0, children: row.node }, row.key)))] }));
});
