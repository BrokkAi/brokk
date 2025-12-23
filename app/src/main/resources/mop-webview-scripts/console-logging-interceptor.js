// Intercept console methods and forward to Java bridge with stack traces
(function() {
    var originalLog = console.log;
    var originalError = console.error;
    var originalWarn = console.warn;
    var originalInfo = console.info;

    function toStringWithStack(arg) {
        return (arg && typeof arg === 'object' && 'stack' in arg) ? arg.stack : String(arg);
    }

    console.log = function() {
        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
        if (window.javaBridge) window.javaBridge.jsLog('INFO', msg);
        originalLog.apply(console, arguments);
    };
    
    console.error = function() {
        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
        if (window.javaBridge) window.javaBridge.jsLog('ERROR', msg);
        originalError.apply(console, arguments);
    };
    
    console.warn = function() {
        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
        if (window.javaBridge) window.javaBridge.jsLog('WARN', msg);
        originalWarn.apply(console, arguments);
    };
    
    console.info = function() {
        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
        if (window.javaBridge) window.javaBridge.jsLog('INFO', msg);
        originalInfo.apply(console, arguments);
    };

    // console.debug = function() {
    //     var msg = Array.from(arguments).map(toStringWithStack).join(' ');
    //     if (window.javaBridge) window.javaBridge.jsLog('DEBUG', msg);
    //     originalInfo.apply(console, arguments);
    // };
})();
