export class Greeter {
    greeting: string;
    constructor(message: string) {
        this.greeting = message;
    }
    greet(): string {
        return "Hello, " + this.greeting;
    }
}

export function globalFunc(num: number): number {
    return num * 2;
}

export const PI: number = 3.14159;

export interface Point {
    x: number;
    y: number;
    label?: string;
    readonly originDistance?: number;
    move(dx: number, dy: number): void;
}

export enum Color {
    Red,
    Green = 3,
    Blue
}

// This is a type alias
export type StringOrNumber = string | number;

// Non-exported type alias
type LocalDetails = { id: number, name: string };
