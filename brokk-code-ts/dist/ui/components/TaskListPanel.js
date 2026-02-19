import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
import { Box, Text } from "ink";
function taskTitle(task) {
    const title = String(task.title ?? "").trim();
    if (title) {
        return title;
    }
    return String(task.text ?? "Untitled").trim() || "Untitled";
}
export function TaskListPanel(props) {
    const width = props.width ?? 46;
    const rowLimit = props.rowLimit ?? 8;
    const rows = props.tasks.slice(0, rowLimit);
    return (_jsxs(Box, { borderStyle: "single", paddingX: 1, flexDirection: "column", width: width, flexShrink: 1, children: [_jsx(Text, { children: "Tasks" }), _jsxs(Text, { dimColor: true, children: ["Items: ", props.tasks.length] }), rows.length === 0 ? _jsx(Text, { dimColor: true, children: "No tasks" }) : null, rows.map((task, idx) => {
                const absoluteIndex = idx;
                const done = Boolean(task.done);
                return (_jsxs(Text, { color: absoluteIndex === props.selectedIndex ? "cyan" : undefined, children: [absoluteIndex === props.selectedIndex ? ">" : " ", " ", done ? "[x]" : "[ ]", " ", taskTitle(task)] }, `${String(task.id ?? idx)}-${idx}`));
            })] }));
}
