import React from "react";
import { Box, Text } from "ink";
import { formatTokenCount } from "../../tokenFormat.js";
import { COLORS } from "../theme.js";

export interface TokenBarProps {
  used: number;
  budget: number;
}

export function TokenBar(props: TokenBarProps): React.JSX.Element {
  return (
    <Box justifyContent="flex-end">
      <Text color={COLORS.dim}>
        {formatTokenCount(props.used)}t / {formatTokenCount(props.budget)}t
      </Text>
    </Box>
  );
}
