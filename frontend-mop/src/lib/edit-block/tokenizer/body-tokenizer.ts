import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Code, Effects, State, Tokenizer } from 'micromark-util-types';
import {makeSafeFx, SafeFx} from '../util';

export interface BodyTokenizerOpts {
    divider: Tokenizer;
    tail: Tokenizer;
    fenceClose?: Tokenizer; // Optional tokenizer to detect premature closing fence
}

/**
 * Returns a tokenizer that handles the body of an edit block, starting in search mode
 * and finishing after the tail has been consumed.
 */
export function makeEditBlockBodyTokenizer(
    { divider, tail, fenceClose }: BodyTokenizerOpts
): Tokenizer {
    return function tokenizeBody(effects, ok, nok) {
        const ctx = this;
        const fx = makeSafeFx('tokenizeBody', effects, ctx, ok, nok);
        let dividerSeen = false;
        let tailSeen = false;

        const hasFenceClose = typeof fenceClose === 'function';

        fx.enter('editBlockSearchContent');

        // Search content state machine
        function searchLineStart(code: Code): State {
            if (code === codes.eof) {
                return inSearch(code);
            }

            if (markdownLineEnding(code) || code === codes.space || code === codes.horizontalTab) {
                // Blank line - emit it as its own empty chunk
                fx.enter('data');
                fx.consume(code);
                fx.exit('data');
                return searchLineStart;
            }
            if (hasFenceClose && (code === codes.graveAccent || code === codes.tilde)) {
                // Look-ahead for premature closing fence
                return effects.check(
                    { tokenize: fenceClose!, concrete: true },
                    fx.nok, // Success: premature close, fail body parsing
                    searchChunkStart // Failure: treat as regular content
                )(code);
            }
            if (code === codes.equalsTo) {
                // Look-ahead for the divider (=======)
                return effects.check(
                    { tokenize: divider, concrete: true },
                    afterDividerCheck, // Success: transition without including divider
                    searchChunkStart // Failure: treat as regular content
                )(code);
            }
            return searchChunkStart(code);
        }

        function searchChunkStart(code: Code): State {
            fx.enter('data');
            return searchChunkContinue(code);
        }

        function searchChunkContinue(code: Code): State {
            if (code === codes.eof) {
                fx.exit('data');
                return inSearch(code);
            }
            if (markdownLineEnding(code)) {
                fx.consume(code);
                fx.exit('data');
                return searchLineStart; // New logical line
            }
            fx.consume(code); // Regular payload
            return searchChunkContinue;
        }

        function inSearch(code: Code): State {
            // This function is reached when we are at EOF, and we haven't found a divider.
            fx.exit('editBlockSearchContent');
            (ctx as any)._editBlockHasDivider = dividerSeen;
            (ctx as any)._editBlockCompleted = tailSeen;
            return fx.ok(code);
        }

        function afterDividerCheck(code: Code): State {
            fx.exit('editBlockSearchContent');
            // Now consume the divider
            return effects.attempt(
                { tokenize: divider, concrete: true },
                afterDividerConsumed,
                fx.nok
            )(code);
        }

        function afterDividerConsumed(code: Code): State {
            dividerSeen = true;
            fx.enter('editBlockReplaceContent');
            return replaceLineStart(code);
        }

        // Replace content state machine
        function replaceLineStart(code: Code): State {
            if (code === codes.eof) {
                return inReplace(code);
            }

            if (markdownLineEnding(code) || code === codes.space || code === codes.horizontalTab) {
                // Blank line - emit it as its own empty chunk
                fx.enter('data');
                fx.consume(code);
                fx.exit('data');
                return replaceLineStart;
            }
            if (hasFenceClose && (code === codes.graveAccent || code === codes.tilde)) {
                // Look-ahead for premature closing fence
                return effects.check(
                    { tokenize: fenceClose!, concrete: true },
                    fx.nok, // Success: premature close, fail body parsing
                    replaceChunkStart // Failure: treat as regular content
                )(code);
            }
            if (code === codes.greaterThan) {
                // Look-ahead for the tail (>>>>>>> REPLACE ...)
                return effects.check(
                    { tokenize: tail, concrete: true },
                    afterTailCheck, // Success: transition without including tail
                    replaceChunkStart // Failure: treat as regular content
                )(code);
            }
            return replaceChunkStart(code);
        }

        function replaceChunkStart(code: Code): State {
            fx.enter('data');
            return replaceChunkContinue(code);
        }

        function replaceChunkContinue(code: Code): State {
            if (code === codes.eof) {
                fx.exit('data');
                return inReplace(code);
            }
            if (markdownLineEnding(code)) {
                fx.consume(code);
                fx.exit('data');
                return replaceLineStart;
            }
            fx.consume(code);
            return replaceChunkContinue;
        }

        function inReplace(code: Code): State {
            // Reached at EOF without tail.
            fx.exit('editBlockReplaceContent');
            (ctx as any)._editBlockHasDivider = dividerSeen;
            (ctx as any)._editBlockCompleted = tailSeen;
            return fx.ok(code);
        }

        function afterTailCheck(code: Code): State {
            fx.exit('editBlockReplaceContent');
            // Now consume the tail
            return effects.attempt(
                { tokenize: tail, concrete: true },
                afterTailConsumed,
                fx.nok
            )(code);
        }

        function afterTailConsumed(code: Code): State {
            tailSeen = true;
            (ctx as any)._editBlockHasDivider = dividerSeen;
            (ctx as any)._editBlockCompleted = tailSeen;
            return fx.ok(code);
        }

        // Start in search mode
        return searchLineStart;
    };
}
