# =============================================================================
#  Build a slim, transferable ZIP of the whole project.
#
#  Includes : MelodyRetro/ source, HANDOFF.md, README.md / .en.md / .ja.md,
#             LICENSE, tools/, the release APK
#  Excludes : build/ .gradle/ .kotlin/ dist/ local.properties .buildtools/
#             .refs/ backup/  (all machine-specific or regenerable)
#
#  Usage (from the repo root):
#    powershell -ExecutionPolicy Bypass -File tools\pack-share.ps1
#
#  Output: share\Melody-Retro-<version>-<yyyyMMdd>.zip   (~15 MB)
# =============================================================================

$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$share   = Join-Path $root 'share'
$staging = Join-Path $share '_staging'
$version = '2.4.9'
$stamp   = Get-Date -Format 'yyyyMMdd'
$zipPath = Join-Path $share "Melody-Retro-$version-$stamp.zip"

if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
New-Item -ItemType Directory -Force -Path $staging | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $staging 'apk') | Out-Null

$skipDirs = @('build', '.gradle', '.kotlin', 'dist', 'captures')

foreach ($proj in @('MelodyRetro')) {
    $src = Join-Path $root $proj
    if (-not (Test-Path $src)) { Write-Host "skip (missing): $proj"; continue }
    Write-Host "staging $proj ..."
    $dst = Join-Path $staging $proj
    # /NFL /NDL /NJH /NJS keep the output quiet; /E copies subdirs including empty
    & robocopy $src $dst /E /NFL /NDL /NJH /NJS /XD @skipDirs /XF local.properties *.iml | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed for $proj (exit $LASTEXITCODE)" }
}

# hand-off docs + bootstrap script
# README.* and LICENSE live at the repo root too — a package without them
# looks like an orphaned source dump
foreach ($doc in @('HANDOFF.md', '.gitignore', 'LICENSE',
                   'README.md', 'README.en.md', 'README.ja.md')) {
    $p = Join-Path $root $doc
    if (Test-Path $p) { Copy-Item $p $staging -Force } else { Write-Host "WARN: missing doc (skip): $doc" }
}
New-Item -ItemType Directory -Force -Path (Join-Path $staging 'tools') | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'setup-buildtools.ps1') (Join-Path $staging 'tools')
Copy-Item (Join-Path $PSScriptRoot 'pack-share.ps1')        (Join-Path $staging 'tools')

# prebuilt APK so the other machine can install without building anything
$apks = @(
    @{ Src = 'MelodyRetro\dist\Melody-Retro-2.4.9-release.apk'; Name = 'Melody-Retro-2.4.9-release.apk' }
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
Melody Retro - hand-off package
===============================

Read HANDOFF.md first. It explains:
  - how to bootstrap the build environment (tools/setup-buildtools.ps1)
  - how to build / install
  - the code map, the external APIs and their gotchas
  - what is currently unverified

Quick start:
  powershell -ExecutionPolicy Bypass -File tools\setup-buildtools.ps1
  cd MelodyRetro
  .\gradlew.bat --no-daemon --console=plain assembleDebug

The prebuilt (debug-signed) APK is in apk/.
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
