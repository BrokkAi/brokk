import React from "react";
import { Box, Text } from "ink";

type JsonRecord = Record<string, unknown>;

export interface ContextPanelProps {
  fragments: JsonRecord[];
  selectedFragmentIds: Set<string>;
  cursorIndex: number;
  tokenCount: number;
  width?: number;
  rowLimit?: number;
  framed?: boolean;
  showHeader?: boolean;
}

function fragmentId(fragment: JsonRecord): string {
  return String(fragment.id ?? "").trim();
}

function fragmentLabel(fragment: JsonRecord): string {
  const kind = String(fragment.chip_kind ?? fragment.chipKind ?? "OTHER").toUpperCase();
  const desc = String(fragment.shortDescription ?? fragment.description ?? "Unknown").trim() || "Unknown";
  const pinned = fragment.pinned ? " [PIN]" : "";
  const readonly = fragment.readonly ? " [RO]" : "";
  return `${kind} ${desc}${pinned}${readonly}`;
}

export function ContextPanel(props: ContextPanelProps): React.JSX.Element {
  const width = props.width ?? 46;
  const rowLimit = props.rowLimit ?? 8;
  const framed = props.framed ?? true;
  const showHeader = props.showHeader ?? true;
  const rows = props.fragments.slice(0, rowLimit);

  return (
    <Box borderStyle={framed ? "single" : undefined} paddingX={framed ? 1 : 0} flexDirection="column" width={width} flexShrink={1}>
      {showHeader ? <Text>Context</Text> : null}
      {showHeader ? <Text dimColor>Fragments: {props.fragments.length} | Tokens: {props.tokenCount}</Text> : null}
      {rows.length === 0 ? <Text dimColor>No context fragments</Text> : null}
      {rows.map((fragment, idx) => {
        const id = fragmentId(fragment);
        const selected = id ? props.selectedFragmentIds.has(id) : false;
        const cursor = idx === props.cursorIndex;
        return (
          <Text key={`${id || idx}-${idx}`} color={cursor ? "cyan" : undefined}>
            {cursor ? ">" : " "} {selected ? "[x]" : "[ ]"} {fragmentLabel(fragment)}
          </Text>
        );
      })}
    </Box>
  );
}
