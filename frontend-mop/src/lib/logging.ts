/**
 * Centralized logging utility that routes debug logs to Java console via jsLog
 */

declare global {
  interface Window {
    javaBridge?: {
      jsLog?: (level: string, message: string) => void;
    };
  }
}

type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG';

/**
 * Routes log messages to Java console via javaBridge.jsLog
 * Falls back to browser console if javaBridge is not available
 */
function routeToJava(level: LogLevel, message: string): void {
  // use plain browser console in browser and interception in JavaFX (MOPWebViewHost.initializeFxPanel)
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
      console.debug(message);
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
 * Centralized logging functions that route to Java console
 */
export const log = {
  error: (...args: any[]) => routeToJava('ERROR', formatMessage(...args)),
  warn: (...args: any[]) => routeToJava('WARN', formatMessage(...args)),
  info: (...args: any[]) => routeToJava('INFO', formatMessage(...args)),
  debug: (...args: any[]) => routeToJava('DEBUG', formatMessage(...args)),


  // Convenience method for tagged logging
  tagged: (tag: string, level: LogLevel = 'INFO') => ({
    error: (...args: any[]) => routeToJava('ERROR', `[${tag}] ${formatMessage(...args)}`),
    warn: (...args: any[]) => routeToJava('WARN', `[${tag}] ${formatMessage(...args)}`),
    info: (...args: any[]) => routeToJava('INFO', `[${tag}] ${formatMessage(...args)}`),
    debug: (...args: any[]) => routeToJava('DEBUG', `[${tag}] ${formatMessage(...args)}`),
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