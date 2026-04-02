#pragma once
#include <vector>

// A simple helper implementation (unused by analyzer, just present in file)
inline void g_impl(std::vector<int> v) {}

// Declaration that uses a function pointer taking a std::vector<int>
void g(void (*cb)(std::vector<int>));

// A simple definition so analyzer sees both declaration and definition
inline void g(void (*cb)(std::vector<int>)) {
    // call the callback with a sample vector
    cb(std::vector<int>{1,2,3});
}
