cmake_minimum_required(VERSION 3.22.1)
project(pam_native_android LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(PAM_RUST_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(PAM_RUST_TARGET "x86_64-linux-android")
else()
    message(FATAL_ERROR "Pam Native supports arm64-v8a and x86_64 only")
endif()

if(NOT DEFINED PAM_NATIVE_ROOT)
    get_filename_component(PAM_NATIVE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
endif()
get_filename_component(PAM_NATIVE_ROOT "${PAM_NATIVE_ROOT}" ABSOLUTE)
set(PAM_ENGINE_LIBRARY
    "${PAM_NATIVE_ROOT}/target/${PAM_RUST_TARGET}/release/libpam_native_engine.a")
if(NOT DEFINED PAM_PHP_RUNTIME_ROOT)
    set(PAM_PHP_RUNTIME_ROOT "${PAM_NATIVE_ROOT}/runtime/android")
endif()
get_filename_component(PAM_PHP_RUNTIME_ROOT "${PAM_PHP_RUNTIME_ROOT}" ABSOLUTE)
set(PAM_PHP_ROOT "${PAM_PHP_RUNTIME_ROOT}/${ANDROID_ABI}")
set(PAM_PHP_LIBRARY "${PAM_PHP_ROOT}/lib/libphp.a")

if(NOT EXISTS "${PAM_ENGINE_LIBRARY}")
    message(FATAL_ERROR
        "Pam Native engine is missing for ${PAM_RUST_TARGET}. "
        "Run `pam mobile prepare android` first.")
endif()

if(NOT EXISTS "${PAM_PHP_LIBRARY}")
    message(FATAL_ERROR
        "Verified PHP Android runtime is missing for ${ANDROID_ABI}. "
        "Run `pam mobile runtime install android` first.")
endif()

add_library(pam_native_engine STATIC IMPORTED)
set_target_properties(pam_native_engine PROPERTIES
    IMPORTED_LOCATION "${PAM_ENGINE_LIBRARY}")

add_library(pam_php STATIC IMPORTED)
set_target_properties(pam_php PROPERTIES
    IMPORTED_LOCATION "${PAM_PHP_LIBRARY}")

file(GLOB PAM_PHP_DEPENDENCIES "${PAM_PHP_ROOT}/lib/*.a")
list(REMOVE_ITEM PAM_PHP_DEPENDENCIES "${PAM_PHP_LIBRARY}")

add_library(pam_native_android SHARED
    pam_android_bridge.cpp
)

target_compile_definitions(pam_native_android PRIVATE
    HAVE_CONFIG_H
    __ANDROID__
    ANDROID
    _GNU_SOURCE
    _FILE_OFFSET_BITS=64
)

target_include_directories(pam_native_android PRIVATE
    "${PAM_NATIVE_ROOT}/native"
    "${PAM_PHP_ROOT}/include"
    "${PAM_PHP_ROOT}/include/php"
    "${PAM_PHP_ROOT}/include/php/main"
    "${PAM_PHP_ROOT}/include/php/Zend"
    "${PAM_PHP_ROOT}/include/php/TSRM"
    "${PAM_PHP_ROOT}/include/php/sapi/embed"
)

target_link_options(pam_native_android PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--exclude-libs,ALL"
    "$<$<NOT:$<CONFIG:Debug>>:-Wl,--gc-sections>"
    "$<$<NOT:$<CONFIG:Debug>>:-Wl,--icf=safe>"
)

target_compile_options(pam_native_android PRIVATE
    "$<$<NOT:$<CONFIG:Debug>>:-O3>"
    "$<$<NOT:$<CONFIG:Debug>>:-ffunction-sections>"
    "$<$<NOT:$<CONFIG:Debug>>:-fdata-sections>"
)

target_link_libraries(pam_native_android
    "-Wl,--whole-archive"
    pam_php
    "-Wl,--no-whole-archive"
    pam_native_engine
    "-Wl,--start-group"
    ${PAM_PHP_DEPENDENCIES}
    "-Wl,--end-group"
    android
    log
    dl
    z
)
