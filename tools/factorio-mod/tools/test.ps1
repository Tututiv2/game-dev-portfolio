# Verification harness for Tuti's Fun Mods.
#
# Three levels, cheapest first:
#   1. solo    - load the mod alone, run the adaptation unit tests
#   2. combat  - run a real world for 9000 ticks with a throwaway test rig that
#                shoots biters and measures whether they actually harden
#   3. full    - load alongside every mod in the real mods folder, to catch
#                conflicts before the game does
#
# Nothing here touches the real mods folder: the full-stack run hardlinks the
# mod zips into a scratch directory instead.
#
# Usage:  powershell -ExecutionPolicy Bypass -File tools\test.ps1 [-Level all]

param(
  [ValidateSet('solo', 'combat', 'full', 'all')]
  [string]$Level = 'all'
)

$ErrorActionPreference = 'Stop'

$Root    = Split-Path -Parent $PSScriptRoot
$Source  = Join-Path $Root 'tutis-fun-mods'
$Biters  = Join-Path $Root 'the-director'
$Version = (Get-Content (Join-Path $Source 'info.json') -Raw | ConvertFrom-Json).version
$BitersVersion = (Get-Content (Join-Path $Biters 'info.json') -Raw | ConvertFrom-Json).version
$Exe     = 'C:\Program Files (x86)\Steam\steamapps\common\Factorio\bin\x64\factorio.exe'
$RealMods = Join-Path $env:APPDATA 'Factorio\mods'
$LogFile = Join-Path $env:APPDATA 'Factorio\factorio-current.log'
$Work    = Join-Path $env:TEMP 'tfm-test'

if (-not (Test-Path $Exe)) { throw "Factorio not found at $Exe" }

function New-ModDir {
  param([string]$Path, [string[]]$Enabled, [switch]$IncludeRealMods, [switch]$IncludeTestRig)

  if (Test-Path $Path) { Remove-Item -Recurse -Force $Path }
  New-Item -ItemType Directory -Force $Path | Out-Null

  Copy-Item -Recurse $Source (Join-Path $Path "tutis-fun-mods_$Version")
  Copy-Item -Recurse $Biters (Join-Path $Path "tutis-the-director_$BitersVersion")

  if ($IncludeTestRig) {
    $rig = Join-Path $Path 'tfm-testrig_0.1.0'
    Copy-Item -Recurse (Join-Path $Root 'tools\testrig') $rig
  }

  if ($IncludeRealMods) {
    # Hardlinks, so the real mods folder is read but never modified.
    Get-ChildItem $RealMods -Filter *.zip | ForEach-Object {
      New-Item -ItemType HardLink -Path (Join-Path $Path $_.Name) -Target $_.FullName -EA SilentlyContinue | Out-Null
    }
    $list = Get-Content (Join-Path $RealMods 'mod-list.json') -Raw | ConvertFrom-Json
    $list.mods = @($list.mods) + [PSCustomObject]@{ name = 'tutis-fun-mods'; enabled = $true }
    $list.mods = @($list.mods) + [PSCustomObject]@{ name = 'tutis-the-director'; enabled = $true }
    if ($IncludeTestRig) {
      $list.mods = @($list.mods) + [PSCustomObject]@{ name = 'tfm-testrig'; enabled = $true }
    }
    $list | ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 (Join-Path $Path 'mod-list.json')
  }
  else {
    $entries = $Enabled | ForEach-Object { "{ ""name"": ""$_"", ""enabled"": true }" }
    "{ ""mods"": [ $($entries -join ', ') ] }" | Out-File -Encoding utf8 (Join-Path $Path 'mod-list.json')
  }
}

function Invoke-Factorio {
  param([string[]]$FactorioArgs)
  $output = & $Exe @FactorioArgs 2>&1 | Out-String
  if ($output -match 'caused a non-recoverable error|Error while running') {
    Write-Host $output -ForegroundColor Red
    throw 'Factorio reported a script error'
  }
  return $output
}

function Show-LogMatches {
  param([string]$Pattern)
  $hits = Select-String -Path $LogFile -Pattern $Pattern
  foreach ($hit in $hits) {
    $text = ($hit.Line -split ': ', 2)[-1]
    $colour = if ($text -match 'FAIL') { 'Red' } else { 'Green' }
    Write-Host "  $text" -ForegroundColor $colour
  }
  return ($hits | Where-Object { $_.Line -match 'FAIL' }).Count
}

$failures = 0
New-Item -ItemType Directory -Force $Work | Out-Null

if ($Level -in @('solo', 'all')) {
  Write-Host "`n== 1. unit tests, mod alone ==" -ForegroundColor Cyan
  $dir = Join-Path $Work 'mods_solo'
  New-ModDir -Path $dir -Enabled @('base', 'tutis-fun-mods', 'tutis-the-director')
  $map = Join-Path $Work 'solo.zip'
  if (Test-Path $map) { Remove-Item -Force $map }
  Invoke-Factorio @('--create', $map, '--mod-directory', $dir) | Out-Null
  $failures += Show-LogMatches 'TFM selftest|DIR selftest'
}

if ($Level -in @('combat', 'all')) {
  Write-Host "`n== 2. end-to-end combat, real world ==" -ForegroundColor Cyan
  $dir = Join-Path $Work 'mods_combat'
  New-ModDir -Path $dir -Enabled @('base', 'tutis-fun-mods', 'tutis-the-director', 'tfm-testrig') -IncludeTestRig
  $map = Join-Path $Work 'combat.zip'
  if (Test-Path $map) { Remove-Item -Force $map }
  Invoke-Factorio @('--create', $map, '--mod-directory', $dir) | Out-Null
  $bench = Invoke-Factorio @('--benchmark', $map, '--benchmark-ticks', '9000', '--mod-directory', $dir)
  $failures += Show-LogMatches 'TFM combat'
  if ($bench -match 'avg: ([\d.]+) ms') { Write-Host "  tick cost: avg $($Matches[1]) ms" -ForegroundColor DarkGray }
}

if ($Level -in @('full', 'all')) {
  Write-Host "`n== 3. compatibility, full mod stack ==" -ForegroundColor Cyan
  $dir = Join-Path $Work 'mods_full'
  New-ModDir -Path $dir -IncludeRealMods
  $map = Join-Path $Work 'full.zip'
  if (Test-Path $map) { Remove-Item -Force $map }
  Invoke-Factorio @('--create', $map, '--mod-directory', $dir) | Out-Null
  $failures += Show-LogMatches 'TFM selftest|DIR selftest'
}

Write-Host ''
if ($failures -eq 0) {
  Write-Host 'ALL CHECKS PASSED' -ForegroundColor Green
  exit 0
}
Write-Host "$failures CHECK(S) FAILED" -ForegroundColor Red
exit 1
