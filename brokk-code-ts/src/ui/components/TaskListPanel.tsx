import React from "react";
import { Box, Text } from "ink";

type JsonRecord = Record<string, unknown>;

export interface TaskListPanelProps {
  tasks: JsonRecord[];
  selectedIndex: number;
  width?: number;
  rowLimit?: number;
}

function taskTitle(task: JsonRecord): string {
  const title = String(task.title ?? "").trim();
  if (title) {
    return title;
  }
  return String(task.text ?? "Untitled").trim() || "Untitled";
}

export function TaskListPanel(props: TaskListPanelProps): React.JSX.Element {
  const width = props.width ?? 46;
  const rowLimit = props.rowLimit ?? 8;
  const rows = props.tasks.slice(0, rowLimit);

  return (
    <Box borderStyle="single" paddingX={1} flexDirection="column" width={width} flexShrink={1}>
      <Text>Tasks</Text>
      <Text dimColor>Items: {props.tasks.length}</Text>
      {rows.length === 0 ? <Text dimColor>No tasks</Text> : null}
      {rows.map((task, idx) => {
        const absoluteIndex = idx;
        const done = Boolean(task.done);
        return (
          <Text key={`${String(task.id ?? idx)}-${idx}`} color={absoluteIndex === props.selectedIndex ? "cyan" : undefined}>
            {absoluteIndex === props.selectedIndex ? ">" : " "} {done ? "[x]" : "[ ]"} {taskTitle(task)}
          </Text>
        );
      })}
    </Box>
  );
}
