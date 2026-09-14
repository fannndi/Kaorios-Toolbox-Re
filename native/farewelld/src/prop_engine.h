#pragma once

#include <string>

namespace farewell {

struct ApplyResult {
    bool found = false;
    bool changed = false;
    bool unsupported = false;
    std::string previous;
    std::string reason;
};

// Updates an existing property in place by writing directly into the property
// area (same technique as resetprop). Only inline values shorter than
// PROP_VALUE_MAX are supported; missing properties are not created.
ApplyResult applyProperty(const std::string& name, const std::string& value);

// Reads the current inline value of an existing property.
bool readProperty(const std::string& name, std::string& out);

}
