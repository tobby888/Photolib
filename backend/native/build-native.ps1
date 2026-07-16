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
        & curl.exe -fsSL --retry 5 --retry-all-errors -o $Path $Url
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

function Invoke-NativeBuild {
    param(
        [string]$Name,
        [string]$SystemName,
        [string]$ZigTarget,
        [string]$RelativeOutput
    )

    $targetBuildDirectory = Join-Path $BuildDirectory $Name
    $libjpegBuildDirectory = Join-Path $targetBuildDirectory "libjpeg-turbo"
    $wrapperBuildDirectory = Join-Path $targetBuildDirectory "wrapper"
    $nativeOutput = Join-Path $OutputDirectory $RelativeOutput
    $env:CC = "zig cc -target $ZigTarget"

    & cmake -S $libjpegSource -B $libjpegBuildDirectory -G Ninja `
        "-DCMAKE_BUILD_TYPE=Release" `
        "-DCMAKE_SYSTEM_NAME=$SystemName" `
        "-DCMAKE_SYSTEM_PROCESSOR=x86_64" `
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
        "-DPL_LIBJPEG_SOURCE=$libjpegSource" `
        "-DPL_LIBJPEG_BUILD=$libjpegBuildDirectory" `
        "-DPL_STB_SOURCE=$stbSource" `
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
    -RelativeOutput "native/windows-x86_64/photolib-image.dll"

Invoke-NativeBuild `
    -Name "linux-x86_64" `
    -SystemName "Linux" `
    -ZigTarget "x86_64-linux-gnu.2.17" `
    -RelativeOutput "native/linux-x86_64/libphotolib-image.so"
