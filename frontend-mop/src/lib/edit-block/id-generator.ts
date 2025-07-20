let currentBubbleId = 0;
let localIndex = 0;

/**
 * Resets the counter for a new bubble and sets the current bubble ID.
 * @param bubbleId The ID of the current bubble (sequence number).
 */
export function resetForBubble(bubbleId: number): void {
  currentBubbleId = bubbleId;
  localIndex = 0;
}

/**
 * Generates the next unique ID for an edit block in the current bubble.
 * @returns A string in the format `bubbleId-index`.
 */
export function nextEditBlockId(): string {
  return `${currentBubbleId}-${localIndex++}`;
}
