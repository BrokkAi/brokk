import { Tokenizer, State, Code } from 'micromark-util-types';
import { tokenizeFilename } from './tokenizer/filename';
import { makeSafeFx } from './util';
import { makeEditBlockBodyTokenizer } from './body-tokenizer';
import { tokenizeFenceOpen } from './tokenizer/fence-open';

// ---------------------------------------------------------------------------
// 1.  Sub-tokenisers for different parts of the edit block
// ---------------------------------------------------------------------------

const stubTokenizer = (label: string): Tokenizer =>
    function (effects, _ok, nok) {
        const fx = makeSafeFx(label, effects, /*ctx*/ this, _ok, nok);
        return (code: Code): State => fx.ok(code);
    };

// export { tokenizeFenceOpen };
export const tokenizeBlankLines = stubTokenizer('blankLines');
export const tokenizeHeader = stubTokenizer('header');
export const tokenizeFenceClose = stubTokenizer('fenceClose');

// We *can* reuse the existing body-tokeniser immediately.
export const tokenizeBody = stubTokenizer('body');
//     makeEditBlockBodyTokenizer({
//     divider: stubTokenizer('divider'),  // TODO real divider
//     tail:    stubTokenizer('tail')      // TODO real tail
// });

// ---------------------------------------------------------------------------
// 2.  Orchestrator
// ---------------------------------------------------------------------------
export const tokenizeEditBlock: Tokenizer = function (effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('editBlockOrchestrator', effects, ctx, ok, nok);

    return start;

    // ────────────────────────────────────────────────────────────────────
    // open-fence  →  filename?  →  blankLines  →  header  →  body → close
    // ────────────────────────────────────────────────────────────────────
    function start(code: Code): State {
        fx.enter('editBlock');
        return effects.attempt(
            { tokenize: tokenizeFenceOpen, concrete: true },
            afterOpen,
            fx.nok
        )(code);
    }

    function afterOpen(code: Code): State {
        return effects.attempt(
            { tokenize: tokenizeFilename, concrete: true },
            done,
            fx.nok // Fail on any real error in filename parsing
        )(code);
    }

    // function afterFilename(code: Code): State {
    //     return effects.attempt(
    //         { tokenize: tokenizeBlankLines, concrete: true },
    //         afterBlankLines,
    //         fx.nok
    //     )(code);
    // }
    //
    // function afterBlankLines(code: Code): State {
    //     return effects.attempt(
    //         { tokenize: tokenizeHeader, concrete: true },
    //         afterHeader,
    //         fx.nok
    //     )(code);
    // }
    //
    // function afterHeader(code: Code): State {
    //     return effects.attempt(
    //         { tokenize: tokenizeBody, concrete: true },
    //         afterBody,
    //         fx.nok
    //     )(code);
    // }
    //
    // function afterBody(code: Code): State {
    //     return effects.attempt(
    //         { tokenize: tokenizeFenceClose, concrete: true },
    //         done,
    //         fx.nok
    //     )(code);
    // }

    function done(code: Code): State {
        fx.exit('editBlock');
        return fx.ok(code);
    }
};
