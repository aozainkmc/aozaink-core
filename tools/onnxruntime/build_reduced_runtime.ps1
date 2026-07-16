[CmdletBinding()]
param(
    [string]$Python = "python",
    [string]$CMakePath = "cmake",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$CompatibilityJavaHome = "",
    [ValidateRange(1, 64)]
    [int]$Parallel = 4
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$OrtTag = "v1.20.0"
$OrtVersion = "1.20.0"
$EigenCommit = "e7248b26a1ed53fa030c5c459f7ea095dfd276ac"
$ScriptDirectory = Split-Path -Parent $PSCommandPath
$RepositoryRoot = (Resolve-Path (Join-Path $ScriptDirectory "..\..")).Path
$BuildRoot = Join-Path $RepositoryRoot "build\onnxruntime-reduced"
$OrtSource = Join-Path $BuildRoot "onnxruntime-$OrtTag"
$EigenSource = Join-Path $BuildRoot "eigen-$EigenCommit"
$OrtBuild = Join-Path $BuildRoot "win-x64"
$Config = Join-Path $ScriptDirectory "required_operators_and_types.config"
$SmokeSource = Join-Path $ScriptDirectory "RuntimeSmoke.java"
$Model = Join-Path $RepositoryRoot "src\main\resources\assets\aozaink_core\ocr\mix_flash_v1\unified_dynamic.onnx"
$RuntimeOutput = Join-Path $RepositoryRoot "runtime\reduced\win-x64\onnxruntime-$OrtVersion-reduced.jar"

if (-not $JavaHome) {
    throw "JAVA_HOME is required because the ONNX Runtime Java/JNI package is built and tested."
}
foreach ($required in @($Config, $SmokeSource, $Model)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file is missing: $required"
    }
}

$modelHashBefore = (Get-FileHash -LiteralPath $Model -Algorithm SHA256).Hash
New-Item -ItemType Directory -Force $BuildRoot | Out-Null

if (-not (Test-Path -LiteralPath (Join-Path $OrtSource ".git"))) {
    & git clone --branch $OrtTag --depth 1 --recurse-submodules https://github.com/microsoft/onnxruntime.git $OrtSource
    if ($LASTEXITCODE -ne 0) { throw "Failed to clone ONNX Runtime $OrtTag" }
}
$actualOrtCommit = (& git -c "safe.directory=$($OrtSource -replace '\\', '/')" -C $OrtSource describe --tags --exact-match |
    Select-Object -First 1)
if ($actualOrtCommit) { $actualOrtCommit = $actualOrtCommit.Trim() }
if ($LASTEXITCODE -ne 0 -or $actualOrtCommit -ne $OrtTag) {
    throw "Expected ONNX Runtime tag $OrtTag, found '$actualOrtCommit'."
}

if (-not (Test-Path -LiteralPath (Join-Path $EigenSource ".git"))) {
    & git clone --filter=blob:none --no-checkout https://chromium.googlesource.com/external/gitlab.com/libeigen/eigen $EigenSource
    if ($LASTEXITCODE -ne 0) { throw "Failed to clone Eigen" }
    & git -C $EigenSource checkout --detach $EigenCommit
    if ($LASTEXITCODE -ne 0) { throw "Failed to check out pinned Eigen commit" }
}
$actualEigenCommit = (& git -c "safe.directory=$($EigenSource -replace '\\', '/')" -C $EigenSource rev-parse HEAD |
    Select-Object -First 1)
if ($actualEigenCommit) { $actualEigenCommit = $actualEigenCommit.Trim() }
if ($actualEigenCommit -ne $EigenCommit) {
    throw "Expected Eigen $EigenCommit, found $actualEigenCommit."
}

# ORT 1.20 predates the MSVC 14.44 requirement for an explicit chrono include.
$mutexHeader = Join-Path $OrtSource "include\onnxruntime\core\platform\ort_mutex.h"
$mutexText = Get-Content -LiteralPath $mutexHeader -Raw
if ($mutexText -notmatch '(?m)^#include <chrono>$') {
    $patched = $mutexText -replace '(?m)(^#include <Windows\.h>\r?\n)', "`$1#include <chrono>`r`n"
    if ($patched -eq $mutexText) {
        throw "Could not apply the ORT 1.20 MSVC chrono compatibility patch."
    }
    Set-Content -LiteralPath $mutexHeader -Value $patched -NoNewline -Encoding UTF8
}

$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$previousCondaPrefix = $env:CONDA_PREFIX
$previousCl = $env:CL
try {
    $env:CONDA_PREFIX = ""
    $env:PATH = (($env:PATH -split ';' | Where-Object { $_ -notmatch '(?i)anaconda|conda' }) -join ';')
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
    $env:CL = "/D_SILENCE_CXX23_DENORM_DEPRECATION_WARNING"

    $arguments = @(
        (Join-Path $OrtSource "tools\ci_build\build.py"),
        "--build_dir", $OrtBuild,
        "--config", "MinSizeRel",
        "--update", "--build", "--build_java",
        "--parallel", "$Parallel",
        "--skip_tests", "--skip_submodule_sync",
        "--cmake_generator", "Visual Studio 17 2022",
        "--cmake_path", $CMakePath,
        "--include_ops_by_config", $Config,
        "--enable_reduced_operator_type_support",
        "--disable_ml_ops",
        "--disable_rtti",
        "--disable_types", "float8",
        "--enable_lto",
        "--enable_msvc_static_runtime",
        "--compile_no_warning_as_error",
        "--cmake_extra_defines",
        "onnxruntime_BUILD_UNIT_TESTS=OFF",
        "CMAKE_IGNORE_PREFIX_PATH=$($previousCondaPrefix -replace '\\', '/')",
        "FETCHCONTENT_SOURCE_DIR_EIGEN=$($EigenSource -replace '\\', '/')",
        "CMAKE_POLICY_VERSION_MINIMUM=3.5"
    )
    & $Python @arguments
    if ($LASTEXITCODE -ne 0) { throw "Reduced ONNX Runtime build failed." }

    $builtJar = Join-Path $OrtSource "java\build\libs\onnxruntime-$OrtVersion.jar"
    if (-not (Test-Path -LiteralPath $builtJar -PathType Leaf)) {
        throw "Built runtime JAR was not found: $builtJar"
    }

    $jarEntries = & (Join-Path $JavaHome "bin\jar.exe") tf $builtJar
    foreach ($entry in @(
        "ai/onnxruntime/native/win-x64/onnxruntime.dll",
        "ai/onnxruntime/native/win-x64/onnxruntime4j_jni.dll"
    )) {
        if ($jarEntries -notcontains $entry) { throw "Built runtime is missing $entry" }
    }
    if ($jarEntries | Where-Object { $_ -match '^ai/onnxruntime/native/(linux|osx|win-(?!x64))' }) {
        throw "Reduced runtime unexpectedly contains another platform."
    }

    $nativeInspect = Join-Path $BuildRoot "win-native-inspect"
    if (Test-Path -LiteralPath $nativeInspect) {
        Remove-Item -LiteralPath $nativeInspect -Recurse -Force
    }
    New-Item -ItemType Directory -Force $nativeInspect | Out-Null
    Push-Location $nativeInspect
    try {
        & (Join-Path $JavaHome "bin\jar.exe") xf $builtJar `
            "ai/onnxruntime/native/win-x64/onnxruntime.dll" `
            "ai/onnxruntime/native/win-x64/onnxruntime4j_jni.dll"
        if ($LASTEXITCODE -ne 0) { throw "Failed to extract Windows native libraries for inspection." }
    }
    finally {
        Pop-Location
    }

    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
        throw "vswhere.exe is required to verify the Windows runtime dependencies."
    }
    $dumpbin = (& $vswhere -latest -products * -find "VC\Tools\MSVC\**\bin\Hostx64\x64\dumpbin.exe" |
        Select-Object -First 1)
    if ($dumpbin) { $dumpbin = $dumpbin.Trim() }
    if (-not $dumpbin -or -not (Test-Path -LiteralPath $dumpbin -PathType Leaf)) {
        throw "dumpbin.exe was not found in the selected Visual Studio installation."
    }
    foreach ($nativeName in @("onnxruntime.dll", "onnxruntime4j_jni.dll")) {
        $nativePath = Join-Path $nativeInspect "ai\onnxruntime\native\win-x64\$nativeName"
        $dependencies = (& $dumpbin /DEPENDENTS $nativePath) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw "dumpbin failed for $nativeName" }
        if ($dependencies -match '(?im)^\s+(?:MSVCP|VCRUNTIME)\d[^\s]*\.dll\s*$') {
            throw "$nativeName still depends on the dynamic MSVC runtime."
        }
    }

    $smokeClasses = Join-Path $BuildRoot "smoke-classes"
    New-Item -ItemType Directory -Force $smokeClasses | Out-Null
    & (Join-Path $JavaHome "bin\javac.exe") -cp $builtJar -d $smokeClasses $SmokeSource
    if ($LASTEXITCODE -ne 0) { throw "Failed to compile the runtime smoke test." }
    & (Join-Path $JavaHome "bin\java.exe") -cp "$smokeClasses;$builtJar" RuntimeSmoke $Model
    if ($LASTEXITCODE -ne 0) { throw "Reduced runtime failed the image/trajectory smoke test." }

    if ($CompatibilityJavaHome) {
        $compatibilityJava = Join-Path $CompatibilityJavaHome "bin\java.exe"
        if (-not (Test-Path -LiteralPath $compatibilityJava -PathType Leaf)) {
            throw "Compatibility Java was not found: $compatibilityJava"
        }
        & $compatibilityJava -cp "$smokeClasses;$builtJar" RuntimeSmoke $Model
        if ($LASTEXITCODE -ne 0) {
            throw "Reduced runtime failed the compatibility Java image/trajectory smoke test."
        }
    }

    New-Item -ItemType Directory -Force (Split-Path -Parent $RuntimeOutput) | Out-Null
    Copy-Item -LiteralPath $builtJar -Destination $RuntimeOutput -Force
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    $env:CONDA_PREFIX = $previousCondaPrefix
    $env:CL = $previousCl
}

$modelHashAfter = (Get-FileHash -LiteralPath $Model -Algorithm SHA256).Hash
if ($modelHashBefore -ne $modelHashAfter) {
    throw "The model changed during runtime compilation; refusing to accept the output."
}

Get-Item -LiteralPath $RuntimeOutput | Select-Object FullName, Length, LastWriteTime
