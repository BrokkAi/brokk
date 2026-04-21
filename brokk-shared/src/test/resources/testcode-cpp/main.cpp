#include "geometry.h"

// A simple function in main.cpp to test calls across files if needed
void main_calls_lib() {
    shapes::Circle test_circle(1.0);
    test_circle.getArea();
    global_func(100);
}

int main() {
  shapes::Circle c(5.0);
  double area = c.getArea();
  Point p = {1, 2};
  p.print();
  global_func(42);
  uses_global_func();
  shapes::another_in_shapes();
  main_calls_lib();
  return 0;
}
