/**
 * TypeScript Annotations Test File
 * Demonstrates various annotation types and comment patterns
 * for testing comment expansion functionality.
 */

// Global type definition with JSDoc
/**
 * User configuration interface
 * @since v1.0.0
 * @category Configuration
 */
interface UserConfig {
    /** API endpoint URL */
    apiUrl: string;
    /** Optional timeout in milliseconds */
    timeout?: number;
}

// Enum with documentation
/**
 * User role enumeration
 * @enum {string}
 */
enum UserRole {
    /** Standard user access */
    USER = "user",
    /** Administrative access */
    ADMIN = "admin",
    /** Guest access only */
    GUEST = "guest"
}

// Class with comprehensive annotations
/**
 * Service class for user management
 * @class UserService
 * @implements {ServiceInterface}
 */
class UserService {
    private apiUrl: string;

    /**
     * Creates a new UserService instance
     * @constructor
     * @param {UserConfig} config - Configuration object
     * @throws {Error} When configuration is invalid
     */
    constructor(config: UserConfig) {
        this.apiUrl = config.apiUrl;
    }

    // Method with deprecation annotation
    /**
     * Gets user by ID (deprecated method)
     * @deprecated Use getUserById instead
     * @param {string} id - User identifier
     * @returns {Promise<User | null>} User object or null
     */
    async getUser(id: string): Promise<User | null> {
        return this.getUserById(id);
    }

    // Method with comprehensive JSDoc
    /**
     * Retrieves user by ID with full error handling
     * @param {string} id - Unique user identifier
     * @param {boolean} [includeMetadata=false] - Include user metadata
     * @returns {Promise<User>} Promise resolving to user object
     * @throws {NotFoundError} When user doesn't exist
     * @throws {ValidationError} When ID format is invalid
     * @example
     * ```typescript
     * const user = await service.getUserById('123', true);
     * console.log(user.name);
     * ```
     * @since v2.0.0
     * @async
     */
    async getUserById(id: string, includeMetadata: boolean = false): Promise<User> {
        if (!id || typeof id !== 'string') {
            throw new Error('Invalid ID format');
        }

        const response = await fetch(`${this.apiUrl}/users/${id}`);
        if (!response.ok) {
            throw new Error(`User not found: ${id}`);
        }

        return response.json();
    }

    // Static method with annotations
    /**
     * Validates user configuration
     * @static
     * @param {UserConfig} config - Configuration to validate
     * @returns {boolean} True if valid, false otherwise
     * @memberof UserService
     */
    static validateConfig(config: UserConfig): boolean {
        return !!(config.apiUrl && config.apiUrl.startsWith('http'));
    }
}

// Generic class with type constraints
/**
 * Generic repository pattern implementation
 * @template T - Entity type
 * @template K - Key type
 * @class Repository
 */
class Repository<T extends { id: K }, K = string> {
    private items: Map<K, T> = new Map();

    /**
     * Adds an item to the repository
     * @param {T} item - Item to add
     * @returns {void}
     * @throws {DuplicateError} When item already exists
     */
    add(item: T): void {
        if (this.items.has(item.id)) {
            throw new Error(`Item with ID ${item.id} already exists`);
        }
        this.items.set(item.id, item);
    }

    // Method with experimental annotation
    /**
     * Experimental batch operation
     * @experimental This method is under development
     * @param {T[]} items - Items to process
     * @returns {Promise<void>}
     * @beta
     */
    async batchProcess(items: T[]): Promise<void> {
        for (const item of items) {
            this.add(item);
        }
    }
}

// Function overloads with individual documentation
/**
 * String processing overload
 * @overload
 */
function processData(input: string): string[];

/**
 * Number processing overload
 * @overload
 */
function processData(input: number): number[];

/**
 * Boolean processing overload
 * @overload
 */
function processData(input: boolean): boolean[];

// Implementation with comprehensive documentation
/**
 * Processes input data based on type
 * @param {string | number | boolean} input - Data to process
 * @returns {string[] | number[] | boolean[]} Processed data array
 * @throws {TypeError} When input type is unsupported
 * @example
 * ```typescript
 * const stringResult = processData("hello");  // string[]
 * const numberResult = processData(42);       // number[]
 * const boolResult = processData(true);       // boolean[]
 * ```
 */
function processData(input: string | number | boolean): string[] | number[] | boolean[] {
    if (typeof input === 'string') {
        return [input.toUpperCase(), input.toLowerCase()];
    }
    if (typeof input === 'number') {
        return [input, input * 2, input * 3];
    }
    if (typeof input === 'boolean') {
        return [input, !input];
    }
    throw new TypeError(`Unsupported input type: ${typeof input}`);
}

// Async function with complex annotations
/**
 * Fetches data from API with retry logic
 * @async
 * @param {string} endpoint - API endpoint to fetch
 * @param {number} [maxRetries=3] - Maximum number of retries
 * @param {number} [delay=1000] - Delay between retries in ms
 * @returns {Promise<ApiResponse>} API response data
 * @throws {NetworkError} When network request fails
 * @throws {TimeoutError} When request times out
 * @see {@link processData} for data processing
 * @todo Add request caching
 * @version 1.2.0
 */
async function fetchWithRetry(
    endpoint: string,
    maxRetries: number = 3,
    delay: number = 1000
): Promise<ApiResponse> {
    let lastError: Error;

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            const response = await fetch(endpoint);
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
            return await response.json();
        } catch (error) {
            lastError = error as Error;
            if (attempt < maxRetries) {
                await new Promise(resolve => setTimeout(resolve, delay));
            }
        }
    }

    throw new Error(`Failed after ${maxRetries} retries: ${lastError.message}`);
}

// Type definitions
/**
 * User entity interface
 * @interface User
 */
interface User {
    /** Unique user identifier */
    id: string;
    /** User display name */
    name: string;
    /** User email address */
    email: string;
    /** User role */
    role: UserRole;
}

/**
 * API response wrapper
 * @interface ApiResponse
 * @template T - Response data type
 */
interface ApiResponse<T = any> {
    /** Response data */
    data: T;
    /** Success status */
    success: boolean;
    /** Optional error message */
    message?: string;
}

// Export statements with comments
/** Export user service for external use */
export { UserService };

/** Export repository class */
export { Repository };

/** Export utility functions */
export { processData, fetchWithRetry };

/** Export type definitions */
export type { User, UserConfig, ApiResponse };