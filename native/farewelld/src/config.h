#pragma once

#include <map>
#include <string>

namespace farewell {

// Parses a build.prop-style key=value file. Lines starting with '#' and blank
// lines are ignored. Values keep internal spaces.
std::map<std::string, std::string> loadConfigFile(const std::string& path, std::string* error);

bool validPropertyName(const std::string& name);

}
