#ifndef MULTI_FILE_OVERLOADS_H
#define MULTI_FILE_OVERLOADS_H

// Test overloads with declarations in header and definitions in implementation file
// This validates that hasBody detection works correctly across files

namespace multifile {

// Free function overloads - declarations only
void cross(int x);
void cross(double x);
void cross(int x, int y);

// Class with overloaded methods
class Calculator {
public:
    int compute(int a);
    int compute(int a, int b);
    double compute(double a);
};

} // namespace multifile

#endif // MULTI_FILE_OVERLOADS_H
