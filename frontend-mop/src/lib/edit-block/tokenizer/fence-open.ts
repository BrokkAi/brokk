import { codes } from 'micromark-util-symbol';
import type { Code, State, Tokenizer } from 'micromark-util-types';
import { makeSafeFx } from '../util';

/**
 * Tokenizer for the opening fence of an edit block.
 * Recognizes a sequence of backticks (`) or tildes (~) of length >= 3.
 * Rejects if there is a space or tab immediately after the fence.
 * Stores the marker and size on the context for later validation of the closing fence.
 */
export const tokenizeFenceOpen: Tokenizer = function (effects, ok, nok) {
  const ctx = this;
  const fx = makeSafeFx('fenceOpen', effects, ctx, ok, nok);

  let marker: Code; // Will store ` or ~
  let size = 0;     // Number of consecutive markers seen

  return start;

  function start(code: Code): State {
    if (code !== codes.graveAccent && code !== codes.tilde) {
      return fx.nok(code);
    }

    marker = code;
    fx.enter('editBlockFenceOpen'); // Just the opening fence token
    consumeMarker(code);
    return inFence;
  }

  function inFence(code: Code): State {
    if (code === marker) {
      consumeMarker(code);
      return inFence;
    }

    if (size < 3) {
      fx.exit('editBlockFenceOpen');
      return fx.nok(code);
    }

    // Reject if there's a space or tab right after the fence (eager recognition)
    if (code === codes.space || code === codes.horizontalTab) {
      fx.exit('editBlockFenceOpen');
      return fx.nok(code);
    }

    fx.exit('editBlockFenceOpen');

    // Store marker and size for closing fence validation
    (ctx as any)._editBlockFenceMarker = marker;
    (ctx as any)._editBlockFenceSize = size;

    return fx.ok(code); // Hand back to orchestrator, positioned after fence
  }

  function consumeMarker(c: Code) {
    size++;
    fx.consume(c);
  }
};
