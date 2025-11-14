// Platform-specific scroll behavior configuration
// Parameter: SCROLL_SPEED_FACTOR will be replaced at runtime
(function() {
    try {
        var scrollSpeedFactor = SCROLL_SPEED_FACTOR;
        var minScrollThreshold = 0.5;
        var smoothScrolls = new Map();

        function findScrollable(el) {
            while (el && el !== document.body && el !== document.documentElement) {
                var style = getComputedStyle(el);
                var canScrollY = (style.overflowY === 'auto' || style.overflowY === 'scroll') && el.scrollHeight > el.clientHeight;
                var canScrollX = (style.overflowX === 'auto' || style.overflowX === 'scroll') && el.scrollWidth > el.clientWidth;
                if (canScrollY || canScrollX) return el;
                el = el.parentElement;
            }
            return document.scrollingElement || document.documentElement || document.body;
        }

        window.addEventListener('wheel', function(ev) {
            if (ev.ctrlKey || ev.metaKey) { return; }
            var target = findScrollable(ev.target);
            if (!target) return;

            ev.preventDefault();

            var dx = ev.deltaX * scrollSpeedFactor;
            var dy = ev.deltaY * scrollSpeedFactor;

            if (Math.abs(dx) < minScrollThreshold) dx = 0;
            if (Math.abs(dy) < minScrollThreshold) dy = 0;

            if (dx) {
                var newScrollLeft = target.scrollLeft + Math.round(dx);
                var maxScrollLeft = target.scrollWidth - target.clientWidth;
                target.scrollLeft = Math.max(0, Math.min(newScrollLeft, maxScrollLeft));
            }
            if (dy) {
                var newScrollTop = target.scrollTop + Math.round(dy);
                var maxScrollTop = target.scrollHeight - target.clientHeight;
                target.scrollTop = Math.max(0, Math.min(newScrollTop, maxScrollTop));
            }
        }, { passive: false, capture: true });
    } catch (e) {
        if (window.javaBridge) window.javaBridge.jsLog('ERROR', 'wheel override failed: ' + e);
    }
})();
