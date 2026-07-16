#!/usr/bin/env sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: build-native.sh OUTPUT_DIRECTORY BUILD_DIRECTORY" >&2
    exit 2
fi

SOURCE_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUTPUT_DIRECTORY=$1
BUILD_DIRECTORY=$2
DEPENDENCY_DIRECTORY="$BUILD_DIRECTORY/dependencies"
ARCHIVE_DIRECTORY="$DEPENDENCY_DIRECTORY/archives"
DEPENDENCY_SOURCE_DIRECTORY="$DEPENDENCY_DIRECTORY/sources"

mkdir -p "$ARCHIVE_DIRECTORY" "$DEPENDENCY_SOURCE_DIRECTORY"

get_verified_archive() {
    url=$1
    path=$2
    expected_sha256=$3
    if [ -f "$path" ]; then
        actual_sha256=$(sha256sum "$path" | awk '{print $1}')
        if [ "$actual_sha256" != "$expected_sha256" ]; then
            rm -f "$path"
        fi
    fi
    if [ ! -f "$path" ]; then
        curl -fsSL --retry 5 --retry-all-errors -o "$path" "$url"
    fi
    actual_sha256=$(sha256sum "$path" | awk '{print $1}')
    if [ "$actual_sha256" != "$expected_sha256" ]; then
        echo "checksum mismatch for $path" >&2
        exit 1
    fi
}

LIBJPEG_ARCHIVE="$ARCHIVE_DIRECTORY/libjpeg-turbo-3.1.4.1.tar.gz"
LIBJPEG_SOURCE="$DEPENDENCY_SOURCE_DIRECTORY/libjpeg-turbo-3.1.4.1"
get_verified_archive \
    https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.4.1/libjpeg-turbo-3.1.4.1.tar.gz \
    "$LIBJPEG_ARCHIVE" \
    ecae8008e2cc9ade2f2c1bb9d5e6d4fb73e7c433866a056bd82980741571a022
if [ ! -f "$LIBJPEG_SOURCE/CMakeLists.txt" ]; then
    tar -xzf "$LIBJPEG_ARCHIVE" -C "$DEPENDENCY_SOURCE_DIRECTORY"
fi

STB_COMMIT=31c1ad37456438565541f4919958214b6e762fb4
STB_ARCHIVE="$ARCHIVE_DIRECTORY/stb-$STB_COMMIT.tar.gz"
STB_SOURCE="$DEPENDENCY_SOURCE_DIRECTORY/stb-$STB_COMMIT"
get_verified_archive \
    "https://codeload.github.com/nothings/stb/tar.gz/$STB_COMMIT" \
    "$STB_ARCHIVE" \
    e4e3bba9c572a4a4148373a914d88ea0f0d11de8cc2c66739926e7eca0223319
if [ ! -f "$STB_SOURCE/stb_image.h" ]; then
    tar -xzf "$STB_ARCHIVE" -C "$DEPENDENCY_SOURCE_DIRECTORY"
fi

build_target() {
    name=$1
    system_name=$2
    zig_target=$3
    relative_output=$4
    target_build_directory="$BUILD_DIRECTORY/$name"
    libjpeg_build_directory="$target_build_directory/libjpeg-turbo"
    wrapper_build_directory="$target_build_directory/wrapper"
    native_output="$OUTPUT_DIRECTORY/$relative_output"

    CC="zig cc -target $zig_target" cmake \
        -S "$LIBJPEG_SOURCE" \
        -B "$libjpeg_build_directory" \
        -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME="$system_name" \
        -DCMAKE_SYSTEM_PROCESSOR=x86_64 \
        -DENABLE_SHARED=OFF \
        -DENABLE_STATIC=ON \
        -DWITH_ARITH_DEC=OFF \
        -DWITH_ARITH_ENC=OFF \
        -DWITH_JAVA=OFF \
        -DWITH_SIMD=ON \
        -DWITH_TESTS=OFF \
        -DWITH_TOOLS=OFF \
        -DWITH_TURBOJPEG=ON
    cmake --build "$libjpeg_build_directory" --target turbojpeg-static

    CC="zig cc -target $zig_target" cmake \
        -S "$SOURCE_DIRECTORY" \
        -B "$wrapper_build_directory" \
        -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_SYSTEM_NAME="$system_name" \
        -DCMAKE_SYSTEM_PROCESSOR=x86_64 \
        -DPL_LIBJPEG_SOURCE="$LIBJPEG_SOURCE" \
        -DPL_LIBJPEG_BUILD="$libjpeg_build_directory" \
        -DPL_STB_SOURCE="$STB_SOURCE" \
        -DPL_ZIG_TARGET="$zig_target" \
        -DPL_NATIVE_OUTPUT="$native_output"
    cmake --build "$wrapper_build_directory" --target photolib-native
}

build_target \
    windows-x86_64 \
    Windows \
    x86_64-windows-gnu \
    native/windows-x86_64/photolib-image.dll

build_target \
    linux-x86_64 \
    Linux \
    x86_64-linux-gnu.2.17 \
    native/linux-x86_64/libphotolib-image.so
