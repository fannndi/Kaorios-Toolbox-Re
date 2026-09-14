#include "log.h"

#include <android/log.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>

namespace farewell {
namespace {

int gKmsgFd = -1;

int androidPriority(LogLevel level) {
    switch (level) {
        case LogLevel::kError:
            return ANDROID_LOG_ERROR;
        case LogLevel::kWarn:
            return ANDROID_LOG_WARN;
        default:
            return ANDROID_LOG_INFO;
    }
}

}

void logInit() {
    // Best effort: kernel log is used as a fallback when logd is not reachable.
    gKmsgFd = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);
}

void logLine(LogLevel level, const std::string& message) {
    __android_log_print(androidPriority(level), "farewelld", "%s", message.c_str());
    if (gKmsgFd >= 0) {
        dprintf(gKmsgFd, "farewelld: %s\n", message.c_str());
    }
    // Also mirror to stdout so `su -c farewelld ...` returns readable output.
    fprintf(stdout, "farewelld: %s\n", message.c_str());
    fflush(stdout);
}

void logErrno(const std::string& what) {
    logLine(LogLevel::kWarn, what + ": " + strerror(errno));
}

}
