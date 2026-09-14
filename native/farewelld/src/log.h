#pragma once

#include <string>

namespace farewell {

enum class LogLevel {
    kInfo,
    kWarn,
    kError,
};

void logInit();
void logLine(LogLevel level, const std::string& message);
void logErrno(const std::string& what);

}
