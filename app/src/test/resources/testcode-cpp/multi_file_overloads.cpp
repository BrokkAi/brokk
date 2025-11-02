#include "multi_file_overloads.h"

namespace multifile {

// Free function overload definitions with bodies
void cross(int x) {
    // Definition for int overload
}

void cross(double x) {
    // Definition for double overload
}

void cross(int x, int y) {
    // Definition for two-parameter overload
}

// Class method definitions with bodies
int Calculator::compute(int a) {
    return a * 2;
}

int Calculator::compute(int a, int b) {
    return a + b;
}

double Calculator::compute(double a) {
    return a * 1.5;
}

} // namespace multifile
