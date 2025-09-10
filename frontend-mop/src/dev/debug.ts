/**
 * Debug configuration for development features
 */

export interface DebugConfig {
  showCacheStats: boolean;
  showTooltips: boolean;
  logSymbolLookups: boolean;
  showWrapStatus: boolean;
}

// Debug configuration - set to true to enable debug features
export const DEBUG_CONFIG: DebugConfig = {
  showCacheStats: false, // Show cache statistics box
  showTooltips: false,   // Show symbol info tooltips on hover
  logSymbolLookups: false, // Log symbol lookup details to console
  showWrapStatus: false   // Show word wrap status display at top of page
};

/**
 * Check if a specific debug feature is enabled
 */
export function isDebugEnabled(feature: keyof DebugConfig): boolean {
  return DEBUG_CONFIG[feature] === true;
}
