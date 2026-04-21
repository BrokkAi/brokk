// Test file for static vs instance member name overlap
// This tests that the analyzer correctly distinguishes between
// static members and instance members with the same name

export class Color {
    // Instance method: adjusts transparency of a color instance
    transparent(factor: number): Color {
        const { r, g, b, a } = this.rgba;
        return new Color(new RGBA(r, g, b, a * factor));
    }

    // Static property: predefined constant for transparent color
    static readonly transparent = new Color(new RGBA(0, 0, 0, 0));

    // Another example: instance method vs static method
    normalize(): void {
        // normalize this instance
    }

    static normalize(value: number): number {
        // static normalization function
        return value / 100;
    }

    // Instance property vs static property
    count: number = 0;
    static count: number = 0;

    constructor(private rgba: RGBA) {}
}

class RGBA {
    constructor(
        public r: number,
        public g: number,
        public b: number,
        public a: number
    ) {}
}
