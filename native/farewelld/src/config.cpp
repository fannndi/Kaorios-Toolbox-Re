#include "config.h"

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace farewell {
namespace {

std::string trim(const std::string& input) {
    size_t begin = 0;
    size_t end = input.size();
    while (begin < end && (input[begin] == ' ' || input[begin] == '\t')) {
        begin++;
    }
    while (end > begin && (input[end - 1] == ' ' || input[end - 1] == '\t' || input[end - 1] == '\r')) {
        end--;
    }
    return input.substr(begin, end - begin);
}

}

bool validPropertyName(const std::string& name) {
    if (name.empty() || name.size() > 91) {
        return false;
    }
    for (char c : name) {
        const bool allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                             (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
        if (!allowed) {
            return false;
        }
    }
    return true;
}

std::map<std::string, std::string> loadConfigFile(const std::string& path, std::string* error) {
    std::map<std::string, std::string> result;
    FILE* file = fopen(path.c_str(), "re");
    if (file == nullptr) {
        if (error) *error = "cannot open " + path + ": " + strerror(errno);
        return result;
    }

    char* line = nullptr;
    size_t capacity = 0;
    ssize_t length;
    int lineNumber = 0;
    while ((length = getline(&line, &capacity, file)) >= 0) {
        lineNumber++;
        std::string text = trim(std::string(line, static_cast<size_t>(length)));
        if (text.empty() || text[0] == '#') {
            continue;
        }
        const size_t separator = text.find('=');
        if (separator == std::string::npos) {
            if (error && error->empty()) {
                *error = path + ":" + std::to_string(lineNumber) + " missing '='";
            }
            continue;
        }
        const std::string name = trim(text.substr(0, separator));
        const std::string value = trim(text.substr(separator + 1));
        if (!validPropertyName(name)) {
            if (error && error->empty()) {
                *error = path + ":" + std::to_string(lineNumber) + " invalid name";
            }
            continue;
        }
        result[name] = value;
    }
    free(line);
    fclose(file);
    return result;
}

}
