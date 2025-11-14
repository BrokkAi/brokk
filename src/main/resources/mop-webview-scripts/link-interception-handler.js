// Intercept link clicks and open external links in system browser
// Parameter: EMBEDDED_SERVER_PORT will be replaced at runtime
(function() {
    try {
        var embeddedPort = 'EMBEDDED_SERVER_PORT';
        
        // Intercept all clicks on anchor tags
        document.addEventListener('click', function(event) {
            var target = event.target;
            
            // Walk up the DOM to find the nearest <a> tag
            while (target && target.tagName !== 'A') {
                target = target.parentElement;
            }
            
            if (target && target.tagName === 'A' && target.href) {
                var url = target.href;
                var isHttpLink = url.startsWith('http://') || url.startsWith('https://');
                var isEmbeddedServer = url.includes('127.0.0.1:' + embeddedPort);
                
                if (isHttpLink && !isEmbeddedServer) {
                    // External link - prevent navigation and open in browser
                    event.preventDefault();
                    if (window.javaBridge && window.javaBridge.openExternalLink) {
                        window.javaBridge.openExternalLink(url);
                    } else {
                        console.error('javaBridge.openExternalLink not available');
                    }
                }
                // For internal links, let the default navigation happen
            }
        }, true); // Use capture phase to intercept before other handlers
        
        if (window.javaBridge) {
            window.javaBridge.jsLog('INFO', 'Link interception installed');
        }
    } catch (e) {
        if (window.javaBridge) {
            window.javaBridge.jsLog('ERROR', 'Link interception failed: ' + e);
        }
    }
})();
