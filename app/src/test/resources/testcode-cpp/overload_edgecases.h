#include <map>
#include <vector>
#include <utility>
#include <string>

void f(std::map<int, std::string> m);
void f(std::vector<std::pair<int,int>> v);

void f(std::map<int, std::string> m) {}
void f(std::vector<std::pair<int,int>> v) {}
