#include "geometry.h"
// For M_PI, or use a const double
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif


// Note: No <iostream> to keep CPG simple for tests if not needed for structure.
// Point::print can be empty or use a placeholder.

int global_var; // Define global_var

namespace shapes {
  Circle::Circle(double r) : radius(r) {}
  double Circle::getArea() { return M_PI * radius * radius; }
  int Circle::getObjectType() { return 1; } // 1 for Circle
} // namespace shapes

void Point::print() {
  // Placeholder: real implementation might use iostream
  // int val = x + y; // Just to have some code
}

void global_func(int val) {
  global_var = val;
}

void uses_global_func() {
  global_func(10);
}

namespace shapes {
  void another_in_shapes() {
    Circle c(1.0);
    c.getArea(); // Call a method
  }
} // namespace shapes
