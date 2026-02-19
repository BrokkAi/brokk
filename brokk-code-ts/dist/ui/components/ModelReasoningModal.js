import { jsxs as _jsxs, jsx as _jsx } from "react/jsx-runtime";
import { Box, Text } from "ink";
function bounded(index, length) {
    if (length <= 0) {
        return 0;
    }
    return Math.max(0, Math.min(index, length - 1));
}
export function ModelReasoningModal(props) {
    if (!props.visible) {
        return null;
    }
    const modelIndex = bounded(props.modelIndex, props.models.length);
    const reasoningIndex = bounded(props.reasoningIndex, props.reasoningLevels.length);
    const width = props.width ?? 72;
    const rowLimit = props.rowLimit ?? 8;
    const modelPaneWidth = Math.max(20, Math.floor((width - 8) * 0.62));
    const reasoningPaneWidth = Math.max(14, width - modelPaneWidth - 6);
    return (_jsxs(Box, { borderStyle: "round", flexDirection: "column", paddingX: 1, marginLeft: 2, marginTop: 1, width: width, children: [_jsxs(Text, { bold: true, children: [props.targetLabel, " Model + Reasoning"] }), _jsxs(Box, { children: [_jsxs(Box, { flexDirection: "column", width: modelPaneWidth, marginRight: 2, children: [_jsx(Text, { color: props.activePane === "model" ? "cyan" : undefined, children: "Model" }), props.models.length === 0 ? _jsx(Text, { dimColor: true, children: "No models" }) : null, props.models.slice(0, rowLimit).map((model, idx) => (_jsxs(Text, { color: props.activePane === "model" && idx === modelIndex ? "cyan" : undefined, children: [props.activePane === "model" && idx === modelIndex ? ">" : " ", " ", model] }, `${model}-${idx}`)))] }), _jsxs(Box, { flexDirection: "column", width: reasoningPaneWidth, children: [_jsx(Text, { color: props.activePane === "reasoning" ? "cyan" : undefined, children: "Reasoning" }), props.reasoningLevels.map((level, idx) => (_jsxs(Text, { color: props.activePane === "reasoning" && idx === reasoningIndex ? "cyan" : undefined, children: [props.activePane === "reasoning" && idx === reasoningIndex ? ">" : " ", " ", level] }, `${level}-${idx}`)))] })] }), _jsx(Text, { dimColor: true, children: "Tab/Left/Right switch pane, Up/Down move, Enter confirm, Esc cancel" })] }));
}
