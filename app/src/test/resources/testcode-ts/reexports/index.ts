// Barrel file with various re-export patterns

// Re-export everything
export * from './users';

// Re-export specific symbols
export { Post, Comment } from './posts';

// Re-export with renaming
export { User as PublicUser } from './internal/user';

// Re-export default as named
export { default as AuthService } from './services/auth';

// Re-export as namespace
export * as Types from './types';

// Type-only re-exports
export type { UserRole, Permission } from './auth';
