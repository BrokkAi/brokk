// Intercept console methods and forward to Java bridge without stdout output
// This is JCEF-specific: we suppress the original console output to avoid JCEF forwarding to stdout
(function() {
    function toStringWithStack(arg) {
        return (arg && typeof arg === 'object' && 'stack' in arg) ? arg.stack : String(arg);
    }

    console.log = function() {
        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
        if (window.javaBridge && window.javaBridge.jsLog) {
            window.javaBridge.jsLog('INFO', msg);
        }
        // Don't call original - prevents JCEF stdout forwarding
    };

    console.error = function() {
        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
        if (window.javaBridge && window.javaBridge.jsLog) {
            window.javaBridge.jsLog('ERROR', msg);
        }
    };

    console.warn = function() {
        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
        if (window.javaBridge && window.javaBridge.jsLog) {
            window.javaBridge.jsLog('WARN', msg);
        }
    };

    console.info = function() {
        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
        if (window.javaBridge && window.javaBridge.jsLog) {
            window.javaBridge.jsLog('INFO', msg);
        }
    };

    console.debug = function() {
        var msg = Array.from(arguments).map(toStringWithStack).join(' ');
        if (window.javaBridge && window.javaBridge.jsLog) {
            window.javaBridge.jsLog('DEBUG', msg);
        }
    };
})();
