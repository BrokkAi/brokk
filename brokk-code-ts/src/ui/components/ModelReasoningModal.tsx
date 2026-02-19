import React from "react";
import { Box, Text } from "ink";

export interface ModelReasoningModalProps {
  visible: boolean;
  targetLabel: string;
  models: string[];
  modelIndex: number;
  reasoningLevels: string[];
  reasoningIndex: number;
  activePane: "model" | "reasoning";
  width?: number;
  rowLimit?: number;
}

function bounded(index: number, length: number): number {
  if (length <= 0) {
    return 0;
  }
  return Math.max(0, Math.min(index, length - 1));
}

export function ModelReasoningModal(props: ModelReasoningModalProps): React.JSX.Element | null {
  if (!props.visible) {
    return null;
  }

  const modelIndex = bounded(props.modelIndex, props.models.length);
  const reasoningIndex = bounded(props.reasoningIndex, props.reasoningLevels.length);
  const width = props.width ?? 72;
  const rowLimit = props.rowLimit ?? 8;
  const modelPaneWidth = Math.max(20, Math.floor((width - 8) * 0.62));
  const reasoningPaneWidth = Math.max(14, width - modelPaneWidth - 6);

  return (
    <Box borderStyle="round" flexDirection="column" paddingX={1} marginLeft={2} marginTop={1} width={width}>
      <Text bold>{props.targetLabel} Model + Reasoning</Text>
      <Box>
        <Box flexDirection="column" width={modelPaneWidth} marginRight={2}>
          <Text color={props.activePane === "model" ? "cyan" : undefined}>Model</Text>
          {props.models.length === 0 ? <Text dimColor>No models</Text> : null}
          {props.models.slice(0, rowLimit).map((model, idx) => (
            <Text key={`${model}-${idx}`} color={props.activePane === "model" && idx === modelIndex ? "cyan" : undefined}>
              {props.activePane === "model" && idx === modelIndex ? ">" : " "} {model}
            </Text>
          ))}
        </Box>
        <Box flexDirection="column" width={reasoningPaneWidth}>
          <Text color={props.activePane === "reasoning" ? "cyan" : undefined}>Reasoning</Text>
          {props.reasoningLevels.map((level, idx) => (
            <Text
              key={`${level}-${idx}`}
              color={props.activePane === "reasoning" && idx === reasoningIndex ? "cyan" : undefined}
            >
              {props.activePane === "reasoning" && idx === reasoningIndex ? ">" : " "} {level}
            </Text>
          ))}
        </Box>
      </Box>
      <Text dimColor>Tab/Left/Right switch pane, Up/Down move, Enter confirm, Esc cancel</Text>
    </Box>
  );
}
