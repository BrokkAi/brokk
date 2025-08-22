/**
 * Centralized logging utility that routes debug logs to Java console via jsLog
 */

declare global {
  interface Window {
    javaBridge?: {
      jsLog?: (level: string, message: string) => void;
      debugLog?: (message: string) => void;
    };
  }
}

type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG';

/**
 * Routes log messages to Java console via javaBridge.jsLog
 * Falls back to browser console if javaBridge is not available
 */
function routeToJava(level: LogLevel, message: string): void {
  try {
    const javaBridge = window.javaBridge;
    if (javaBridge && typeof javaBridge.jsLog === 'function') {
      javaBridge.jsLog(level, message);
      return;
    }
  } catch (e) {
    // If jsLog fails, fall through to console
  }

  // Fallback to browser console
  switch (level) {
    case 'ERROR':
      console.error(message);
      break;
    case 'WARN':
      console.warn(message);
      break;
    case 'INFO':
      console.info(message);
      break;
    case 'DEBUG':
    default:
      console.log(message);
      break;
  }
}

/**
 * Format multiple arguments into a single message string
 */
function formatMessage(...args: any[]): string {
  return args.map(arg => {
    if (typeof arg === 'string') {
      return arg;
    }
    if (arg instanceof Error) {
      return `${arg.message}\n${arg.stack || ''}`;
    }
    return JSON.stringify(arg);
  }).join(' ');
}

/**
 * Routes debug messages directly to Java stdout via javaBridge.debugLog
 * Falls back to browser console if javaBridge is not available
 */
function routeDebugToJava(message: string): void {
  try {
    const javaBridge = window.javaBridge;
    if (javaBridge && typeof javaBridge.debugLog === 'function') {
      javaBridge.debugLog(message);
      return;
    }
  } catch (e) {
    // If debugLog fails, fall through to console
  }

  // Fallback to browser console
  console.log(`[DEBUG] ${message}`);
}

/**
 * Centralized logging functions that route to Java console
 */
export const log = {
  error: (...args: any[]) => routeToJava('ERROR', formatMessage(...args)),
  warn: (...args: any[]) => routeToJava('WARN', formatMessage(...args)),
  info: (...args: any[]) => routeToJava('INFO', formatMessage(...args)),
  debug: (...args: any[]) => routeToJava('DEBUG', formatMessage(...args)),

  // Direct stdout debug logging via javaBridge.debugLog
  debugLog: (...args: any[]) => routeDebugToJava(formatMessage(...args)),

  // Convenience method for tagged logging
  tagged: (tag: string, level: LogLevel = 'INFO') => ({
    error: (...args: any[]) => routeToJava('ERROR', `[${tag}] ${formatMessage(...args)}`),
    warn: (...args: any[]) => routeToJava('WARN', `[${tag}] ${formatMessage(...args)}`),
    info: (...args: any[]) => routeToJava('INFO', `[${tag}] ${formatMessage(...args)}`),
    debug: (...args: any[]) => routeToJava('DEBUG', `[${tag}] ${formatMessage(...args)}`),
    debugLog: (...args: any[]) => routeDebugToJava(`[${tag}] ${formatMessage(...args)}`),
  })
};

/**
 * Create a logger with a specific tag prefix
 */
export function createLogger(tag: string) {
  return log.tagged(tag);
}

/**
 * Private worker logging function that sends messages to the main thread
 */
function workerLog(level: Lowercase<LogLevel>, message: string): void {
  if (typeof self !== 'undefined' && self.postMessage) {
    self.postMessage({ type: 'worker-log', level, message });
  } else {
    // Fallback for non-worker contexts
    routeToJava(level.toUpperCase() as LogLevel, message);
  }
}

/**
 * Worker logger that matches the regular logger API but sends messages to main thread
 */
export const workerLogger = {
  error: (...args: any[]) => workerLog('error', formatMessage(...args)),
  warn: (...args: any[]) => workerLog('warn', formatMessage(...args)),
  info: (...args: any[]) => workerLog('info', formatMessage(...args)),
  debug: (...args: any[]) => workerLog('debug', formatMessage(...args)),

  // Convenience method for tagged worker logging
  tagged: (tag: string) => ({
    error: (...args: any[]) => workerLog('error', `[${tag}] ${formatMessage(...args)}`),
    warn: (...args: any[]) => workerLog('warn', `[${tag}] ${formatMessage(...args)}`),
    info: (...args: any[]) => workerLog('info', `[${tag}] ${formatMessage(...args)}`),
    debug: (...args: any[]) => workerLog('debug', `[${tag}] ${formatMessage(...args)}`),
  })
};

/**
 * Create a worker logger with a specific tag prefix
 */
export function createWorkerLogger(tag: string) {
  return workerLogger.tagged(tag);
}