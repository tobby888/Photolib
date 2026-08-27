#!/usr/bin/env sh
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: build-native.sh OUTPUT_DIRECTORY BUILD_DIRECTORY" >&2
    exit 2
fi

SOURCE_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
OUTPUT_DIRECTORY=$1
BUILD_DIRECTORY=$2

# Ninja resolves relative paths against its own build directory, so a relative
# argument here only surfaces much later as a missing libturbojpeg.a.
mkdir -p "$OUTPUT_DIRECTORY" "$BUILD_DIRECTORY"
OUTPUT_DIRECTORY=$(CDPATH= cd -- "$OUTPUT_DIRECTORY" && pwd)
BUILD_DIRECTORY=$(CDPATH= cd -- "$BUILD_DIRECTORY" && pwd)

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

GPL_LICENSE="$ARCHIVE_DIRECTORY/GPL-3.0.txt"
LGPL_LICENSE="$ARCHIVE_DIRECTORY/LGPL-3.0.txt"
MPL_LICENSE="$ARCHIVE_DIRECTORY/MPL-2.0.txt"
get_verified_archive \
    https://www.gnu.org/licenses/gpl-3.0.txt \
    "$GPL_LICENSE" \
    3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986
get_verified_archive \
    https://www.gnu.org/licenses/lgpl-3.0.txt \
    "$LGPL_LICENSE" \
    e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118
get_verified_archive \
    https://www.mozilla.org/media/MPL/2.0/index.815ca599c9df.txt \
    "$MPL_LICENSE" \
    fab3dd6bdab226f1c08630b1dd917e11fcb4ec5e1e020e2c16f83a0a13863e85

VIPS_VERSION=1.3.2
VIPS_WINDOWS_ARCHIVE="$ARCHIVE_DIRECTORY/sharp-libvips-win32-x64-$VIPS_VERSION.tgz"
VIPS_WINDOWS_SOURCE="$DEPENDENCY_SOURCE_DIRECTORY/sharp-libvips-win32-x64-$VIPS_VERSION"
get_verified_archive \
    "https://registry.npmjs.org/@img/sharp-libvips-win32-x64/-/sharp-libvips-win32-x64-$VIPS_VERSION.tgz" \
    "$VIPS_WINDOWS_ARCHIVE" \
    bcae355919358e0406c1674d0beaf841e9b11f321f8a54b927cddf4935c27668
if [ ! -f "$VIPS_WINDOWS_SOURCE/package/lib/libvips-42.dll" ]; then
    mkdir -p "$VIPS_WINDOWS_SOURCE"
    tar -xzf "$VIPS_WINDOWS_ARCHIVE" -C "$VIPS_WINDOWS_SOURCE"
fi

VIPS_LINUX_ARCHIVE="$ARCHIVE_DIRECTORY/sharp-libvips-linux-x64-$VIPS_VERSION.tgz"
VIPS_LINUX_SOURCE="$DEPENDENCY_SOURCE_DIRECTORY/sharp-libvips-linux-x64-$VIPS_VERSION"
get_verified_archive \
    "https://registry.npmjs.org/@img/sharp-libvips-linux-x64/-/sharp-libvips-linux-x64-$VIPS_VERSION.tgz" \
    "$VIPS_LINUX_ARCHIVE" \
    8cf0eafeaca832b68942fe1a770fb5f3b490504d3a9f2e3f56ee8784c9d65c45
if [ ! -f "$VIPS_LINUX_SOURCE/package/lib/libvips-cpp.so.8.18.3" ]; then
    mkdir -p "$VIPS_LINUX_SOURCE"
    tar -xzf "$VIPS_LINUX_ARCHIVE" -C "$VIPS_LINUX_SOURCE"
fi

VIPS_WINDOWS_DLL="$VIPS_WINDOWS_SOURCE/package/lib/libvips-42.dll"
VIPS_WINDOWS_IMPORT="$VIPS_WINDOWS_SOURCE/package/lib/libvips.lib"
VIPS_LINUX_LIBRARY="$VIPS_LINUX_SOURCE/package/lib/libvips-cpp.so.8.18.3"
mkdir -p \
    "$OUTPUT_DIRECTORY/native/windows-x86_64" \
    "$OUTPUT_DIRECTORY/native/linux-x86_64" \
    "$OUTPUT_DIRECTORY/native/licenses/sharp-libvips/windows-x64" \
    "$OUTPUT_DIRECTORY/native/licenses/sharp-libvips/linux-x64" \
    "$OUTPUT_DIRECTORY/native/licenses/libjpeg-turbo" \
    "$OUTPUT_DIRECTORY/native/licenses/stb" \
    "$OUTPUT_DIRECTORY/native/licenses/common"
cp -f "$VIPS_WINDOWS_DLL" \
    "$OUTPUT_DIRECTORY/native/windows-x86_64/libvips-42.dll"
cp -f "$VIPS_LINUX_LIBRARY" \
    "$OUTPUT_DIRECTORY/native/linux-x86_64/libvips-cpp.so.8.18.3"
for manifest in README.md package.json versions.json; do
    cp -f "$VIPS_WINDOWS_SOURCE/package/$manifest" \
        "$OUTPUT_DIRECTORY/native/licenses/sharp-libvips/windows-x64/$manifest"
    cp -f "$VIPS_LINUX_SOURCE/package/$manifest" \
        "$OUTPUT_DIRECTORY/native/licenses/sharp-libvips/linux-x64/$manifest"
done
cp -f "$LIBJPEG_SOURCE/LICENSE.md" \
    "$OUTPUT_DIRECTORY/native/licenses/libjpeg-turbo/LICENSE.md"
cp -f "$LIBJPEG_SOURCE/README.ijg" \
    "$OUTPUT_DIRECTORY/native/licenses/libjpeg-turbo/README.ijg"
cp -f "$STB_SOURCE/LICENSE" \
    "$OUTPUT_DIRECTORY/native/licenses/stb/LICENSE"
cp -f "$GPL_LICENSE" \
    "$OUTPUT_DIRECTORY/native/licenses/common/GPL-3.0.txt"
cp -f "$LGPL_LICENSE" \
    "$OUTPUT_DIRECTORY/native/licenses/common/LGPL-3.0.txt"
cp -f "$MPL_LICENSE" \
    "$OUTPUT_DIRECTORY/native/licenses/common/MPL-2.0.txt"

build_target() {
    name=$1
    system_name=$2
    zig_target=$3
    relative_output=$4
    vips_link_library=$5
    run_host_tests=$6
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
        -DPL_VIPS_LINK_LIBRARY="$vips_link_library" \
        -DPL_BUILD_HOST_TESTS="$run_host_tests" \
        -DPL_ZIG_TARGET="$zig_target" \
        -DPL_NATIVE_OUTPUT="$native_output"
    cmake --build "$wrapper_build_directory" --target photolib-native
}

build_target \
    windows-x86_64 \
    Windows \
    x86_64-windows-gnu \
    native/windows-x86_64/photolib-image.dll \
    "$VIPS_WINDOWS_IMPORT" \
    OFF

build_target \
    linux-x86_64 \
    Linux \
    x86_64-linux-gnu.2.28 \
    native/linux-x86_64/libphotolib-image.so \
    "$VIPS_LINUX_LIBRARY" \
    ON
