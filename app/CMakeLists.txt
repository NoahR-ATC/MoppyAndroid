cmake_minimum_required(VERSION 3.6.0)

add_library(
    # Library name
    MoppyAndroidLib

    # Shared with the rest of the project
    SHARED

    # Path to source file
    src/main/cpp/MoppyAndroid.cpp
    )

find_library(
        log-lib

        log
)

target_link_libraries(
        MoppyAndroidLib

        ${log-lib}
)
#include_directories(src/main/cpp/include/)