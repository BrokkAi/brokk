// Template Literal Types and Utility Types Test File

// ===== Basic Template Literal Types =====

// Simple template literal type
export type EventName<T extends string> = `${T}Changed`;

// Template with Capitalize
export type PropEventHandler<T extends string> = `on${Capitalize<T>}`;

// Template with Uppercase
export type ActionType<T extends string> = `ACTION_${Uppercase<T>}`;

// Template with Lowercase
export type MethodName<T extends string> = `get${Capitalize<T>}`;

// Template with Uncapitalize
export type PrivateProp<T extends string> = `_${Uncapitalize<T>}`;

// ===== Nested Template Literal Types =====

// Multiple transformations
export type ComplexEvent<T extends string> = `on${Capitalize<T>}Changed`;

// Chained template literals
export type NestedTemplate<T extends string, U extends string> = `${T}_${U}_handler`;

// Template with union
export type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";
export type ApiEndpoint<T extends string> = `api/${Lowercase<T>}`;

// ===== Test Types for Utility Combinations =====

// Base types for testing utilities
interface User {
    id: number;
    name: string;
    email: string;
    age: number;
    optional?: string;
}

interface Config {
    host: string;
    port: number;
    optional?: boolean;
    debug?: boolean;
}

// ===== Basic Utility Types =====

// Partial
export type PartialUser = Partial<User>;

// Required
export type RequiredConfig = Required<Config>;

// Readonly
export type ReadonlyUser = Readonly<User>;

// Pick
export type UserNameEmail = Pick<User, 'name' | 'email'>;

// Omit
export type UserWithoutId = Omit<User, 'id'>;

// Record
export type StringRecord = Record<string, string>;
export type NumberDict = Record<string, number>;

// ===== Complex Utility Type Combinations =====

// Partial + Pick
export type PartialUserNameEmail = Partial<Pick<User, 'name' | 'email'>>;

// Required + Omit
export type RequiredConfigWithoutOptional = Required<Omit<Config, 'optional'>>;

// Readonly + Partial
export type ReadonlyPartialUser = Readonly<Partial<User>>;

// Pick + Required
export type RequiredUserNameEmail = Required<Pick<User, 'name' | 'email'>>;

// Omit + Partial
export type PartialUserWithoutId = Partial<Omit<User, 'id'>>;

// ===== Triple Utility Type Combinations =====

// Readonly + Partial + Pick
export type ReadonlyPartialUserNameEmail = Readonly<Partial<Pick<User, 'name' | 'email'>>>;

// Required + Omit + Readonly
export type RequiredReadonlyConfigWithoutOptional = Required<Readonly<Omit<Config, 'optional'>>>;

// Partial + Omit + Pick (using intermediate type)
export type PartialUserWithoutAge = Partial<Omit<User, 'age'>>;
export type PartialUserNameOnly = Partial<Pick<User, 'name'>>;

// ===== Advanced Utility Type Patterns =====

// Record with complex value type
export type UserRecord = Record<string, User>;
export type PartialUserRecord = Record<string, Partial<User>>;

// Nested utility types
export type NestedUtility = Partial<Record<string, Pick<User, 'name' | 'email'>>>;

// Utility type with union
export type MixedRecord = Record<string, string | number | boolean>;

// Multiple Picks combined
export type UserBasicInfo = Pick<User, 'name'> & Pick<User, 'email'>;

// Utility with conditional
export type ConditionalUtility<T> = T extends User ? Partial<T> : Required<T>;

// ===== Template Literals with Utility Types =====

// Template literal combined with Pick
export type EventHandler<T extends string> = {
    [K in `on${Capitalize<T>}`]: () => void;
};

// Template literal with Record
export type EventMap<T extends string> = Record<`${T}Event`, () => void>;

// Complex template with utility
export type ApiHandlers = {
    [K in `handle${Capitalize<HttpMethod>}`]: (url: string) => Promise<void>;
};

// ===== Mapped Types with Template Literals =====

// Getters using template literals
export type Getters<T> = {
    [P in keyof T as `get${Capitalize<string & P>}`]: () => T[P];
};

// Setters using template literals
export type Setters<T> = {
    [P in keyof T as `set${Capitalize<string & P>}`]: (value: T[P]) => void;
};

// Event emitters
export type EventEmitter<T> = {
    [P in keyof T as `on${Capitalize<string & P>}Change`]: (callback: (value: T[P]) => void) => void;
};

// ===== Extreme Utility Type Combinations =====

// Four levels deep
export type DeepUtility = Readonly<Required<Partial<Pick<User, 'name' | 'email'>>>>;

// Mixed utilities with Record
export type ComplexMixedUtility = Partial<Record<string, Required<Pick<User, 'name'>>>>;

// Utility with multiple type parameters
export type MergedUtility<T, U> = Partial<T> & Required<U>;
export type UserConfigMerge = MergedUtility<User, Config>;

// Conditional utility combination
export type ConditionalMerge<T> = T extends object ? Partial<Readonly<T>> : never;

// ===== Real-world Use Cases =====

// API response type
export type ApiResponse<T> = {
    data: T;
    status: number;
    error?: string;
};

// Paginated API response
export type PaginatedResponse<T> = ApiResponse<T[]> & {
    page: number;
    totalPages: number;
};

// Filtered user type for API
export type PublicUser = Omit<User, 'id'>;
export type UserUpdatePayload = Partial<Omit<User, 'id'>>;

// Config with environment override
export type EnvironmentConfig = Required<Config> & {
    env: 'development' | 'production' | 'test';
};

export type PartialEnvironmentConfig = Partial<EnvironmentConfig>;

// Additional complex utility patterns
export type DeepPartial<T> = {
    [P in keyof T]?: T[P] extends object ? DeepPartial<T[P]> : T[P];
};

export type RequiredKeys<T> = {
    [K in keyof T]-?: {} extends Pick<T, K> ? never : K;
}[keyof T];
