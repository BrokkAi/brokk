/**
 * Debug configuration for development features
 */

export interface DebugConfig {
  showCacheStats: boolean;
  showTooltips: boolean;
  logSymbolLookups: boolean;
}

// Debug configuration - set to true to enable debug features
export const DEBUG_CONFIG: DebugConfig = {
  showCacheStats: true, // Show cache statistics box
  showTooltips: true,   // Show symbol info tooltips on hover
  logSymbolLookups: true // Log symbol lookup details to console
};

/**
 * Check if a specific debug feature is enabled
 */
export function isDebugEnabled(feature: keyof DebugConfig): boolean {
  return DEBUG_CONFIG[feature] === true;
}