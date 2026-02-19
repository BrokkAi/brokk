import React from "react";
import { Box, Text } from "ink";

export interface SelectorModalProps {
  title: string;
  items: string[];
  selectedIndex: number;
  visible: boolean;
  width?: number;
  rowLimit?: number;
}

export function SelectorModal(props: SelectorModalProps): React.JSX.Element | null {
  if (!props.visible) {
    return null;
  }

  const boundedIndex = props.items.length === 0 ? 0 : Math.max(0, Math.min(props.selectedIndex, props.items.length - 1));
  const width = props.width ?? 50;
  const rowLimit = props.rowLimit ?? 8;

  return (
    <Box
      borderStyle="round"
      flexDirection="column"
      paddingX={1}
      paddingY={0}
      width={width}
      marginLeft={2}
      marginTop={1}
    >
      <Text bold>{props.title}</Text>
      {props.items.length === 0 ? <Text dimColor>No options</Text> : null}
      {props.items.slice(0, rowLimit).map((item, idx) => (
        <Text key={`${item}-${idx}`} color={idx === boundedIndex ? "cyan" : undefined}>
          {idx === boundedIndex ? ">" : " "} {item}
        </Text>
      ))}
      <Text dimColor>Up/Down move, Enter select, Esc cancel, Tab next pane</Text>
    </Box>
  );
}
