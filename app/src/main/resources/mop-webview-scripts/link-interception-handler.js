// Intercept link clicks and open external links in system browser
// Parameter: EMBEDDED_SERVER_PORT will be replaced at runtime
(function() {
    var embeddedPort = 'EMBEDDED_SERVER_PORT';
    
    if (window.javaBridge) {
        window.javaBridge.jsLog('INFO', 'Link interception script starting, embedded port: ' + embeddedPort);
    }
    
    try {
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
                    if (window.javaBridge) {
                        window.javaBridge.jsLog('INFO', 'Intercepting external link: ' + url);
                    }
                    
                    // External link - prevent navigation and open in browser
                    event.preventDefault();
                    event.stopPropagation();
                    
                    if (window.javaBridge && window.javaBridge.openExternalLink) {
                        window.javaBridge.openExternalLink(url);
                    } else {
                        console.error('javaBridge.openExternalLink not available');
                        if (window.javaBridge) {
                            window.javaBridge.jsLog('ERROR', 'javaBridge.openExternalLink not available');
                        }
                    }
                }
            }
        }, true); // Use capture phase to intercept before other handlers
        
        if (window.javaBridge) {
            window.javaBridge.jsLog('INFO', 'Link interception event listener installed successfully');
        }
    } catch (e) {
        if (window.javaBridge) {
            window.javaBridge.jsLog('ERROR', 'Link interception failed: ' + e.message + ', stack: ' + e.stack);
        } else {
            console.error('Link interception failed:', e);
        }
    }
})();
