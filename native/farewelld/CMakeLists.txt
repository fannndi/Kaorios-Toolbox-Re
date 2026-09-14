cmake_minimum_required(VERSION 3.18)
project(farewelld CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_CXX_FLAGS_RELEASE "-O2 -Wall -Wextra -fvisibility=hidden")

# Stage A property engine. Built into the daemon; can also be built as a shared
# library (-DFAREWELL_BUILD_SHARED=ON) for future in-process use.
add_library(farewell_prop STATIC
    src/prop_engine.cpp
    src/log.cpp)
target_include_directories(farewell_prop PUBLIC src)

add_executable(farewelld
    src/main.cpp
    src/config.cpp)
target_link_libraries(farewelld PRIVATE farewell_prop log)

option(FAREWELL_BUILD_SHARED "Also build libfarewell.so" OFF)
if(FAREWELL_BUILD_SHARED)
    add_library(farewell SHARED
        src/prop_engine.cpp
        src/config.cpp
        src/log.cpp)
    target_include_directories(farewell PRIVATE src)
    target_link_libraries(farewell PRIVATE log)
endif()
