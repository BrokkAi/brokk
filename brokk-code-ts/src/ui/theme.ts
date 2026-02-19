export const UI = {
  horizontalMargin: 2,
  topMargin: 1,
  taskPanelWidth: 38,
  minAppHeight: 20,
  smallWidthBreakpoint: 100,
  chatMinWidth: 28,
  footerHelpHeight: 1,
  statusHeight: 1,
  inputHeight: 3,
  modalPaddingX: 2,
  modalPaddingY: 1,
  maxSelectorWidth: 54,
  maxCombinedSelectorWidth: 76
} as const;

export const COLORS = {
  idle: "gray",
  running: "cyan",
  system: "yellow",
  user: "cyan",
  assistant: "green",
  inputPrompt: "magenta",
  dim: "gray"
} as const;
