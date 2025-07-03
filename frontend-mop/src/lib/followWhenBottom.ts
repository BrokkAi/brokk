export function followWhenBottom(
  node: HTMLElement,
  { behavior = 'smooth', threshold = 2 }: { behavior?: ScrollBehavior; threshold?: number } = {}
) {
  let stickToBottom = true;
  let raf: number | null = null;

  function atBottom() {
    return node.scrollHeight - (node.scrollTop + node.clientHeight) <= threshold;
  }

  function detachIfAwayFromBottom() {
    if (!atBottom()) {
      stickToBottom = false;
    }
  }

  // Listen for user input events to detect intent to stop auto-scrolling
  node.addEventListener('wheel', detachIfAwayFromBottom, { passive: true });
  node.addEventListener('touchstart', detachIfAwayFromBottom, { passive: true });
  node.addEventListener('mousedown', detachIfAwayFromBottom);
  node.addEventListener('keydown', (e) => {
    if (['PageUp', 'PageDown', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(e.key)) {
      detachIfAwayFromBottom();
    }
  });

  // Re-attach auto-scrolling when user scrolls back to the bottom
  function checkReattach() {
    if (!stickToBottom && atBottom()) {
      stickToBottom = true;
    }
  }
  node.addEventListener('scroll', checkReattach);

  const mutObs = new MutationObserver(() => {
    // Cancel any pending scroll to avoid multiple animations
    if (raf !== null) {
      cancelAnimationFrame(raf);
    }
    // Scroll after the DOM batch finishes
    raf = requestAnimationFrame(() => {
      if (stickToBottom) {
        const jumpSize = node.scrollHeight - node.scrollTop - node.clientHeight;
        const scrollBehavior = jumpSize > node.clientHeight ? 'auto' : behavior;
        node.scroll({ top: node.scrollHeight, behavior: scrollBehavior });
      }
      raf = null;
    });
  });

  mutObs.observe(node, { childList: true, subtree: true });

  return {
    update(opts: { behavior?: ScrollBehavior; threshold?: number } = {}) {
      behavior = opts.behavior ?? behavior;
      threshold = opts.threshold ?? threshold;
    },
    destroy() {
      node.removeEventListener('wheel', detachIfAwayFromBottom);
      node.removeEventListener('touchstart', detachIfAwayFromBottom);
      node.removeEventListener('mousedown', detachIfAwayFromBottom);
      node.removeEventListener('keydown', detachIfAwayFromBottom);
      node.removeEventListener('scroll', checkReattach);
      mutObs.disconnect();
      if (raf !== null) {
        cancelAnimationFrame(raf);
      }
    }
  };
}
