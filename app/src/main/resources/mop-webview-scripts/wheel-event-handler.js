// Override wheel event handling for platform-specific scroll behavior
// Parameter: SCROLL_SPEED_FACTOR will be replaced at runtime
(function() {
    try {
        // Platform-specific scroll behavior configuration
        var scrollSpeedFactor = SCROLL_SPEED_FACTOR; // Platform-specific scroll speed factor
        var minScrollThreshold = 0.5;                // Minimum delta to process (prevents jitter)
        var smoothingFactor = 0.8;                   // Smoothing for very small movements

        var smoothScrolls = new Map(); // Track ongoing smooth scrolls per element
        var momentum = new Map();      // Track momentum per element

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

        function smoothScroll(element, targetX, targetY, duration) {
            duration = duration || animationDuration;
            var startX = element.scrollLeft;
            var startY = element.scrollTop;
            var deltaX = targetX - startX;
            var deltaY = targetY - startY;
            var startTime = performance.now();

            // Cancel any existing smooth scroll for this element
            var existing = smoothScrolls.get(element);
            if (existing) {
                cancelAnimationFrame(existing);
            }

            function animate(currentTime) {
                var elapsed = currentTime - startTime;
                var progress = Math.min(elapsed / duration, 1);

                // Ease out cubic for smooth deceleration
                var eased = 1 - Math.pow(1 - progress, 3);

                element.scrollLeft = startX + deltaX * eased;
                element.scrollTop = startY + deltaY * eased;

                if (progress < 1) {
                    var animId = requestAnimationFrame(animate);
                    smoothScrolls.set(element, animId);
                } else {
                    smoothScrolls.delete(element);
                }
            }

            var animId = requestAnimationFrame(animate);
            smoothScrolls.set(element, animId);
        }

        window.addEventListener('wheel', function(ev) {
            if (ev.ctrlKey || ev.metaKey) { return; } // let zoom gestures pass
            var target = findScrollable(ev.target);
            if (!target) return;

            ev.preventDefault();

            var dx = ev.deltaX * scrollSpeedFactor;
            var dy = ev.deltaY * scrollSpeedFactor;

            // Filter out very small deltas to prevent jitter
            if (Math.abs(dx) < minScrollThreshold) dx = 0;
            if (Math.abs(dy) < minScrollThreshold) dy = 0;

            // Apply scroll immediately with rounding to prevent sub-pixel issues
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
