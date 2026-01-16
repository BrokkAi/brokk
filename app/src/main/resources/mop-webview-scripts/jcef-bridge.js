(function() {
    if (window.javaBridge) {
        console.log('javaBridge already exists, skipping injection');
        return;
    }

    console.log('Injecting JCEF javaBridge');

    window.javaBridge = {
        onAck: function(epoch) {
            window.cefQuery({
                request: JSON.stringify({method: 'onAck', args: [epoch]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('onAck failed:', m); }
            });
        },
        jsLog: function(level, message) {
            window.cefQuery({
                request: JSON.stringify({method: 'jsLog', args: [level, message]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) {}
            });
        },
        onBridgeReady: function() {
            window.cefQuery({
                request: JSON.stringify({method: 'onBridgeReady', args: []}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('onBridgeReady failed:', m); }
            });
        },
        searchStateChanged: function(total, current) {
            window.cefQuery({
                request: JSON.stringify({method: 'searchStateChanged', args: [total, current]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('searchStateChanged failed:', m); }
            });
        },
        onSymbolClick: function(symbolName, symbolExists, fqn, x, y) {
            window.cefQuery({
                request: JSON.stringify({method: 'onSymbolClick', args: [symbolName, symbolExists, fqn, x, y]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('onSymbolClick failed:', m); }
            });
        },
        onFilePathClick: function(filePath, exists, matchesJson, x, y) {
            window.cefQuery({
                request: JSON.stringify({method: 'onFilePathClick', args: [filePath, exists, matchesJson, x, y]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('onFilePathClick failed:', m); }
            });
        },
        captureText: function(text) {
            window.cefQuery({
                request: JSON.stringify({method: 'captureText', args: [text]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('captureText failed:', m); }
            });
        },
        deleteHistoryTask: function(sequence) {
            window.cefQuery({
                request: JSON.stringify({method: 'deleteHistoryTask', args: [sequence]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('deleteHistoryTask failed:', m); }
            });
        },
        lookupSymbolsAsync: function(symbolNamesJson, seq, contextId) {
            window.cefQuery({
                request: JSON.stringify({method: 'lookupSymbolsAsync', args: [symbolNamesJson, seq, contextId]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('lookupSymbolsAsync failed:', m); }
            });
        },
        lookupFilePathsAsync: function(filePathsJson, seq, contextId) {
            window.cefQuery({
                request: JSON.stringify({method: 'lookupFilePathsAsync', args: [filePathsJson, seq, contextId]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('lookupFilePathsAsync failed:', m); }
            });
        },
        openExternalLink: function(url) {
            window.cefQuery({
                request: JSON.stringify({method: 'openExternalLink', args: [url]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) {}
            });
        },
        onZoomChanged: function(zoom) {
            window.cefQuery({
                request: JSON.stringify({method: 'onZoomChanged', args: [zoom]}),
                onSuccess: function(r) {},
                onFailure: function(e, m) { console.error('onZoomChanged failed:', m); }
            });
        }
    };

    console.log('JCEF javaBridge injected successfully');

    // Signal to Java that the bridge is ready
    window.javaBridge.onBridgeReady();
})();
