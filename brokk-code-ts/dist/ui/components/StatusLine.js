import { jsx as _jsx } from "react/jsx-runtime";
import { Text } from "ink";
import { COLORS } from "../theme.js";
import { formatFragmentStatus, formatStatusMetadata } from "../statusFormatter.js";
function truncate(text, maxWidth) {
    if (text.length <= maxWidth) {
        return text;
    }
    if (maxWidth <= 3) {
        return text.slice(0, maxWidth);
    }
    return `${text.slice(0, maxWidth - 3)}...`;
}
export function StatusLine(props) {
    const maxWidth = props.maxWidth ?? 120;
    let content = formatStatusMetadata({
        mode: props.mode,
        model: props.model,
        reasoning: props.reasoning,
        workspace: props.workspace,
        branch: props.branch
    });
    if (typeof props.fragmentDescription === "string" &&
        props.fragmentDescription.trim() &&
        typeof props.fragmentSizeTokens === "number" &&
        props.fragmentSizeTokens >= 0) {
        content = formatFragmentStatus(props.fragmentDescription.trim(), props.fragmentSizeTokens);
    }
    return _jsx(Text, { color: COLORS.dim, children: truncate(content, maxWidth) });
}
