#include "config.h"
#include "log.h"
#include "prop_engine.h"

#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <map>
#include <string>
#include <unistd.h>

namespace {

constexpr const char* kDefaultConfig = "/system/etc/farewell/props.conf";
constexpr const char* kStatusProp = "sys.pfix_status";
constexpr const char* kVersion = "1";

volatile sig_atomic_t gStop = 0;
volatile sig_atomic_t gReload = 0;

void onStop(int) {
    gStop = 1;
}

void onReload(int) {
    gReload = 1;
}

bool gConfigWarned = false;
bool gStatusMissing = false;

struct Stats {
    int total = 0;
    int changed = 0;
    int unchanged = 0;
    int missing = 0;
    int skipped = 0;
};

void sleepWithFlags(int seconds) {
    for (int i = 0; i < seconds * 5 && !gStop && !gReload; i++) {
        usleep(200 * 1000);
    }
}

std::string timestamp() {
    char buffer[32];
    const time_t now = time(nullptr);
    struct tm parts {};
    localtime_r(&now, &parts);
    strftime(buffer, sizeof(buffer), "%H:%M:%S", &parts);
    return buffer;
}

Stats applyPass(const std::map<std::string, std::string>& props, bool verbose) {
    Stats stats;
    for (const auto& entry : props) {
        const std::string& name = entry.first;
        const std::string& value = entry.second;
        stats.total++;
        if (value.empty()) {
            stats.skipped++;
            continue;
        }
        const farewell::ApplyResult result = farewell::applyProperty(name, value);
        if (!result.found) {
            stats.missing++;
            continue;
        }
        if (result.unsupported) {
            stats.skipped++;
            if (verbose) {
                farewell::logLine(farewell::LogLevel::kWarn,
                                  "skip " + name + " (" + result.reason + ")");
            }
            continue;
        }
        if (result.changed) {
            stats.changed++;
            if (verbose) {
                farewell::logLine(farewell::LogLevel::kInfo,
                                  "set " + name + " (" + std::to_string(result.previous.size()) +
                                      " -> " + std::to_string(value.size()) + " bytes)");
            }
        } else {
            stats.unchanged++;
        }
    }
    return stats;
}

void publishStatus(const Stats& stats) {
    char status[96];
    snprintf(status, sizeof(status), "v%s %s c%d u%d m%d s%d t%d", kVersion, timestamp().c_str(),
             stats.changed, stats.unchanged, stats.missing, stats.skipped, stats.total);
    const farewell::ApplyResult result = farewell::applyProperty(kStatusProp, status);
    if (!result.found && !gStatusMissing) {
        gStatusMissing = true;
        farewell::logLine(farewell::LogLevel::kWarn,
                          std::string("status property ") + kStatusProp +
                              " not present (add it to build.prop)");
    }
}

void usage() {
    puts("farewelld - native property service (Stage A)\n"
         "usage: farewelld [--config <path>] [--interval <seconds>] [--prop key=value]\n"
         "                 [--once] [--verbose] [--help]");
}

}

int main(int argc, char** argv) {
    std::string configPath = kDefaultConfig;
    int interval = 30;
    bool once = false;
    bool verbose = false;
    std::map<std::string, std::string> overrides;

    for (int i = 1; i < argc; i++) {
        const std::string arg = argv[i];
        if (arg == "--config" && i + 1 < argc) {
            configPath = argv[++i];
        } else if (arg == "--interval" && i + 1 < argc) {
            interval = atoi(argv[++i]);
            if (interval < 1) interval = 1;
        } else if (arg == "--prop" && i + 1 < argc) {
            const std::string item = argv[++i];
            const size_t separator = item.find('=');
            if (separator == std::string::npos) {
                fprintf(stderr, "invalid --prop value: %s\n", item.c_str());
                return 2;
            }
            const std::string name = item.substr(0, separator);
            if (!farewell::validPropertyName(name)) {
                fprintf(stderr, "invalid property name: %s\n", name.c_str());
                return 2;
            }
            overrides[name] = item.substr(separator + 1);
        } else if (arg == "--once") {
            once = true;
        } else if (arg == "--verbose") {
            verbose = true;
        } else if (arg == "--help" || arg == "-h") {
            usage();
            return 0;
        } else {
            fprintf(stderr, "unknown argument: %s\n", arg.c_str());
            usage();
            return 2;
        }
    }

    signal(SIGTERM, onStop);
    signal(SIGINT, onStop);
    signal(SIGHUP, onReload);
    signal(SIGPIPE, SIG_IGN);

    farewell::logInit();
    farewell::logLine(farewell::LogLevel::kInfo,
                      "farewelld v" + std::string(kVersion) + " pid=" + std::to_string(getpid()) +
                          " config=" + configPath + " interval=" + std::to_string(interval) + "s");

    do {
        std::string error;
        std::map<std::string, std::string> props = farewell::loadConfigFile(configPath, &error);
        if (!error.empty() && !gConfigWarned) {
            gConfigWarned = true;
            farewell::logLine(farewell::LogLevel::kWarn, "config: " + error);
        }
        for (const auto& entry : overrides) {
            props[entry.first] = entry.second;
        }

        const Stats stats = applyPass(props, verbose);
        farewell::logLine(farewell::LogLevel::kInfo,
                          "pass: changed=" + std::to_string(stats.changed) +
                              " unchanged=" + std::to_string(stats.unchanged) +
                              " missing=" + std::to_string(stats.missing) +
                              " skipped=" + std::to_string(stats.skipped) +
                              " total=" + std::to_string(stats.total));
        publishStatus(stats);

        if (once) {
            break;
        }
        gReload = 0;
        sleepWithFlags(interval);
    } while (!gStop);

    farewell::logLine(farewell::LogLevel::kInfo, "farewelld stopped");
    return 0;
}
