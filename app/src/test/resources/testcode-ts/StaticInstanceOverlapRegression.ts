// Regression test for static vs instance member overlap
// This pattern caused false "duplicate CU" warnings before the fix

export class Color {
    // Instance method
    transparent(factor: number): Color {
        return this;
    }

    // Static field with same name - should NOT cause duplicate warning
    static readonly transparent = null;
}

export namespace Color {
    export namespace Format {
        // Nested namespace - declaration merging pattern
    }
}
