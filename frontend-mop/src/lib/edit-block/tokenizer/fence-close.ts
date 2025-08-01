import { Code, State, Tokenizer } from 'micromark-util-types';
import { makeSafeFx } from '../util';
import { codes } from 'micromark-util-symbol';


/**
 * Tokenizer for the closing fence of a fenced edit block.
 * The opening fence tokenizer must store these two values on the context:
 *   _editBlockFenceMarker - the marker character (` or ~)
 *   _editBlockFenceSize   - how many markers opened the block (≥3)
 * Succeeds if:
 *   - Line starts with ≥ that many of the same marker
 *   - Immediately followed by line-ending or EOF
 */
export const tokenizeFenceClose: Tokenizer = function (effects, ok, nok) {
    const ctx = this as any;
    const fx = makeSafeFx('fenceClose', effects, ctx, ok, nok);

    const marker: Code = ctx._editBlockFenceMarker;
    const need = ctx._editBlockFenceSize as number;

    if (marker === undefined || need === undefined) {
        throw new Error('Fence-close tokenizer invoked without marker/size');
    }

    let seen = 0;

    return start;

    function start(code: Code): State {
        //incomplete check
        if (code === codes.eof) {
            fx.enter('editBlockFenceClose');
            fx.exit('editBlockFenceClose');
            return fx.ok(code);
        }

        if (code !== marker) return fx.nok(code);

        fx.enter('editBlockFenceClose');
        fx.consume(code);
        seen = 1;
        return inFence;
    }

    function inFence(code: Code): State {
        if (code === marker) {
            fx.consume(code);
            seen++;
            return inFence;
        }
        if (seen < need) {
            fx.exit('editBlockFenceClose');
            return fx.nok(code);
        }
        fx.exit('editBlockFenceClose');
        return fx.ok(code);
    }
};
