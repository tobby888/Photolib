param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,
    [Parameter(Mandatory = $true)]
    [string]$BuildDirectory
)

$ErrorActionPreference = "Stop"
$sourceDirectory = $PSScriptRoot
$dependencyDirectory = Join-Path $BuildDirectory "dependencies"
$archiveDirectory = Join-Path $dependencyDirectory "archives"
$dependencySourceDirectory = Join-Path $dependencyDirectory "sources"

New-Item -ItemType Directory -Force -Path $archiveDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $dependencySourceDirectory | Out-Null

function Get-VerifiedArchive {
    param(
        [string]$Url,
        [string]$Path,
        [string]$Sha256
    )

    if (Test-Path -LiteralPath $Path) {
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
        if ($actual -ne $Sha256) {
            Remove-Item -LiteralPath $Path
        }
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        & curl.exe -fsSL --ssl-no-revoke --retry 5 --retry-all-errors -o $Path $Url
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($actual -ne $Sha256) {
        throw "Checksum mismatch for $Path (expected $Sha256, got $actual)"
    }
}

$libjpegArchive = Join-Path $archiveDirectory "libjpeg-turbo-3.1.4.1.tar.gz"
$libjpegSource = Join-Path $dependencySourceDirectory "libjpeg-turbo-3.1.4.1"
Get-VerifiedArchive `
    -Url "https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.4.1/libjpeg-turbo-3.1.4.1.tar.gz" `
    -Path $libjpegArchive `
    -Sha256 "ecae8008e2cc9ade2f2c1bb9d5e6d4fb73e7c433866a056bd82980741571a022"
if (-not (Test-Path -LiteralPath (Join-Path $libjpegSource "CMakeLists.txt"))) {
    & tar -xzf $libjpegArchive -C $dependencySourceDirectory
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$stbCommit = "31c1ad37456438565541f4919958214b6e762fb4"
$stbArchive = Join-Path $archiveDirectory "stb-$stbCommit.tar.gz"
$stbSource = Join-Path $dependencySourceDirectory "stb-$stbCommit"
Get-VerifiedArchive `
    -Url "https://codeload.github.com/nothings/stb/tar.gz/$stbCommit" `
    -Path $stbArchive `
    -Sha256 "e4e3bba9c572a4a4148373a914d88ea0f0d11de8cc2c66739926e7eca0223319"
if (-not (Test-Path -LiteralPath (Join-Path $stbSource "stb_image.h"))) {
    & tar -xzf $stbArchive -C $dependencySourceDirectory
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$gplLicense = Join-Path $archiveDirectory "GPL-3.0.txt"
$lgplLicense = Join-Path $archiveDirectory "LGPL-3.0.txt"
$mplLicense = Join-Path $archiveDirectory "MPL-2.0.txt"
Get-VerifiedArchive `
    -Url "https://www.gnu.org/licenses/gpl-3.0.txt" `
    -Path $gplLicense `
    -Sha256 "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986"
Get-VerifiedArchive `
    -Url "https://www.gnu.org/licenses/lgpl-3.0.txt" `
    -Path $lgplLicense `
    -Sha256 "e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118"
Get-VerifiedArchive `
    -Url "https://www.mozilla.org/media/MPL/2.0/index.815ca599c9df.txt" `
    -Path $mplLicense `
    -Sha256 "fab3dd6bdab226f1c08630b1dd917e11fcb4ec5e1e020e2c16f83a0a13863e85"

$vipsVersion = "1.3.2"
$vipsWindowsArchive = Join-Path $archiveDirectory "sharp-libvips-win32-x64-$vipsVersion.tgz"
$vipsWindowsSource = Join-Path $dependencySourceDirectory "sharp-libvips-win32-x64-$vipsVersion"
Get-VerifiedArchive `
    -Url "https://registry.npmjs.org/@img/sharp-libvips-win32-x64/-/sharp-libvips-win32-x64-$vipsVersion.tgz" `
    -Path $vipsWindowsArchive `
    -Sha256 "bcae355919358e0406c1674d0beaf841e9b11f321f8a54b927cddf4935c27668"
if (-not (Test-Path -LiteralPath (Join-Path $vipsWindowsSource "package/lib/libvips-42.dll"))) {
    New-Item -ItemType Directory -Force -Path $vipsWindowsSource | Out-Null
    & tar -xzf $vipsWindowsArchive -C $vipsWindowsSource
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$vipsLinuxArchive = Join-Path $archiveDirectory "sharp-libvips-linux-x64-$vipsVersion.tgz"
$vipsLinuxSource = Join-Path $dependencySourceDirectory "sharp-libvips-linux-x64-$vipsVersion"
Get-VerifiedArchive `
    -Url "https://registry.npmjs.org/@img/sharp-libvips-linux-x64/-/sharp-libvips-linux-x64-$vipsVersion.tgz" `
    -Path $vipsLinuxArchive `
    -Sha256 "8cf0eafeaca832b68942fe1a770fb5f3b490504d3a9f2e3f56ee8784c9d65c45"
if (-not (Test-Path -LiteralPath (Join-Path $vipsLinuxSource "package/lib/libvips-cpp.so.8.18.3"))) {
    New-Item -ItemType Directory -Force -Path $vipsLinuxSource | Out-Null
    & tar -xzf $vipsLinuxArchive -C $vipsLinuxSource
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$vipsWindowsDll = Join-Path $vipsWindowsSource "package/lib/libvips-42.dll"
$vipsWindowsImport = Join-Path $vipsWindowsSource "package/lib/libvips.lib"
$vipsLinuxLibrary = Join-Path $vipsLinuxSource "package/lib/libvips-cpp.so.8.18.3"
$windowsResourceDirectory = Join-Path $OutputDirectory "native/windows-x86_64"
$linuxResourceDirectory = Join-Path $OutputDirectory "native/linux-x86_64"
$windowsLicenseDirectory = Join-Path $OutputDirectory "native/licenses/sharp-libvips/windows-x64"
$linuxLicenseDirectory = Join-Path $OutputDirectory "native/licenses/sharp-libvips/linux-x64"
$libjpegLicenseDirectory = Join-Path $OutputDirectory "native/licenses/libjpeg-turbo"
$stbLicenseDirectory = Join-Path $OutputDirectory "native/licenses/stb"
$commonLicenseDirectory = Join-Path $OutputDirectory "native/licenses/common"
New-Item -ItemType Directory -Force -Path $windowsResourceDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $linuxResourceDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $windowsLicenseDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $linuxLicenseDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $libjpegLicenseDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $stbLicenseDirectory | Out-Null
New-Item -ItemType Directory -Force -Path $commonLicenseDirectory | Out-Null
Copy-Item -Force -LiteralPath $vipsWindowsDll `
    -Destination (Join-Path $windowsResourceDirectory "libvips-42.dll")
Copy-Item -Force -LiteralPath $vipsLinuxLibrary `
    -Destination (Join-Path $linuxResourceDirectory "libvips-cpp.so.8.18.3")
foreach ($manifest in @("README.md", "package.json", "versions.json")) {
    Copy-Item -Force `
        -LiteralPath (Join-Path $vipsWindowsSource "package/$manifest") `
        -Destination (Join-Path $windowsLicenseDirectory $manifest)
    Copy-Item -Force `
        -LiteralPath (Join-Path $vipsLinuxSource "package/$manifest") `
        -Destination (Join-Path $linuxLicenseDirectory $manifest)
}
Copy-Item -Force -LiteralPath (Join-Path $libjpegSource "LICENSE.md") `
    -Destination (Join-Path $libjpegLicenseDirectory "LICENSE.md")
Copy-Item -Force -LiteralPath (Join-Path $libjpegSource "README.ijg") `
    -Destination (Join-Path $libjpegLicenseDirectory "README.ijg")
Copy-Item -Force -LiteralPath (Join-Path $stbSource "LICENSE") `
    -Destination (Join-Path $stbLicenseDirectory "LICENSE")
Copy-Item -Force -LiteralPath $gplLicense `
    -Destination (Join-Path $commonLicenseDirectory "GPL-3.0.txt")
Copy-Item -Force -LiteralPath $lgplLicense `
    -Destination (Join-Path $commonLicenseDirectory "LGPL-3.0.txt")
Copy-Item -Force -LiteralPath $mplLicense `
    -Destination (Join-Path $commonLicenseDirectory "MPL-2.0.txt")

function Invoke-NativeBuild {
    param(
        [string]$Name,
        [string]$SystemName,
        [string]$ZigTarget,
        [string]$RelativeOutput,
        [string]$VipsLinkLibrary,
        [bool]$RunHostTests
    )

    $targetBuildDirectory = Join-Path $BuildDirectory $Name
    $libjpegBuildDirectory = Join-Path $targetBuildDirectory "libjpeg-turbo"
    $wrapperBuildDirectory = Join-Path $targetBuildDirectory "wrapper"
    $nativeOutput = Join-Path $OutputDirectory $RelativeOutput

    # CMake needs single-executable tools; wrap "zig ar" / "zig ranlib" as .cmd files
    New-Item -ItemType Directory -Force -Path $targetBuildDirectory | Out-Null
    $zigArCmd = Join-Path $targetBuildDirectory "zig-ar.cmd"
    $zigRanlibCmd = Join-Path $targetBuildDirectory "zig-ranlib.cmd"
    "@echo off`nzig ar %*" | Set-Content -Path $zigArCmd -Encoding ASCII
    "@echo off`nzig ranlib %*" | Set-Content -Path $zigRanlibCmd -Encoding ASCII

    $env:CC = "zig cc -target $ZigTarget"

    & cmake -S $libjpegSource -B $libjpegBuildDirectory -G Ninja `
        "-DCMAKE_BUILD_TYPE=Release" `
        "-DCMAKE_SYSTEM_NAME=$SystemName" `
        "-DCMAKE_SYSTEM_PROCESSOR=x86_64" `
        "-DCMAKE_AR=$zigArCmd" `
        "-DCMAKE_RANLIB=$zigRanlibCmd" `
        "-DENABLE_SHARED=OFF" `
        "-DENABLE_STATIC=ON" `
        "-DWITH_ARITH_DEC=OFF" `
        "-DWITH_ARITH_ENC=OFF" `
        "-DWITH_JAVA=OFF" `
        "-DWITH_SIMD=ON" `
        "-DWITH_TESTS=OFF" `
        "-DWITH_TOOLS=OFF" `
        "-DWITH_TURBOJPEG=ON"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & cmake --build $libjpegBuildDirectory --target turbojpeg-static
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & cmake -S $sourceDirectory -B $wrapperBuildDirectory -G Ninja `
        "-DCMAKE_BUILD_TYPE=Release" `
        "-DCMAKE_SYSTEM_NAME=$SystemName" `
        "-DCMAKE_SYSTEM_PROCESSOR=x86_64" `
        "-DCMAKE_AR=$zigArCmd" `
        "-DCMAKE_RANLIB=$zigRanlibCmd" `
        "-DPL_LIBJPEG_SOURCE=$libjpegSource" `
        "-DPL_LIBJPEG_BUILD=$libjpegBuildDirectory" `
        "-DPL_STB_SOURCE=$stbSource" `
        "-DPL_VIPS_LINK_LIBRARY=$VipsLinkLibrary" `
        "-DPL_BUILD_HOST_TESTS=$($RunHostTests.ToString().ToUpperInvariant())" `
        "-DPL_ZIG_TARGET=$ZigTarget" `
        "-DPL_NATIVE_OUTPUT=$nativeOutput"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & cmake --build $wrapperBuildDirectory --target photolib-native
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Invoke-NativeBuild `
    -Name "windows-x86_64" `
    -SystemName "Windows" `
    -ZigTarget "x86_64-windows-gnu" `
    -RelativeOutput "native/windows-x86_64/photolib-image.dll" `
    -VipsLinkLibrary $vipsWindowsImport `
    -RunHostTests $true

Invoke-NativeBuild `
    -Name "linux-x86_64" `
    -SystemName "Linux" `
    -ZigTarget "x86_64-linux-gnu.2.28" `
    -RelativeOutput "native/linux-x86_64/libphotolib-image.so" `
    -VipsLinkLibrary $vipsLinuxLibrary `
    -RunHostTests $false
