cmake_minimum_required(VERSION 3.24)

project(photolib_native_image LANGUAGES C)

if(NOT DEFINED PL_ZIG_TARGET)
    message(FATAL_ERROR "PL_ZIG_TARGET is required")
endif()
if(NOT DEFINED PL_NATIVE_OUTPUT)
    message(FATAL_ERROR "PL_NATIVE_OUTPUT is required")
endif()
foreach(required PL_LIBJPEG_SOURCE PL_LIBJPEG_BUILD PL_STB_SOURCE PL_VIPS_LINK_LIBRARY)
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
if(NOT EXISTS "${PL_VIPS_LINK_LIBRARY}")
    message(FATAL_ERROR "libvips link library was not found at ${PL_VIPS_LINK_LIBRARY}")
endif()

add_library(photolib_stb STATIC src/stb_bridge.c)
target_include_directories(photolib_stb PRIVATE "${PL_STB_SOURCE}")
target_compile_definitions(photolib_stb PRIVATE STBI_MAX_DIMENSIONS=30000)

if(PL_BUILD_HOST_TESTS)
    add_executable(photolib_stb_bridge_tests src/stb_bridge_tests.c)
    target_include_directories(photolib_stb_bridge_tests PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/src"
    )
    target_link_libraries(photolib_stb_bridge_tests PRIVATE photolib_stb)
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        target_link_libraries(photolib_stb_bridge_tests PRIVATE m)
    endif()
    add_custom_target(run-photolib-stb-bridge-tests
        COMMAND "$<TARGET_FILE:photolib_stb_bridge_tests>"
        DEPENDS photolib_stb_bridge_tests
        VERBATIM
        COMMENT "Running bounded native PNG encoder tests"
    )
endif()

add_library(photolib_vips_bridge STATIC src/vips_bridge.c)
target_include_directories(photolib_vips_bridge PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(PL_VIPS_SYSTEM_LIBRARIES -lpthread)
else()
    set(PL_VIPS_SYSTEM_LIBRARIES)
endif()

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
            "$<TARGET_FILE:photolib_vips_bridge>"
            "${PL_VIPS_LINK_LIBRARY}"
            ${PL_VIPS_SYSTEM_LIBRARIES}
            -lc
            -lm
            "-femit-bin=${PL_NATIVE_OUTPUT}"
    DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/src/image_processor.zig"
            "${CMAKE_CURRENT_SOURCE_DIR}/src/stb_bridge.h"
            "${CMAKE_CURRENT_SOURCE_DIR}/src/vips_bridge.h"
            "${CMAKE_CURRENT_SOURCE_DIR}/src/vips_bridge.c"
            "${PL_LIBJPEG_BUILD}/libturbojpeg.a"
            "${PL_VIPS_LINK_LIBRARY}"
            photolib_stb
            photolib_vips_bridge
    VERBATIM
    COMMENT "Building ${PL_ZIG_TARGET} PhotoLib native image library"
)

add_custom_target(photolib-native ALL DEPENDS "${PL_NATIVE_OUTPUT}")
if(PL_BUILD_HOST_TESTS)
    add_dependencies(photolib-native run-photolib-stb-bridge-tests)
endif()
