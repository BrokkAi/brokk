/**
 * Central configuration for the SlopScan Node.js server.
 * Handles environment variable parsing for technical debt and financial risk equations.
 */
export const config = {
  // Server port
  port: parseInt(process.env.PORT || '3001', 10),

  // SlopScan Equation Parameters
  // V: Velocity Ratio (Default 1.0)
  SLOP_V_RATIO: parseFloat(process.env.SLOP_V_RATIO || '1.0'),

  // I: Innovation Drift (Default 0.1)
  SLOP_I_DRIFT: parseFloat(process.env.SLOP_I_DRIFT || '0.1'),

  // E: Error/Ignore Threshold (Default 50)
  SLOP_E_IGNORE: parseInt(process.env.SLOP_E_IGNORE || '50', 10),

  // C: Cost per Developer Day in USD (Default 1200)
  SLOP_C_DAY: parseInt(process.env.SLOP_C_DAY || '1200', 10),

  // M: Maintenance Multiplier (Default 1.5)
  SLOP_M_MULTIPLIER: parseFloat(process.env.SLOP_M_MULTIPLIER || '1.5'),

  // N: Team Size (Default 6)
  SLOP_N_TEAM: parseInt(process.env.SLOP_N_TEAM || '6', 10),

  // Brokk API Key for headless executor authentication
  BROKK_API_KEY: process.env.BROKK_API_KEY,

  // LLM Model used for planning analysis tasks
  BROKK_PLANNER_MODEL: process.env.BROKK_PLANNER_MODEL || 'gpt-4o',
};
