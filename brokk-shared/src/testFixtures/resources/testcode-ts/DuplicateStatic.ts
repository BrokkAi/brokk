// Test for duplicate static field warning
export class Color {
    transparent(factor: number): Color {
        return this;
    }

    static readonly transparent = 0;
}
