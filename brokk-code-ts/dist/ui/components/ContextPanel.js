import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Box, Text } from "ink";
function fragmentId(fragment) {
    return String(fragment.id ?? "").trim();
}
function fragmentLabel(fragment) {
    const kind = String(fragment.chip_kind ?? fragment.chipKind ?? "OTHER").toUpperCase();
    const desc = String(fragment.shortDescription ?? fragment.description ?? "Unknown").trim() || "Unknown";
    const pinned = fragment.pinned ? " [PIN]" : "";
    const readonly = fragment.readonly ? " [RO]" : "";
    return `${kind} ${desc}${pinned}${readonly}`;
}
export function ContextPanel(props) {
    const width = props.width ?? 46;
    const rowLimit = props.rowLimit ?? 8;
    const framed = props.framed ?? true;
    const showHeader = props.showHeader ?? true;
    const rows = props.fragments.slice(0, rowLimit);
    return (_jsxs(Box, { borderStyle: framed ? "single" : undefined, paddingX: framed ? 1 : 0, flexDirection: "column", width: width, flexShrink: 1, children: [showHeader ? _jsx(Text, { children: "Context" }) : null, showHeader ? _jsxs(Text, { dimColor: true, children: ["Fragments: ", props.fragments.length, " | Tokens: ", props.tokenCount] }) : null, rows.length === 0 ? _jsx(Text, { dimColor: true, children: "No context fragments" }) : null, rows.map((fragment, idx) => {
                const id = fragmentId(fragment);
                const selected = id ? props.selectedFragmentIds.has(id) : false;
                const cursor = idx === props.cursorIndex;
                return (_jsxs(Text, { color: cursor ? "cyan" : undefined, children: [cursor ? ">" : " ", " ", selected ? "[x]" : "[ ]", " ", fragmentLabel(fragment)] }, `${id || idx}-${idx}`));
            })] }));
}
