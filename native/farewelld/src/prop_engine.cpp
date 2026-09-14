#include "prop_engine.h"

#include "log.h"

#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/futex.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

namespace farewell {
namespace {

// Verified against bionic android-10.0.0_r47 .. android-12.1.0_r27.
constexpr size_t kPropValueMax = 92;             // PROP_VALUE_MAX
constexpr uint32_t kPropAreaMagic = 0x504f5250;  // "PROP"
constexpr uint32_t kLongFlag = 1u << 16;         // prop_info::kLongFlag
constexpr size_t kPropAreaHeader = 128;          // bytes before area data
constexpr const char* kPropertiesDir = "/dev/__properties__";
constexpr const char* kSerialAreaName = "properties_serial";

struct PropInfo {
    uint32_t serial;
    char value[kPropValueMax];
};

struct Area {
    std::string name;
    int fd = -1;
    uint8_t* base = nullptr;
    size_t size = 0;
};

using FindFn = const PropInfo* (*)(const char*);

std::mutex gMutex;
std::vector<Area> gAreas;
Area* gSerialArea = nullptr;
FindFn gFind = nullptr;
bool gInitialized = false;

void futexWake(uint32_t* address) {
    syscall(__NR_futex, address, FUTEX_WAKE, INT32_MAX, nullptr, nullptr, 0);
}

void initializeLocked() {
    if (gInitialized) {
        return;
    }
    gInitialized = true;

    gFind = reinterpret_cast<FindFn>(dlsym(RTLD_DEFAULT, "__system_property_find"));
    if (gFind == nullptr) {
        logLine(LogLevel::kError, "libc __system_property_find unavailable");
        return;
    }

    DIR* dir = opendir(kPropertiesDir);
    if (dir == nullptr) {
        logErrno("opendir " + std::string(kPropertiesDir));
        return;
    }

    while (dirent* entry = readdir(dir)) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        const std::string path = std::string(kPropertiesDir) + "/" + entry->d_name;
        const int fd = open(path.c_str(), O_RDWR | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) {
            continue;
        }
        struct stat info {};
        if (fstat(fd, &info) != 0 || info.st_size < static_cast<off_t>(kPropAreaHeader)) {
            close(fd);
            continue;
        }
        void* mapping = mmap(nullptr, static_cast<size_t>(info.st_size), PROT_READ | PROT_WRITE,
                             MAP_SHARED, fd, 0);
        if (mapping == MAP_FAILED) {
            close(fd);
            continue;
        }
        auto* bytes = static_cast<uint8_t*>(mapping);
        uint32_t magic = 0;
        memcpy(&magic, bytes + 8, sizeof(magic));
        if (magic != kPropAreaMagic) {
            munmap(mapping, static_cast<size_t>(info.st_size));
            close(fd);
            continue;
        }
        gAreas.push_back(Area{entry->d_name, fd, bytes, static_cast<size_t>(info.st_size)});
        if (entry->d_name == std::string(kSerialAreaName)) {
            gSerialArea = &gAreas.back();
        }
    }
    closedir(dir);
    logLine(LogLevel::kInfo, "mapped " + std::to_string(gAreas.size()) + " property areas" +
                                 (gSerialArea ? "" : " (serial area missing)"));
}

const PropInfo* locate(const std::string& name, Area** owner) {
    const PropInfo* info = gFind(name.c_str());
    if (info == nullptr) {
        return nullptr;
    }
    const auto* address = reinterpret_cast<const uint8_t*>(info);
    for (Area& area : gAreas) {
        if (address >= area.base && address + sizeof(PropInfo) <= area.base + area.size) {
            *owner = &area;
            return info;
        }
    }
    return nullptr;
}

bool readValue(const PropInfo* info, std::string& out) {
    for (int attempt = 0; attempt < 64; attempt++) {
        const uint32_t before = __atomic_load_n(&info->serial, __ATOMIC_ACQUIRE);
        if ((before & 1u) != 0) {
            continue;  // write in progress
        }
        if ((before & kLongFlag) != 0) {
            return false;
        }
        const size_t length = static_cast<size_t>(before >> 24);
        if (length >= kPropValueMax) {
            return false;
        }
        char buffer[kPropValueMax];
        memcpy(buffer, info->value, length);
        buffer[length] = '\0';
        const uint32_t after = __atomic_load_n(&info->serial, __ATOMIC_ACQUIRE);
        if (before == after) {
            out.assign(buffer, length);
            return true;
        }
    }
    return false;
}

void bumpGlobalSerial() {
    if (gSerialArea == nullptr) {
        return;
    }
    auto* serial = reinterpret_cast<uint32_t*>(gSerialArea->base + 4);
    __atomic_add_fetch(serial, 1u, __ATOMIC_RELEASE);
    futexWake(serial);
}

}

bool readProperty(const std::string& name, std::string& out) {
    std::lock_guard<std::mutex> lock(gMutex);
    initializeLocked();
    if (gFind == nullptr) {
        return false;
    }
    Area* owner = nullptr;
    const PropInfo* info = locate(name, &owner);
    if (info == nullptr) {
        return false;
    }
    return readValue(info, out);
}

ApplyResult applyProperty(const std::string& name, const std::string& value) {
    ApplyResult result;
    if (name.empty() || value.empty() || value.size() >= kPropValueMax) {
        result.unsupported = true;
        result.reason = "value length unsupported";
        return result;
    }
    for (char c : value) {
        if (c == '\0' || c == '\n' || c == '\r') {
            result.unsupported = true;
            result.reason = "value contains control character";
            return result;
        }
    }

    std::lock_guard<std::mutex> lock(gMutex);
    initializeLocked();
    if (gFind == nullptr) {
        result.unsupported = true;
        result.reason = "no property lookup";
        return result;
    }

    Area* owner = nullptr;
    const PropInfo* info = locate(name, &owner);
    if (info == nullptr) {
        return result;  // found == false: property does not exist
    }
    result.found = true;

    if ((__atomic_load_n(&info->serial, __ATOMIC_ACQUIRE) & kLongFlag) != 0) {
        result.unsupported = true;
        result.reason = "long property not supported";
        return result;
    }

    std::string current;
    if (!readValue(info, current)) {
        result.unsupported = true;
        result.reason = "unstable property value";
        return result;
    }
    result.previous = current;
    if (current == value) {
        return result;  // found, unchanged
    }

    const size_t offset = reinterpret_cast<const uint8_t*>(info) - owner->base;
    auto* writable = reinterpret_cast<PropInfo*>(owner->base + offset);

    uint32_t serial = __atomic_load_n(&writable->serial, __ATOMIC_RELAXED);
    serial |= 1u;
    __atomic_store_n(&writable->serial, serial, __ATOMIC_RELAXED);
    memcpy(writable->value, value.c_str(), value.size());
    memset(writable->value + value.size(), 0, kPropValueMax - value.size());
    const uint32_t next = (static_cast<uint32_t>(value.size()) << 24) | ((serial + 1u) & 0xffffffu);
    __atomic_store_n(&writable->serial, next, __ATOMIC_RELEASE);
    futexWake(&writable->serial);
    bumpGlobalSerial();

    result.changed = true;
    return result;
}

}
