param(
    [string]$Ndk = "",
    [string]$Abi = "arm64-v8a",
    [string]$Api = "29",
    [switch]$Shared,
    [switch]$NoStrip
)

$ErrorActionPreference = "Stop"

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path $sdk)) { throw "Android SDK not found at $sdk" }

if (-not $Ndk) {
    $ndkDir = Join-Path $sdk "ndk"
    if (-not (Test-Path $ndkDir)) { throw "No NDK installed under $ndkDir" }
    $Ndk = (Get-ChildItem $ndkDir -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
}
if (-not (Test-Path (Join-Path $Ndk "build\cmake\android.toolchain.cmake"))) {
    throw "Invalid NDK: $Ndk"
}

$cmakeRoot = (Get-ChildItem (Join-Path $sdk "cmake") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
$cmake = Join-Path $cmakeRoot "bin\cmake.exe"
$ninja = Join-Path $cmakeRoot "bin\ninja.exe"
if (-not (Test-Path $cmake)) { throw "cmake not found in SDK ($cmakeRoot)" }
if (-not (Test-Path $ninja)) { throw "ninja not found in SDK ($ninja)" }
$env:PATH = "$(Split-Path $cmake);$($env:PATH)"

$root = $PSScriptRoot
$source = Join-Path $root "farewelld"
$buildDir = Join-Path $source "build-$Abi"
$outDir = Join-Path $root "out\$Abi"
$romBin = Join-Path $root "rom\system\bin"

Write-Host "NDK   : $Ndk"
Write-Host "CMake : $cmake"
Write-Host "ABI   : $Abi (android-$Api)"

$configureArgs = @(
    "-S", $source,
    "-B", $buildDir,
    "-G", "Ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$(Join-Path $Ndk 'build\cmake\android.toolchain.cmake')",
    "-DANDROID_ABI=$Abi",
    "-DANDROID_PLATFORM=android-$Api",
    "-DANDROID_STL=c++_static",
    "-DCMAKE_BUILD_TYPE=Release"
)
if ($Shared) { $configureArgs += "-DFAREWELL_BUILD_SHARED=ON" }

& $cmake @configureArgs
if ($LASTEXITCODE -ne 0) { throw "cmake configure failed" }

& $cmake --build $buildDir
if ($LASTEXITCODE -ne 0) { throw "cmake build failed" }

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
New-Item -ItemType Directory -Force -Path $romBin | Out-Null

$binary = Join-Path $buildDir "farewelld"
Copy-Item $binary (Join-Path $outDir "farewelld") -Force
Copy-Item $binary (Join-Path $romBin "farewelld") -Force

if ($Shared) {
    $library = Join-Path $buildDir "libfarewell.so"
    if (Test-Path $library) { Copy-Item $library (Join-Path $outDir "libfarewell.so") -Force }
}

$strip = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
if (-not $NoStrip -and (Test-Path $strip)) {
    & $strip --strip-unneeded (Join-Path $romBin "farewelld")
    Copy-Item (Join-Path $romBin "farewelld") (Join-Path $outDir "farewelld") -Force
}

$artifact = Get-Item (Join-Path $romBin "farewelld")
Write-Host ("Built {0} ({1:N0} bytes)" -f $artifact.FullName, $artifact.Length)
