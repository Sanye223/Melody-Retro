# =============================================================================
#  Melody - one-click build environment bootstrap (Windows, PowerShell 5.1+)
#
#  What it does:
#    1. Downloads a JDK 17 (Temurin) into <root>/.buildtools/jdk
#    2. Downloads Android cmdline-tools into <root>/.buildtools/sdk
#    3. Installs platform-tools / platforms;android-35 / build-tools;35.0.0
#    4. Accepts the SDK licenses
#    5. Writes local.properties for both Melody and MelodyRetro
#
#  Usage (from the repository root):
#    powershell -ExecutionPolicy Bypass -File tools\setup-buildtools.ps1
#
#  Requirements: Windows 10+, ~1.5 GB free disk, internet access (or a proxy).
#  Note: keep this file ASCII-only so it works on machines with a non-UTF8
#  console code page.
# =============================================================================

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$bt   = Join-Path $root '.buildtools'
$jdk  = Join-Path $bt 'jdk'
$sdk  = Join-Path $bt 'sdk'
$tmp  = Join-Path $bt '_tmp'

Write-Host ""
Write-Host "Project root : $root"
Write-Host "Build tools  : $bt"
Write-Host ""

New-Item -ItemType Directory -Force -Path $bt, $tmp | Out-Null

function Get-File([string]$Url, [string]$OutFile) {
    Write-Host "  downloading $Url"
    # curl.exe ships with Windows 10 1803+ and follows redirects reliably
    & curl.exe -L --fail --retry 3 --retry-delay 2 -o $OutFile $Url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $Url" }
    $len = (Get-Item $OutFile).Length
    if ($len -lt 1MB) { throw "download too small ($len bytes): $Url" }
    Write-Host ("  saved {0:N1} MB" -f ($len / 1MB))
}

# Accept both layouts: <bt>\jdk\bin\java.exe and <bt>\jdk\jdk-17.x\bin\java.exe
function Resolve-JdkHome {
    if (Test-Path (Join-Path $jdk 'bin\java.exe')) { return $jdk }
    $nested = Get-ChildItem $jdk -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Select-Object -First 1
    if ($nested) { return $nested.FullName }
    return $null
}

# ---------------------------------------------------------------- 1. JDK 17 --
$javaHome = Resolve-JdkHome
if ($javaHome) {
    Write-Host "[1/5] JDK 17 already present, skipping."
} else {
    Write-Host "[1/5] Installing JDK 17 (Temurin) ..."
    $zip = Join-Path $tmp 'jdk17.zip'
    Get-File 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $zip

    $extract = Join-Path $tmp 'jdk-extract'
    if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
    Expand-Archive -Path $zip -DestinationPath $extract -Force

    # the archive contains a single jdk-17.0.x+y folder
    $inner = Get-ChildItem $extract -Directory | Select-Object -First 1
    if (-not $inner) { throw "unexpected JDK archive layout" }
    if (Test-Path $jdk) { Remove-Item -Recurse -Force $jdk }
    Move-Item $inner.FullName $jdk
    Remove-Item -Recurse -Force $extract, $zip
}

if (-not $javaHome) { $javaHome = Resolve-JdkHome }
if (-not $javaHome) { throw "JDK 17 not found after install" }

# ------------------------------------------------------- 2. Android SDK CLI --
$sdkManager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (Test-Path $sdkManager) {
    Write-Host "[2/5] cmdline-tools already present, skipping."
} else {
    Write-Host "[2/5] Installing Android cmdline-tools ..."
    $zip = Join-Path $tmp 'cmdline-tools.zip'
    Get-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $zip

    $extract = Join-Path $tmp 'cmdline-extract'
    if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
    Expand-Archive -Path $zip -DestinationPath $extract -Force

    New-Item -ItemType Directory -Force -Path (Join-Path $sdk 'cmdline-tools') | Out-Null
    $latest = Join-Path $sdk 'cmdline-tools\latest'
    if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
    Move-Item (Join-Path $extract 'cmdline-tools') $latest
    Remove-Item -Recurse -Force $extract, $zip
}

$env:JAVA_HOME    = $javaHome
$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = "$javaHome\bin;$sdk\platform-tools;$sdk\cmdline-tools\latest\bin;$env:PATH"

# ------------------------------------------------ 3. install SDK components --
Write-Host "[3/5] Installing platform-tools / android-35 / build-tools 35.0.0 ..."
& $sdkManager "--sdk_root=$sdk" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
if ($LASTEXITCODE -ne 0) { throw "sdkmanager install failed (exit $LASTEXITCODE)" }

# ------------------------------------------------------ 4. accept licenses ----
Write-Host "[4/5] Accepting SDK licenses ..."
$yes = ("y`n" * 40)
$yes | & $sdkManager "--sdk_root=$sdk" '--licenses' | Out-Null

# ------------------------------------------------- 5. write local.properties --
Write-Host "[5/5] Writing local.properties ..."
$sdkForProp = $sdk.Replace('\', '/')
foreach ($proj in @('Melody', 'MelodyRetro')) {
    $dir = Join-Path $root $proj
    if (-not (Test-Path $dir)) { continue }
    $line = "sdk.dir=$sdkForProp"
    Set-Content -Path (Join-Path $dir 'local.properties') -Value $line -Encoding ASCII
    Write-Host "  $proj/local.properties -> $line"
}

# ------------------------------------------------------------------- done ----
Write-Host ""
Write-Host "Done. Build with:"
Write-Host ""
Write-Host "  `$env:JAVA_HOME='$javaHome'"
Write-Host "  cd $root\Melody"
Write-Host "  .\gradlew.bat --no-daemon --console=plain assembleDebug"
Write-Host ""
Write-Host "The Gradle wrapper downloads Gradle 8.9 on first run (~130 MB)."
Write-Host ""
