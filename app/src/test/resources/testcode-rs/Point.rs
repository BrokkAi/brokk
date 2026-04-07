pub struct Point {
    pub x: i32,
    pub y: i32,
}

impl Point {
    pub fn new(x: i32, y: i32) -> Self {
        Self { x, y }
    }

    pub fn translate(&mut self, dx: i32, dy: i32) {
        self.x += dx;
        self.y += dy;
    }
}

pub trait Drawable {
    fn draw(&self);
}

impl Drawable for Point {
    fn draw(&self) {
        // Simulate drawing
        println!("Drawing point at ({}, {})", self.x, self.y);
    }
}

pub const ORIGIN: Point = Point { x: 0, y: 0 };

pub fn distance(p: &Point, q: &Point) -> f64 {
    let dx = (p.x - q.x) as f64;
    let dy = (p.y - q.y) as f64;
    (dx * dx + dy * dy).sqrt()
}

// To test package name with subdirectories
// Create a file like src/test/resources/testcode-rs/sub/mod.rs if needed
// For now, determinePackageName will return "" for Point.rs directly under testcode-rs/
// or "sub" if Point.rs was in testcode-rs/sub/Point.rs and project root is testcode-rs.

pub enum Color {
    Red,
    Green,
    Blue,
    Rgb(u8, u8, u8),
    Named { name: String },
}

pub trait Shape {
    const ID: u32; // Associated constant in trait
    fn area(&self) -> f64;
}

impl Shape for Point {
    const ID: u32 = 1; // Associated constant in impl

    fn area(&self) -> f64 {
        0.0 // Points have no area
    }
}

pub trait DefaultPosition {
    const DEFAULT_X: i32 = 0; // Associated const in trait with default
    const DEFAULT_Y: i32 = 0;
    fn default_pos() -> (i32, i32) {
        (Self::DEFAULT_X, Self::DEFAULT_Y)
    }
}

// Implementing DefaultPosition for Point to test associated constants from traits
impl DefaultPosition for Point {}

pub struct Circle {
    pub center: Point,
    pub radius: f64,
}

impl Shape for Circle {
    const ID: u32 = 2; // Associated constant in another impl
    fn area(&self) -> f64 {
        std::f64::consts::PI * self.radius * self.radius
    }
}
