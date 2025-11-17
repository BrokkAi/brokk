import {markdownLineEnding} from 'micromark-util-character';
import {codes} from 'micromark-util-symbol';
import type {Code, State, Tokenizer} from 'micromark-util-types';
import {makeEditBlockBodyTokenizer} from './tokenizer/body-tokenizer';
import {tokenizeDivider} from './tokenizer/divider-tokenizer';
import {tokenizeFenceClose} from './tokenizer/fence-close';
import {tokenizeFenceOpen} from './tokenizer/fence-open';
import {tokenizeFilename} from './tokenizer/filename';
import {tokenizeTail} from './tokenizer/tail-tokenizer';
import {makeSafeFx} from './util';
import {makeTokenizeHeader} from './tokenizer/header';
import {tokenizeGitDiff} from './tokenizer/git-diff-parser';

// ---------------------------------------------------------------------------
// 1.  Orchestrator for edit block parsing
// ---------------------------------------------------------------------------
export const tokenizeFencedEditBlock: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('fencedEditBlock', effects, ctx, ok, nok);

    function eatEndLineAndCheckEof(code: Code, next: State) {
        if (markdownLineEnding(code)) {
            fx.enter("chunk")
            fx.consume(code);
            fx.exit("chunk")
            return next;
        }
        //eager recognition check
        if (code === codes.eof) {
            return done(code);
        }
        return null;
    }

    // Use the existing body tokenizer for search/replace content, with fence close guard
    const tokenizeBody = makeEditBlockBodyTokenizer({
        divider: tokenizeDivider,
        tail: tokenizeTail
    });

    // Use strict header tokenizer for unfenced blocks to ensure complete header
    const tokenizeHeader = makeTokenizeHeader({ strict: false });


    return start;

    function start(code: Code): State {
        fx.enter('editBlock');
        return effects.attempt(
            { tokenize: tokenizeFenceOpen, concrete: true },
            afterOpen,
            fx.nok
        )(code);
    }

    function afterOpen(code: Code): State {
        // Tolerate optional horizontal whitespace right after the opening fence
        if (code === codes.space || code === codes.horizontalTab) {
            fx.enter("chunk");
            fx.consume(code);
            fx.exit("chunk");
            return afterOpen; // continue consuming any further spaces/tabs
        }

        const next = eatEndLineAndCheckEof(code, afterOpen);
        if (next) return next;

        // Check for git diff format first (starts with [)
        if (code === codes.leftSquareBracket) {
            return effects.attempt(
                { tokenize: tokenizeGitDiff, concrete: true },
                afterGitDiff,
                fx.nok
            )(code);
        }

        //Filename is optional, so we need to check for the header first
        return effects.check(
            { tokenize: tokenizeHeader, concrete: true },
            parseHeader,
            parseFilename
        )(code);
    }

    function parseFilename(code: Code): State {
        return effects.attempt(
            { tokenize: tokenizeFilename, concrete: true },
            afterFilename,
            fx.nok // Fail on any real error in filename parsing
        )(code);
    }

    function parseHeader(code: Code): State {
        return effects.attempt(
            { tokenize: tokenizeHeader, concrete: true },
            afterHeader,
            fx.nok
        )(code);
    }

    function afterFilename(code: Code): State {
        const next = eatEndLineAndCheckEof(code, afterFilename)
        if (next) return next;
        return effects.attempt(
            { tokenize: tokenizeHeader, concrete: true },
            afterHeader,
            fx.nok
        )(code);
    }

    function afterHeader(code: Code): State {
        const next = eatEndLineAndCheckEof(code, afterHeader)
        if (next) return next;
        return effects.attempt(
            { tokenize: tokenizeBody, concrete: true },
            afterBody,
            fx.nok
        )(code);
    }

    function afterGitDiff(code: Code): State {
        const next = eatEndLineAndCheckEof(code, afterGitDiff);
        if (next) return next;

        // For git diff format, the content ends with ] - we need to consume it
        if (code === codes.rightSquareBracket) {
            fx.enter("chunk");
            fx.consume(code);
            fx.exit("chunk");
            return afterGitDiffBracket;
        }

        // If fence closes after git-diff, mark as complete on success
        return effects.attempt(
            { tokenize: tokenizeFenceClose, concrete: true },
            doneComplete,
            fx.nok
        )(code);
    }

    function afterGitDiffBracket(code: Code): State {
        const next = eatEndLineAndCheckEof(code, afterGitDiffBracket);
        if (next) return next;

        // Check if immediately followed by closing backticks (inline format)
        if (code === codes.graveAccent) {
            return effects.attempt(
                { tokenize: tokenizeFenceClose, concrete: true },
                doneComplete,  // After successful fence close, we're done
                doneComplete   // If fence close fails, we're still done (malformed but we'll accept it) but mark complete
            )(code);
        }

        // If there are no backticks after ], treat it as complete git-diff block
        return doneComplete(code);
    }


    function afterBody(code: Code): State {
        const next = eatEndLineAndCheckEof(code, afterBody)
        if (next) return next;
        // Always attempt to close the fence, but completion depends on tailSeen in body
        return effects.attempt(
            { tokenize: tokenizeFenceClose, concrete: true },
            doneMaybeComplete,
            fx.nok
        )(code);
    }

    function markComplete(): void {
        fx.enter('editBlockComplete');
        fx.exit('editBlockComplete');
    }

    function doneMaybeComplete(code: Code): State {
        // Only mark structural completion for SEARCH/REPLACE when the tail was actually consumed.
        if ((ctx as any)._editBlockCompleted === true) {
            markComplete();
        }
        return done(code);
    }

    function doneComplete(code: Code): State {
        // Mark structural completion (used for git-diff fenced cases)
        markComplete();
        return done(code);
    }

    function done(code: Code): State {
        fx.exit('editBlock');
        return fx.ok(code);
    }
};
