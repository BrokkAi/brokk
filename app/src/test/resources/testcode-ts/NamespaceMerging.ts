// Test file for TypeScript namespace merging with classes and enums

// Class + namespace merging
class Color {
    constructor(public rgb: number) {}
    
    toHex(): string {
        return '#' + this.rgb.toString(16).padStart(6, '0');
    }
    
    static blend(a: Color, b: Color): Color {
        return new Color((a.rgb + b.rgb) >> 1);
    }
}

namespace Color {
    export const white = new Color(0xFFFFFF);
    export const black = new Color(0x000000);
    export const red = new Color(0xFF0000);
    
    export function fromHex(s: string): Color {
        const hex = s.startsWith('#') ? s.slice(1) : s;
        return new Color(parseInt(hex, 16));
    }
    
    export function random(): Color {
        return new Color(Math.floor(Math.random() * 0xFFFFFF));
    }
}

// Enum + namespace merging
enum Direction {
    North = "N",
    South = "S",
    East = "E",
    West = "W"
}

namespace Direction {
    export function opposite(dir: Direction): Direction {
        switch (dir) {
            case Direction.North: return Direction.South;
            case Direction.South: return Direction.North;
            case Direction.East: return Direction.West;
            case Direction.West: return Direction.East;
        }
    }
    
    export function isVertical(dir: Direction): boolean {
        return dir === Direction.North || dir === Direction.South;
    }
    
    export const all: Direction[] = [
        Direction.North,
        Direction.South,
        Direction.East,
        Direction.West
    ];
}

// Exported class + namespace merging
export class Point {
    constructor(public x: number, public y: number) {}
    
    distanceTo(other: Point): number {
        const dx = this.x - other.x;
        const dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}

export namespace Point {
    export const origin = new Point(0, 0);
    
    export function fromPolar(r: number, theta: number): Point {
        return new Point(r * Math.cos(theta), r * Math.sin(theta));
    }
    
    export interface Config {
        precision?: number;
        rounded?: boolean;
    }
}

// Exported enum + namespace merging
export enum HttpStatus {
    OK = 200,
    Created = 201,
    BadRequest = 400,
    NotFound = 404,
    ServerError = 500
}

export namespace HttpStatus {
    export function isSuccess(status: HttpStatus): boolean {
        return status >= 200 && status < 300;
    }
    
    export function isError(status: HttpStatus): boolean {
        return status >= 400;
    }
    
    export const messages: Record<HttpStatus, string> = {
        [HttpStatus.OK]: "OK",
        [HttpStatus.Created]: "Created",
        [HttpStatus.BadRequest]: "Bad Request",
        [HttpStatus.NotFound]: "Not Found",
        [HttpStatus.ServerError]: "Internal Server Error"
    };
}
