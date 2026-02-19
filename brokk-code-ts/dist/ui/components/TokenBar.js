import { jsxs as _jsxs, jsx as _jsx } from "react/jsx-runtime";
import { Box, Text } from "ink";
import { formatTokenCount } from "../../tokenFormat.js";
import { COLORS } from "../theme.js";
export function TokenBar(props) {
    return (_jsx(Box, { justifyContent: "flex-end", children: _jsxs(Text, { color: COLORS.dim, children: [formatTokenCount(props.used), "t / ", formatTokenCount(props.budget), "t"] }) }));
}
