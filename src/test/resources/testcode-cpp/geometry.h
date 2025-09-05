#ifndef GEOMETRY_H
#define GEOMETRY_H

namespace shapes {
  class Circle {
  public:
    Circle(double r);
    double getArea();
    static int getObjectType();
  private:
    double radius;
  };
} // namespace shapes

struct Point {
  int x;
  int y;
  void print();
};

extern int global_var; // Declare global_var
void global_func(int val);
void uses_global_func(); // Declaration

#endif // GEOMETRY_H
