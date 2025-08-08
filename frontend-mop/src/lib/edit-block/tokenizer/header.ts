import { markdownLineEnding } from 'micromark-util-character';
import { codes } from 'micromark-util-symbol';
import type { Tokenizer, Code, State } from 'micromark-util-types';
import { makeSafeFx } from '../util';

/** Configuration flags for the header tokenizer. */
export interface HeaderOptions {
  /** If `true`, an incomplete header should yield `nok` instead of `ok`. */
  strict?: boolean; // default = false (lenient, current behaviour)
}

/**
 * Factory function that returns a tokenizer for the header of an edit block.
 * Recognizes `<<<<<<< SEARCH [optional-filename]` followed by a line-ending.
 * On success:
 *   - Leaves the stream positioned *after* the newline/EOF
 *   - Has entered/exited: editBlockHead, editBlockSearchKeyword, (optional) editBlockFilename
 *   - Leaves the parser *inside* `editBlockSearchContent` so that the body tokenizer can start immediately.
 * @param {HeaderOptions} options - Configuration for the tokenizer.
 * @returns {Tokenizer} A tokenizer configured with the specified options.
 */
export const makeTokenizeHeader = (
  { strict = false }: HeaderOptions = {}
): Tokenizer => {
  return function tokenizeHeader(effects, ok, nok) {
    const ctx = this;
    const fx = makeSafeFx('header', effects, ctx, ok, nok);

    let ltCount = 0;

    return start;

    function start(code: Code): State {
      if (code !== codes.lessThan) return fx.nok(code);

      fx.enter('editBlockHead');
      fx.consume(code);
      ltCount = 1;
      return consumeLessThan;
    }

    function consumeLessThan(code: Code): State {
      // Early EOF check
      if (code === codes.eof) {
        fx.exit('editBlockHead');
        return strict ? fx.nok(code) : fx.ok(code);
      }

      if (code === codes.lessThan) {
        ltCount++;
        fx.consume(code);
        return consumeLessThan;
      }

      if (ltCount < 7 || code !== codes.space) {
        fx.exit('editBlockHead');
        return fx.nok(code);
      }
      fx.consume(code);
      fx.exit('editBlockHead');
      fx.enter('editBlockSearchKeyword');
      return checkSearchKeyword(0);
    }

    function checkSearchKeyword(index: number): State {
      return function (code: Code): State {
        // Early EOF check
        if (code === codes.eof) {
          fx.exit('editBlockSearchKeyword');
          return strict ? fx.nok(code) : fx.ok(code);
        }

        const keyword = 'SEARCH';
        if (index < keyword.length) {
          if (code !== keyword.charCodeAt(index)) {
            fx.exit('editBlockSearchKeyword');
            return fx.nok(code);
          }
          fx.consume(code);
          return checkSearchKeyword(index + 1);
        }
        return afterSearchKeyword(code);
      };
    }

    function afterSearchKeyword(code: Code): State {
      if (markdownLineEnding(code) || code === codes.eof) {
        fx.exit('editBlockSearchKeyword');
        return fx.ok(code);
      }
      if (code === codes.space || code === codes.horizontalTab) {
        fx.consume(code);
        return afterSearchKeyword;
      }
      fx.exit('editBlockSearchKeyword');
      fx.enter('editBlockFilename');
      return inFilename(code);
    }

    function inFilename(code: Code): State {
      if (markdownLineEnding(code) || code === codes.eof) {
        fx.exit('editBlockFilename');
        return fx.ok(code);
      }
      if (code === codes.space || code === codes.horizontalTab) {
        fx.exit('editBlockFilename');
        return fx.nok(code);
      }
      fx.consume(code);
      return inFilename;
    }
  };
};
