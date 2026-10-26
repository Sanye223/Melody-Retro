# =============================================================================
#  Build a slim, transferable ZIP of the whole project.
#
#  Includes : Melody/ + MelodyRetro/ source, HANDOFF.md, tools/,
#             the two release APKs
#  Excludes : build/ .gradle/ .kotlin/ dist/ local.properties .buildtools/
#             .refs/ backup/  (all machine-specific or regenerable)
#
#  Usage (from the repo root):
#    powershell -ExecutionPolicy Bypass -File tools\pack-share.ps1
#
#  Output: share\Melody-<version>-<yyyyMMdd>.zip   (~30 MB)
# =============================================================================

$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$share   = Join-Path $root 'share'
$staging = Join-Path $share '_staging'
$version = '1.6.2'
$stamp   = Get-Date -Format 'yyyyMMdd'
$zipPath = Join-Path $share "Melody-$version-$stamp.zip"

if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
New-Item -ItemType Directory -Force -Path $staging | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $staging 'apk') | Out-Null

$skipDirs = @('build', '.gradle', '.kotlin', 'dist', 'captures')

foreach ($proj in @('Melody', 'MelodyRetro')) {
    $src = Join-Path $root $proj
    if (-not (Test-Path $src)) { Write-Host "skip (missing): $proj"; continue }
    Write-Host "staging $proj ..."
    $dst = Join-Path $staging $proj
    # /NFL /NDL /NJH /NJS keep the output quiet; /E copies subdirs including empty
    & robocopy $src $dst /E /NFL /NDL /NJH /NJS /XD @skipDirs /XF local.properties *.iml | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed for $proj (exit $LASTEXITCODE)" }
}

# hand-off docs + bootstrap script
Copy-Item (Join-Path $root 'HANDOFF.md') $staging
Copy-Item (Join-Path $root '.gitignore') $staging -Force
New-Item -ItemType Directory -Force -Path (Join-Path $staging 'tools') | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'setup-buildtools.ps1') (Join-Path $staging 'tools')
Copy-Item (Join-Path $PSScriptRoot 'pack-share.ps1')        (Join-Path $staging 'tools')

# prebuilt APKs so the other machine can install without building anything
$apks = @(
    @{ Src = 'Melody\dist\Melody-1.6.2-release.apk';            Name = 'Melody-1.6.2-release.apk' },
    @{ Src = 'MelodyRetro\dist\Melody-Retro-2.4.2-release.apk'; Name = 'Melody-Retro-2.4.2-release.apk' }
)
foreach ($a in $apks) {
    $p = Join-Path $root $a.Src
    if (Test-Path $p) {
        Copy-Item $p (Join-Path $staging "apk\$($a.Name)")
    } else {
        Write-Host "WARN: APK not found (skip): $($a.Src)"
    }
}

# a tiny pointer at the top so a fresh reader finds the hand-off doc first
$readme = @'
Melody - hand-off package
=========================

Read HANDOFF.md first. It explains:
  - how to bootstrap the build environment (tools/setup-buildtools.ps1)
  - how to build / install
  - the code map, the external APIs and their gotchas
  - what is currently unverified

Quick start:
  powershell -ExecutionPolicy Bypass -File tools\setup-buildtools.ps1
  cd Melody
  .\gradlew.bat --no-daemon --console=plain assembleDebug

Prebuilt (debug-signed) APKs are in apk/.
'@
Set-Content -Path (Join-Path $staging 'README.txt') -Value $readme -Encoding ASCII

if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
Write-Host "compressing ..."
& tar.exe -a -c -f $zipPath -C $staging .
if ($LASTEXITCODE -ne 0) { throw "tar failed (exit $LASTEXITCODE)" }

Remove-Item -Recurse -Force $staging

$zip = Get-Item $zipPath
$count = (& tar.exe -t -f $zipPath | Measure-Object -Line).Lines

Write-Host ""
Write-Host ("ZIP   : {0}" -f $zip.FullName)
Write-Host ("Size  : {0:N1} MB" -f ($zip.Length / 1MB))
Write-Host ("Files : {0}" -f $count)
Write-Host ""
