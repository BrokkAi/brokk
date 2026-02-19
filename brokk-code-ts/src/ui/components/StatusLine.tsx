import React from "react";
import { Text } from "ink";
import { COLORS } from "../theme.js";
import { formatFragmentStatus, formatStatusMetadata } from "../statusFormatter.js";

export interface StatusLineProps {
  mode: string;
  model: string;
  reasoning: string;
  workspace: string;
  branch: string;
  fragmentDescription?: string;
  fragmentSizeTokens?: number;
  maxWidth?: number;
}

function truncate(text: string, maxWidth: number): string {
  if (text.length <= maxWidth) {
    return text;
  }
  if (maxWidth <= 3) {
    return text.slice(0, maxWidth);
  }
  return `${text.slice(0, maxWidth - 3)}...`;
}

export function StatusLine(props: StatusLineProps): React.JSX.Element {
  const maxWidth = props.maxWidth ?? 120;
  let content = formatStatusMetadata({
    mode: props.mode,
    model: props.model,
    reasoning: props.reasoning,
    workspace: props.workspace,
    branch: props.branch
  });

  if (
    typeof props.fragmentDescription === "string" &&
    props.fragmentDescription.trim() &&
    typeof props.fragmentSizeTokens === "number" &&
    props.fragmentSizeTokens >= 0
  ) {
    content = formatFragmentStatus(props.fragmentDescription.trim(), props.fragmentSizeTokens);
  }

  return <Text color={COLORS.dim}>{truncate(content, maxWidth)}</Text>;
}
