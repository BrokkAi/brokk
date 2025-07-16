# Code Skeleton Design Guidelines

When creating a skeleton, I want to include the **structural essence** of the code - enough to understand the shape, interface, and organization without implementation details. Here's what should be included:

## 🏗️ **Core Structure (Always Include)**

### **Declarations with Signatures**
```typescript
// Full signatures, no implementation
export class UserService {
  constructor(private db: DatabaseConnection, private auth: AuthService);
  async createUser(userData: CreateUserRequest): Promise<User>;
  async getUserById(id: string): Promise<User | null>;
  private validateUser(data: UserData): boolean;
}
```

### **Type Information**
```typescript
// Complete type definitions
interface User {
  id: string;
  name: string;
  email: string;
  createdAt: Date;
}

type UserRole = 'admin' | 'user' | 'guest';
```

### **Visibility and Modifiers**
```typescript
// All access modifiers, static, abstract, readonly, etc.
export abstract class BaseService {
  protected readonly config: ServiceConfig;
  public static readonly VERSION = "1.0";
  abstract process(data: any): Promise<void>;
}
```

## 🔗 **Relationships and Dependencies**

### **Inheritance and Implementation**
```typescript
// Show class hierarchies
export class UserService extends BaseService implements IUserService {
  // ... methods
}
```

### **Imports/Exports**
```typescript
// All import statements
import { DatabaseConnection } from './database';
import type { User, CreateUserRequest } from './types';

// Export statements
export { UserService };
export type { User };
```

## 📋 **Interface Contracts**

### **Method Signatures**
```typescript
// Parameters, return types, generics, async
async findUsers<T extends UserFilter>(
  filter: T, 
  options?: QueryOptions
): Promise<User[]>;
```

### **Property Declarations**
```typescript
// Field types and initializers (but not complex logic)
class UserService {
  private readonly users: Map<string, User> = new Map();
  public maxUsers: number = 1000;
  protected config?: ServiceConfig;
}
```

## 🎯 **Key Markers (Include Selectively)**

### **Decorators and Annotations**
```typescript
// Important metadata
@Injectable()
@Controller('/users')
export class UserController {
  @Post('/create')
  @ValidateBody(CreateUserSchema)
  async createUser(@Body() userData: CreateUserRequest): Promise<User>;
}
```

### **Generics and Constraints**
```typescript
// Type parameters and bounds
export class Repository<T extends Entity, K extends keyof T> {
  findBy<P extends K>(property: P, value: T[P]): Promise<T[]>;
}
```

## ❌ **What to Exclude**

### **Implementation Details**
```typescript
// ❌ Don't include
async createUser(userData: CreateUserRequest): Promise<User> {
  const validation = await this.validator.validate(userData);
  if (!validation.isValid) {
    throw new ValidationError(validation.errors);
  }
  // ... 50 lines of business logic
  return user;
}

// ✅ Include instead
async createUser(userData: CreateUserRequest): Promise<User>;
```

### **Complex Logic**
```typescript
// ❌ Don't include complex expressions
const config = {
  timeout: process.env.NODE_ENV === 'production' 
    ? parseInt(process.env.TIMEOUT || '5000') 
    : 1000,
  retries: Math.max(1, parseInt(process.env.RETRIES || '3'))
};

// ✅ Include simple declarations
const config: ServiceConfig;
```

## 🎨 **Formatting Guidelines**

### **Consistent Indentation**
```typescript
export class UserService {
  constructor(private db: DatabaseConnection);
  
  async createUser(data: CreateUserRequest): Promise<User>;
  async deleteUser(id: string): Promise<void>;
  
  private validateInput(data: any): boolean;
}
```

### **Preserve Structure**
```typescript
// Keep nesting and organization
namespace UserManagement {
  export class UserService {
    // ... methods
  }
  
  export interface UserConfig {
    // ... properties
  }
}
```

## 🎯 **Purpose of Skeletons**

The skeleton should enable someone to:

1. **Understand the API** - What methods/properties are available
2. **See relationships** - How classes connect and depend on each other
3. **Navigate quickly** - Jump to specific methods/classes
4. **Generate code** - Have enough context to implement or call methods
5. **Detect changes** - Compare versions to see API evolution

## 📝 **Perfect Skeleton Example**

```typescript
// UserService.ts
import { DatabaseConnection } from './database';
import { AuthService } from './auth';
import type { User, CreateUserRequest, UserUpdate } from './types';

export class UserService {
  constructor(
    private readonly db: DatabaseConnection,
    private readonly auth: AuthService
  );

  // Public API
  async createUser(userData: CreateUserRequest): Promise<User>;
  async getUserById(id: string): Promise<User | null>;
  async updateUser(id: string, updates: UserUpdate): Promise<User>;
  async deleteUser(id: string): Promise<void>;

  // Internal methods
  private validateUserData(data: CreateUserRequest): boolean;
  private async hashPassword(password: string): Promise<string>;
}

export type { User, CreateUserRequest, UserUpdate };
```

This skeleton tells you everything you need to know about the `UserService` without any implementation noise - its dependencies, public API, parameter types, return types, and internal structure.

## 🔍 **Implementation in Brokk Analyzers**

In the context of Brokk's Tree-sitter analyzers, skeletons should:

### **Preserve Language-Specific Features**
- **TypeScript**: Type annotations, generics, decorators, namespace structure
- **Java**: Access modifiers, annotations, generics, package structure  
- **Python**: Type hints, decorators, class inheritance
- **Go**: Receiver types, interface implementations, package exports
- **PHP**: Visibility modifiers, traits, namespace declarations
- **C#**: Access modifiers, attributes, generics, namespace structure

### **Maintain Hierarchical Structure**
```typescript
// Show nested relationships clearly
namespace MyModule {
  export class InnerClass {
    private field: string;
    public method(): void;
  }
  
  export interface InnerInterface {
    property: number;
    method(): string;
  }
}
```

### **Include Essential Metadata**
- Export/import statements for understanding dependencies
- Access modifiers for understanding API boundaries
- Type information for understanding contracts
- Inheritance relationships for understanding hierarchies

The goal is to create a **navigable map** of the codebase that preserves all the structural information needed for understanding and development, while eliminating the implementation noise that makes code hard to scan and understand quickly.