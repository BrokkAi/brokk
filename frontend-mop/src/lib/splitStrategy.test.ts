import { expect, test, describe, vi, beforeEach, afterEach } from 'vitest';
import {
    evaluateSplit,
    updateFenceState,
    SOFT_SPLIT_CHARS,
    HARD_SPLIT_CHARS,
    MIN_SPLIT_SIZE
} from './splitStrategy';

// Helper to generate content of specific length
function makeContent(length: number, char = 'x'): string {
    return char.repeat(length);
}

// Helper to generate paragraphs of approximately target total length
function makeParagraphs(targetLength: number, paragraphSize = 500): string {
    const paragraphs: string[] = [];
    let total = 0;
    while (total < targetLength) {
        const remaining = targetLength - total;
        const size = Math.min(paragraphSize, remaining);
        paragraphs.push(makeContent(size));
        total += size + 2; // +2 for \n\n
    }
    return paragraphs.join('\n\n');
}

describe('updateFenceState', () => {
    test('returns false when no fences in text', () => {
        expect(updateFenceState(false, 'plain text')).toBe(false);
        expect(updateFenceState(true, 'plain text')).toBe(true);
    });

    test('toggles on single fence', () => {
        expect(updateFenceState(false, '```\ncode')).toBe(true);
        expect(updateFenceState(true, '```\ncode')).toBe(false);
    });

    test('no change on even number of fences (open + close)', () => {
        expect(updateFenceState(false, '```\ncode\n```')).toBe(false);
        expect(updateFenceState(true, '```\ncode\n```')).toBe(true);
    });

    test('detects triple tilde fences', () => {
        expect(updateFenceState(false, '~~~\ncode')).toBe(true);
        expect(updateFenceState(false, '~~~\ncode\n~~~')).toBe(false);
    });

    test('detects indented fences (spaces)', () => {
        expect(updateFenceState(false, '  ```\ncode')).toBe(true);
        expect(updateFenceState(false, '    ```\ncode\n    ```')).toBe(false);
    });

    test('detects indented fences (tabs)', () => {
        expect(updateFenceState(false, '\t```\ncode')).toBe(true);
        expect(updateFenceState(false, '\t\t```\ncode\n\t\t```')).toBe(false);
    });

    test('handles multiple fences (odd count)', () => {
        const text = '```\ncode1\n```\n\ntext\n\n```\ncode2';
        expect(updateFenceState(false, text)).toBe(true); // 3 fences = odd
    });

    test('handles multiple fences (even count)', () => {
        const text = '```\ncode1\n```\n\ntext\n\n```\ncode2\n```';
        expect(updateFenceState(false, text)).toBe(false); // 4 fences = even
    });
});

describe('evaluateSplit - no split needed', () => {
    test('returns shouldSplit=false when under SOFT_SPLIT_CHARS', () => {
        const currentMarkdown = makeContent(1000);
        const newChunk = makeContent(100);
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(false);
        expect(result.textForCurrentBubble).toBe('');
        expect(result.textForNewBubble).toBe('');
    });

    test('returns shouldSplit=false at exactly SOFT_SPLIT_CHARS', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS - 100);
        const newChunk = makeContent(100);
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(false);
    });

    test('updates fence state even when not splitting', () => {
        const currentMarkdown = makeContent(100);
        const newChunk = '```\nsome code';
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(false);
        expect(result.newFenceState.insideFence).toBe(true);
    });
});

describe('evaluateSplit - soft split', () => {
    test('splits at paragraph boundary after SOFT_SPLIT_CHARS', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS - 100);
        const newChunk = makeContent(200) + '\n\n' + makeContent(100);
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
        expect(result.textForCurrentBubble).toBe(makeContent(200) + '\n\n');
        expect(result.textForNewBubble).toBe(makeContent(100));
    });

    test('textForCurrentBubble ends with paragraph boundary', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS);
        const newChunk = 'some text\n\nmore text';
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
        expect(result.textForCurrentBubble.endsWith('\n\n')).toBe(true);
    });

    test('recalculates fence state for new bubble content', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS);
        const newChunk = 'text\n\n```\ncode inside new bubble';
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
        expect(result.newFenceState.insideFence).toBe(true);
    });

    test('handles multiple paragraph boundaries - uses last valid one', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS);
        const newChunk = 'para1\n\npara2\n\npara3';
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
        // Should split at the last \n\n boundary
        expect(result.textForNewBubble).toBe('para3');
    });

    test('respects MIN_SPLIT_SIZE - no tiny bubbles', () => {
        // Current markdown is very short, new chunk has early boundary
        const currentMarkdown = makeContent(100);
        const newChunk = makeContent(SOFT_SPLIT_CHARS) + '\n\n' + makeContent(100);
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
        // The split should happen but respect MIN_SPLIT_SIZE
        expect(result.textForCurrentBubble.length + currentMarkdown.length).toBeGreaterThanOrEqual(MIN_SPLIT_SIZE);
    });
});

describe('evaluateSplit - hard split', () => {
    test('forces split at HARD_SPLIT_CHARS when no paragraph boundary', () => {
        const currentMarkdown = makeContent(HARD_SPLIT_CHARS - 100);
        const newChunk = makeContent(200); // No \n\n boundary
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
        expect(result.textForCurrentBubble).toBe('');
        expect(result.textForNewBubble).toBe(newChunk);
    });

    test('hard split inherits fence state from parent', () => {
        const currentMarkdown = makeContent(HARD_SPLIT_CHARS - 100);
        const newChunk = makeContent(200);
        // Note: hard split only fires when !insideFence, so we test the resulting state
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
        // newFenceState should reflect the fence state after processing newChunk
        expect(result.newFenceState.insideFence).toBe(false);
    });

    test('hard split with fence in new chunk updates state correctly', () => {
        const currentMarkdown = makeContent(HARD_SPLIT_CHARS - 100);
        const newChunk = makeContent(100) + '\n```\ncode';
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
        expect(result.newFenceState.insideFence).toBe(true);
    });
});

describe('evaluateSplit - fence protection', () => {
    test('blocks soft split when inside open fence over SOFT_SPLIT_CHARS', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS - 100) + '\n```\ncode';
        const newChunk = 'more code\n\nstill in fence';
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: true });

        // Soft split is blocked (no valid paragraph boundary outside fence)
        // but we're under HARD_SPLIT_CHARS so no split occurs
        expect(result.shouldSplit).toBe(false);
    });

    test('hard split is unconditional even inside fence over HARD_SPLIT_CHARS', () => {
        const currentMarkdown = makeContent(HARD_SPLIT_CHARS - 100) + '\n```\ncode';
        const newChunk = makeContent(200);
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: true });

        // Hard split fires unconditionally at threshold
        expect(result.shouldSplit).toBe(true);
        expect(result.textForCurrentBubble).toBe('');
        expect(result.textForNewBubble).toBe(newChunk);
    });

    test('allows split after fence closes', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS - 100) + '\n```\ncode\n```';
        const newChunk = '\n\ntext outside fence';
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
    });

    test('detects indented fence and blocks split', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS - 100) + '\n  ```\ncode';
        const newChunk = 'more\n\nstill in fence';
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: true });

        expect(result.shouldSplit).toBe(false);
    });
});

describe('evaluateSplit - edge cases', () => {
    test('handles empty new chunk', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS + 100);
        const result = evaluateSplit(currentMarkdown, '', { insideFence: false });

        expect(result.shouldSplit).toBe(false);
    });

    test('handles empty current markdown', () => {
        const newChunk = makeParagraphs(SOFT_SPLIT_CHARS + 1000);
        const result = evaluateSplit('', newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
    });

    test('handles consecutive paragraph boundaries', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS);
        const newChunk = 'text\n\n\n\nmore text'; // Multiple newlines
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
    });

    test('split boundary exactly at SOFT_SPLIT_CHARS', () => {
        const boundary = SOFT_SPLIT_CHARS;
        const currentMarkdown = makeContent(boundary - 5);
        const newChunk = 'xxx\n\nyyy'; // 3 chars + \n\n puts us at exactly boundary
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(result.shouldSplit).toBe(true);
    });
});

describe('evaluateSplit - logging', () => {
    let debugSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {});
    });

    afterEach(() => {
        debugSpy.mockRestore();
    });

    test('logs SOFT SPLIT when splitting at paragraph boundary', () => {
        const currentMarkdown = makeContent(SOFT_SPLIT_CHARS);
        const newChunk = 'text\n\nmore';
        evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(debugSpy).toHaveBeenCalledWith(
            expect.stringContaining('SOFT SPLIT'),
            expect.any(Object)
        );
    });

    test('logs HARD SPLIT when forcing split', () => {
        const currentMarkdown = makeContent(HARD_SPLIT_CHARS - 100);
        const newChunk = makeContent(200);
        evaluateSplit(currentMarkdown, newChunk, { insideFence: false });

        expect(debugSpy).toHaveBeenCalledWith(
            expect.stringContaining('HARD SPLIT'),
            expect.any(Object)
        );
    });

    test('logs HARD SPLIT even when inside fence', () => {
        const currentMarkdown = makeContent(HARD_SPLIT_CHARS - 100);
        const newChunk = makeContent(200);
        evaluateSplit(currentMarkdown, newChunk, { insideFence: true });

        // Hard split is unconditional, so it logs HARD SPLIT not SPLIT BLOCKED
        expect(debugSpy).toHaveBeenCalledWith(
            expect.stringContaining('HARD SPLIT'),
            expect.any(Object)
        );
    });
});

describe('evaluateSplit - fence state persistence across splits', () => {
    test('fence opened in first bubble persists to second', () => {
        // Simulate streaming: first chunk opens a fence
        const chunk1 = makeContent(SOFT_SPLIT_CHARS) + '\n\n```\ncode start';
        const result1 = evaluateSplit('', chunk1, { insideFence: false });

        // After split, new bubble should know it starts inside a fence
        if (result1.shouldSplit) {
            // The new bubble's fence state should reflect the fence in textForNewBubble
            expect(result1.newFenceState.insideFence).toBe(true);
        }
    });

    test('long open fence (>16k but <32k) prevents soft split', () => {
        // Start with an open fence, but under HARD_SPLIT_CHARS
        const currentMarkdown = '```\n' + makeContent(SOFT_SPLIT_CHARS + 5000);
        const newChunk = makeContent(1000) + '\n\nmore';

        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: true });

        // Soft split blocked (inside fence), but under hard threshold so no split
        expect(result.shouldSplit).toBe(false);
    });

    test('very long open fence (>32k) triggers hard split unconditionally', () => {
        // Start with an open fence, over HARD_SPLIT_CHARS
        const currentMarkdown = '```\n' + makeContent(HARD_SPLIT_CHARS);
        const newChunk = makeContent(1000);

        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: true });

        // Hard split fires unconditionally
        expect(result.shouldSplit).toBe(true);
    });

    test('split allowed after fence closes in new chunk', () => {
        // Current markdown has open fence
        const currentMarkdown = '```\n' + makeContent(SOFT_SPLIT_CHARS);
        // New chunk closes fence then has paragraph boundary
        const newChunk = 'end of code\n```\n\nnew paragraph';

        // Fence state: starts inside (true), chunk closes it
        const result = evaluateSplit(currentMarkdown, newChunk, { insideFence: true });

        expect(result.shouldSplit).toBe(true);
    });
});
