import Mark from 'mark.js';

export type SearchState = {
  total: number;      // number of matches
  current: number;    // 1-based index, or 0 if none
  query: string;
  caseSensitive: boolean;
};

export type SearchController = {
  setQuery(query: string, caseSensitive: boolean): void;
  clear(): void;
  next(): void;
  prev(): void;
  scrollCurrent(): void;
  getState(): SearchState;
  onContentChanged(): void; // call after Svelte updates DOM
  dispose(): void;
};

const HIGHLIGHT_CLASS = 'search-highlight';
const CURRENT_CLASS = 'search-current';

export function createSearchController(container: HTMLElement): SearchController {
  const mark = new Mark(container);
  let query = '';
  let caseSensitive = false;
  let matches: HTMLElement[] = [];
  let currentIdx = -1; // 0-based, -1 means none
  let rafId = 0;
  let marking = false;

  function notifyJava(): void {
    const total = matches.length;
    const current = total === 0 || currentIdx < 0 ? 0 : currentIdx + 1; // 1-based
    try {
      window.javaBridge?.searchStateChanged?.(total, current);
    } catch {
      // no-op
    }
  }

  function clearCurrentClass(): void {
    for (const el of matches) el.classList.remove(CURRENT_CLASS);
  }

  function applyCurrent(): void {
    clearCurrentClass();
    if (currentIdx >= 0 && matches[currentIdx]) {
      matches[currentIdx].classList.add(CURRENT_CLASS);
    }
  }

  function scrollToCurrent(): void {
    if (currentIdx >= 0 && matches[currentIdx]) {
      matches[currentIdx].scrollIntoView({ block: 'center' });
    }
  }

  function unmarkAll(cb?: () => void): void {
    marking = true;
    clearCurrentClass();
    mark.unmark({
      className: HIGHLIGHT_CLASS,
      done: () => {
        matches = [];
        currentIdx = -1;
        marking = false;
        notifyJava();
        cb?.();
      }
    });
  }

  function remark(options?: { shouldScroll?: boolean }): void {
    const shouldScroll = options?.shouldScroll ?? true; // Default to true

    if (!query) {
      unmarkAll();
      return;
    }
    if (marking) return;

    marking = true;
    mark.unmark({
      className: HIGHLIGHT_CLASS,
      done: () => {
        mark.mark(query, {
          separateWordSearch: false,
          caseSensitive,
          acrossElements: true,
          diacritics: false,
          element: 'span',
          className: HIGHLIGHT_CLASS,
          exclude: ['.copy-button']
        });
        matches = Array.from(container.querySelectorAll<HTMLElement>(`span.${HIGHLIGHT_CLASS}`));

        if (matches.length === 0) currentIdx = -1;
        else if (currentIdx < 0) currentIdx = 0;
        else if (currentIdx >= matches.length) currentIdx = matches.length - 1;

        applyCurrent();
        notifyJava();
        marking = false;

        if (shouldScroll && currentIdx >= 0) {
          cancelAnimationFrame(rafId);
          rafId = requestAnimationFrame(scrollToCurrent);
        } else if (!shouldScroll) {
          // If we're explicitly not scrolling, ensure no previous scroll is pending
          cancelAnimationFrame(rafId);
        }
      }
    });
  }

  function setQueryInternal(q: string, cs: boolean): void {
    if (q.length > 0 && q.length < 3) {
      // If query is present but too short, clear previous search and notify
      unmarkAll();
      query = q; // Keep the query value, but don't perform the search
      caseSensitive = cs;
      notifyJava();
      return;
    }

    const changed = q !== query || cs !== caseSensitive;
    query = q;
    caseSensitive = cs;

    if (!query) {
      unmarkAll();
      return;
    }
    if (changed) currentIdx = -1;
    remark(); // This call will use the default `shouldScroll: true`
  }

  function next(): void {
    if (matches.length === 0) return;
    currentIdx = (currentIdx + 1) % matches.length;
    applyCurrent();
    scrollToCurrent();
    notifyJava();
  }

  function prev(): void {
    if (matches.length === 0) return;
    currentIdx = (currentIdx - 1 + matches.length) % matches.length;
    applyCurrent();
    scrollToCurrent();
    notifyJava();
  }

  function getState(): SearchState {
    return {
      total: matches.length,
      current: matches.length === 0 ? 0 : currentIdx + 1,
      query,
      caseSensitive
    };
  }

  function clear(): void {
    setQueryInternal('', caseSensitive);
  }

  function onContentChanged(): void {
    if (!query) return;
    remark({ shouldScroll: false }); // Do not scroll when content changes passively
  }

  function dispose(): void {
    cancelAnimationFrame(rafId);
    unmarkAll();
  }

  return {
    setQuery: setQueryInternal,
    clear,
    next,
    prev,
    scrollCurrent: scrollToCurrent,
    getState,
    onContentChanged,
    dispose
  };
}
