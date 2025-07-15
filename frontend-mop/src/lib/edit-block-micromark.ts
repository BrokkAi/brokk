import type {
  Extension,
  State,
  Tokenizer,
  Effects,
  Resolver,
  Event
} from 'micromark-util-types';

/**
 * ──────────────────────────────────────────────────────────────────────────────
 * Un-fenced edit-block tokenizer
 *   HEAD  := ^ {0,3} <{5,9}  "SEARCH" …
 *   DIV   := ^ {0,3} ={5,9}
 *   TAIL  := ^ {0,3} >{5,9}  "REPLACE" …
 * ──────────────────────────────────────────────────────────────────────────────
 */
export function editBlockUnfenced(): Extension {
  const OPEN_RE = /^ {0,3}<{5,9}\s+SEARCH\b/;
  const CLOSE_RE = /^ {0,3}>{5,9}\s+REPLACE\b/;

  const tokenize: Tokenizer = function (effects, ok, nok) {
    let lineBuffer: number[] = [];
    let editTok: any; // Store the token to update attributes later

    return atLineStart;

    /* ── <line-start> ─────────────────────────────────── */
    function atLineStart(code: number): State {
      if (code === 60 /* '<' */) {
        editTok = effects.enter('editBlock');
        editTok._attributes = { incomplete: true };
        effects.enter('editBlockFence');
        effects.consume(code);
        lineBuffer = [code];
        return scanOpening;
      }
      return nok(code);
    }

    /* –– opening line (“<<<<<<< SEARCH …”) –––––––––––––– */
    function scanOpening(code: number): State {
      if (code === 10 || code === null) {
        const lineStr = String.fromCharCode(...lineBuffer);
        if (OPEN_RE.test(lineStr)) {
          effects.exit('editBlockFence');
          lineBuffer = [];
          if (code !== null) effects.consume(code);
          return body;
        }
        effects.exit('editBlockFence');
        effects.exit('editBlock');
        return nok(code);
      }
      lineBuffer.push(code);
      effects.consume(code);
      return scanOpening;
    }

    /* –– body lines –––––––––––––––––––––––––––––––––––– */
    function body(code: number): State {
      if (code === null) {
        effects.exit('editBlock');
        return ok(code);
      }
      if (code === 10 /* \n */) {
        const lineStr = String.fromCharCode(...lineBuffer);
        if (CLOSE_RE.test(lineStr)) {
          editTok._attributes.incomplete = false;
          effects.enter('editBlockFence');
          effects.consume(code);
          effects.exit('editBlockFence');
          effects.exit('editBlock');
          return ok;
        }
        lineBuffer = [];
        effects.consume(code);
        return body;
      }
      lineBuffer.push(code);
      effects.consume(code);
      return body;
    }
  };

  return {
    flow: {
      60: { tokenize, partial: true } // '<'
    }
  };
}

/**
 * ──────────────────────────────────────────────────────────────────────────────
 * Fenced variant piggy-backs on the standard "``` … ```" fence.
 * We post-process the fence events; if the body contains `<<<<<<< SEARCH`,
 * we *replace* the fence events by a single editBlock token pair.
 * ──────────────────────────────────────────────────────────────────────────────
 */
export function editBlockFenceResolver(): Extension {
  /** Collapse `codeFenced` enter..exit pairs that contain SEARCH marker. */
  const resolve: Resolver['resolveAll'] = (events, ctx) => {
    const out: Event[] = [];
    for (let i = 0; i < events.length; i++) {
      const ev = events[i];
      if (ev[0] === 'enter' && ev[1].type === 'codeFenced') {
        // locate matching exit (depth-aware)
        let depth = 1, j = i + 1;
        while (j < events.length && depth) {
          if (events[j][1].type === 'codeFenced') {
            depth += events[j][0] === 'enter' ? 1 : -1;
          }
          j++;
        }
        if (j < events.length) {
          const body = ctx.sliceSerialize(events[i + 1][2], events[j - 1][2]);
          if (body.includes('<<<<<<< SEARCH')) {
            out.push([
              'enter',
              {
                type: 'editBlock',
                _attributes: { _info: ev[1].lang }
              } as any,
              ev[2]
            ]);
            out.push(['exit', { type: 'editBlock' } as any, events[j][2]]);
            i = j - 1; // skip original fence events
            continue;
          }
        }
      }
      out.push(ev);
    }
    return out;
  };

  return { resolveAll: resolve };
}
