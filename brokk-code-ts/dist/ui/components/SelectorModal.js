import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Box, Text } from "ink";
export function SelectorModal(props) {
    if (!props.visible) {
        return null;
    }
    const boundedIndex = props.items.length === 0 ? 0 : Math.max(0, Math.min(props.selectedIndex, props.items.length - 1));
    const width = props.width ?? 50;
    const rowLimit = props.rowLimit ?? 8;
    return (_jsxs(Box, { borderStyle: "round", flexDirection: "column", paddingX: 1, paddingY: 0, width: width, marginLeft: 2, marginTop: 1, children: [_jsx(Text, { bold: true, children: props.title }), props.items.length === 0 ? _jsx(Text, { dimColor: true, children: "No options" }) : null, props.items.slice(0, rowLimit).map((item, idx) => (_jsxs(Text, { color: idx === boundedIndex ? "cyan" : undefined, children: [idx === boundedIndex ? ">" : " ", " ", item] }, `${item}-${idx}`))), _jsx(Text, { dimColor: true, children: "Up/Down move, Enter select, Esc cancel, Tab next pane" })] }));
}
