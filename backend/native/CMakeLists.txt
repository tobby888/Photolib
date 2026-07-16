cmake_minimum_required(VERSION 3.24)

project(photolib_native_image LANGUAGES C)

if(NOT DEFINED PL_ZIG_TARGET)
    message(FATAL_ERROR "PL_ZIG_TARGET is required")
endif()
if(NOT DEFINED PL_NATIVE_OUTPUT)
    message(FATAL_ERROR "PL_NATIVE_OUTPUT is required")
endif()
foreach(required PL_LIBJPEG_SOURCE PL_LIBJPEG_BUILD PL_STB_SOURCE)
    if(NOT DEFINED ${required})
        message(FATAL_ERROR "${required} is required")
    endif()
endforeach()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(turbojpeg-static STATIC IMPORTED GLOBAL)
set_target_properties(turbojpeg-static PROPERTIES
    IMPORTED_LOCATION "${PL_LIBJPEG_BUILD}/libturbojpeg.a"
)

if(NOT EXISTS "${PL_LIBJPEG_BUILD}/libturbojpeg.a")
    message(FATAL_ERROR "libturbojpeg.a was not built at ${PL_LIBJPEG_BUILD}")
endif()

add_library(photolib_stb STATIC src/stb_bridge.c)
target_include_directories(photolib_stb PRIVATE "${PL_STB_SOURCE}")
target_compile_definitions(photolib_stb PRIVATE STBI_MAX_DIMENSIONS=30000)

get_filename_component(PL_NATIVE_OUTPUT_DIR "${PL_NATIVE_OUTPUT}" DIRECTORY)
add_custom_command(
    OUTPUT "${PL_NATIVE_OUTPUT}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${PL_NATIVE_OUTPUT_DIR}"
    COMMAND zig build-lib
            "${CMAKE_CURRENT_SOURCE_DIR}/src/image_processor.zig"
            -O ReleaseFast
            -target "${PL_ZIG_TARGET}"
            -dynamic
            -fPIC
            -fstrip
            -fno-emit-implib
            -I "${PL_LIBJPEG_SOURCE}/src"
            -I "${PL_LIBJPEG_BUILD}"
            -I "${CMAKE_CURRENT_SOURCE_DIR}/src"
            "$<TARGET_FILE:turbojpeg-static>"
            "$<TARGET_FILE:photolib_stb>"
            -lc
            -lm
            "-femit-bin=${PL_NATIVE_OUTPUT}"
    DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/src/image_processor.zig"
            "${CMAKE_CURRENT_SOURCE_DIR}/src/stb_bridge.h"
            "${PL_LIBJPEG_BUILD}/libturbojpeg.a"
            photolib_stb
    VERBATIM
    COMMENT "Building ${PL_ZIG_TARGET} PhotoLib native image library"
)

add_custom_target(photolib-native ALL DEPENDS "${PL_NATIVE_OUTPUT}")
