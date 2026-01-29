// Configuration constants
export const SOFT_SPLIT_CHARS = 16_000;  // Look for paragraph boundary after this
export const HARD_SPLIT_CHARS = 32_000;  // Force split even without good boundary
export const MIN_SPLIT_SIZE = 2_000;     // Don't create tiny bubbles

// Types
export interface FenceState {
    insideFence: boolean;
}

export interface SplitDecision {
    shouldSplit: boolean;
    /** If shouldSplit, the text to append to current bubble (empty string if no split) */
    textForCurrentBubble: string;
    /** If shouldSplit, the text for the new bubble */
    textForNewBubble: string;
    /** Updated fence state after processing the chunk */
    newFenceState: FenceState;
}

/**
 * Count fence toggles (``` or ~~~) in text.
 * Only counts fences at start of line.
 */
function countFenceToggles(text: string): number {
    const matches = text.match(/^[ \t]*(`{3,}|~{3,})/gm);
    return matches ? matches.length : 0;
}

/**
 * Update fence state after processing new text.
 * Each fence marker toggles the state.
 */
export function updateFenceState(currentInsideFence: boolean, newText: string): boolean {
    const toggles = countFenceToggles(newText);
    // Odd number of toggles flips the state
    const newCurrentInsideFence = toggles % 2 === 1 ? !currentInsideFence : currentInsideFence;
    if (newCurrentInsideFence !== currentInsideFence) {
        console.debug('[splitStrategy] FENCE STATE CHANGED', { currentInsideFence, newCurrentInsideFence });
    }
    return newCurrentInsideFence;
}

/**
 * Find a safe paragraph boundary in the combined text.
 * Returns the index (in combinedText) where to split, or -1 if no safe boundary found.
 * The split index is AFTER the \n\n (so it goes with the first part).
 */
function findSafeParagraphBoundary(
    combinedText: string,
    scanStartIndex: number,
    searchStartIndex: number,
    minSplitIndex: number,
    initialInsideFence: boolean
): number {
    // We need to ensure we're not inside a fence at the split point
    let insideFence = initialInsideFence;
    let lastSafeBoundary = -1;
    let i = scanStartIndex;
    
    while (i < combinedText.length - 1) {
        // Check for fence at start of line
        if (i === 0 || combinedText[i - 1] === '\n') {
            const rest = combinedText.slice(i);
            if (/^[ \t]*(`{3,}|~{3,})/.test(rest)) {
                insideFence = !insideFence;
            }
        }
        
        // Check for paragraph boundary (\n\n) outside fence
        if (!insideFence && 
            combinedText[i] === '\n' && 
            combinedText[i + 1] === '\n' &&
            i >= minSplitIndex) {
            // Record position after \n\n as valid split point
            lastSafeBoundary = i + 2;
        }
        
        i++;
    }
    
    // Only return boundary if it's in the region we're searching
    return lastSafeBoundary >= searchStartIndex ? lastSafeBoundary : -1;
}

/**
 * Evaluate whether to split the current bubble given new incoming text.
 * 
 * @param currentMarkdown - The markdown already in the current bubble
 * @param newChunk - The new chunk being appended
 * @param currentFenceState - Whether we're currently inside a code fence
 * @returns Split decision with text portions and updated fence state
 */
export function evaluateSplit(
    currentMarkdown: string,
    newChunk: string,
    currentFenceState: FenceState
): SplitDecision {
    const currentLength = currentMarkdown.length;
    const projectedLength = currentLength + newChunk.length;
    
    // Calculate new fence state regardless of split decision
    const newInsideFence = updateFenceState(currentFenceState.insideFence, newChunk);
    const newFenceState: FenceState = { insideFence: newInsideFence };
    
    // Not over threshold yet - no split needed
    if (projectedLength <= SOFT_SPLIT_CHARS) {
        return {
            shouldSplit: false,
            textForCurrentBubble: '',
            textForNewBubble: '',
            newFenceState
        };
    }
    
    // Over threshold - look for split point
    const combinedText = currentMarkdown + newChunk;
    
    // Search for boundary starting from where we exceeded soft limit
    const searchStart = Math.max(currentLength, SOFT_SPLIT_CHARS);
    
    // Find a safe paragraph boundary. 
    // We only need to scan the new chunk, starting from currentLength.
    const splitIndex = findSafeParagraphBoundary(
        combinedText,
        currentLength,
        searchStart,
        MIN_SPLIT_SIZE,
        currentFenceState.insideFence
    );
    
    if (splitIndex > currentLength) {
        // Split point is in the new chunk - split there
        const splitInChunk = splitIndex - currentLength;
        console.debug(
            '[splitStrategy] SOFT SPLIT at paragraph boundary',
            { currentLength, splitIndex, splitInChunk, projectedLength }
        );
        return {
            shouldSplit: true,
            textForCurrentBubble: newChunk.slice(0, splitInChunk),
            textForNewBubble: newChunk.slice(splitInChunk),
            newFenceState: { 
                // Recalculate fence state for just the new bubble's content
                insideFence: updateFenceState(false, newChunk.slice(splitInChunk))
            }
        };
    }
    
    // No good split point in chunk, but we might need hard split
    if (projectedLength > HARD_SPLIT_CHARS && !currentFenceState.insideFence) {
        // Force split at start of new chunk (only if not inside fence)
        console.debug(
            '[splitStrategy] HARD SPLIT forced (no paragraph boundary found)',
            { currentLength, projectedLength, HARD_SPLIT_CHARS }
        );
        return {
            shouldSplit: true,
            textForCurrentBubble: '',
            textForNewBubble: newChunk,
            newFenceState: {
                insideFence: updateFenceState(currentFenceState.insideFence, newChunk)
            }
        };
    }
    
    // Can't split safely yet (inside fence or no good boundary)
    if (projectedLength > HARD_SPLIT_CHARS && currentFenceState.insideFence) {
        console.debug(
            '[splitStrategy] SPLIT BLOCKED - inside code fence',
            { currentLength, projectedLength, insideFence: currentFenceState.insideFence }
        );
    }
    return {
        shouldSplit: false,
        textForCurrentBubble: '',
        textForNewBubble: '',
        newFenceState
    };
}
