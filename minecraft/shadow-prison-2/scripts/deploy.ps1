<#
    Build Shadow Prison 2 and drop it into the dev server.   (ported from THE STILL)

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy.ps1
        ... -SkipBuild      copy the existing jar without rebuilding
        ... -Quiet          only print the summary line

    Fails on EXPLICIT checks only (never $ErrorActionPreference='Stop'): SP1's deploy aborted on a
    stray stderr line after copying the jar but before restarting — "the deploy worked but my
    change isn't in game". A failed build NEVER copies the previous jar ("Deployed" lies, SP1).
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$Quiet
)

$ErrorActionPreference = 'Continue'

$root      = Split-Path -Parent $PSScriptRoot
$pluginDir = Join-Path $root 'plugin'
$serverDir = Join-Path $root 'server'
$pluginsIn = Join-Path $serverDir 'plugins'
$target    = Join-Path $pluginsIn 'ShadowPrison2.jar'
$MIN_JAR_BYTES = 20KB

function Say([string]$m) { if (-not $Quiet) { Write-Host $m } }
function Die([string]$m) { Write-Host "DEPLOY FAILED: $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $pluginDir)) { Die "no plugin directory at $pluginDir" }

$buildStarted = Get-Date
if (-not $SkipBuild) {
    Say "Building (with tests)..."
    Push-Location $pluginDir
    & .\gradlew.bat build -q --console=plain
    $buildExit = $LASTEXITCODE
    Pop-Location
    if ($buildExit -ne 0) { Die "gradle build exited $buildExit (tests or compile failed) - NOT deploying" }
}

$jar = Join-Path $pluginDir 'build\libs\ShadowPrison2.jar'
if (-not (Test-Path $jar)) { Die "no jar at $jar - did the build actually run?" }
$jarItem = Get-Item $jar
if (-not $SkipBuild -and $jarItem.LastWriteTime -lt $buildStarted.AddSeconds(-5)) {
    # gradle may leave an up-to-date jar untouched; that is fine only if sources did not change.
    Say "(jar up to date - no source changes since the last build)"
}
if ($jarItem.Length -lt $MIN_JAR_BYTES) {
    Die ("jar is only {0} bytes (floor {1}); refusing to deploy a stub" -f $jarItem.Length, $MIN_JAR_BYTES)
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarItem.FullName)
try {
    $hasMain = $zip.Entries | Where-Object { $_.FullName -eq 'com/shadowprison/ShadowPrison.class' }
    $hasYml  = $zip.Entries | Where-Object { $_.FullName -eq 'plugin.yml' }
} finally { $zip.Dispose() }
if (-not $hasMain) { Die "jar has no com/shadowprison/ShadowPrison.class" }
if (-not $hasYml)  { Die "jar has no plugin.yml" }

if (-not (Test-Path $pluginsIn)) { New-Item -ItemType Directory -Force -Path $pluginsIn | Out-Null }
Copy-Item $jarItem.FullName $target -Force
if (-not $?) { Die "copy to $target failed (is the server holding the file open?)" }

Say ("Deployed ShadowPrison2.jar ({0} KB) -> server\plugins\" -f [math]::Round($jarItem.Length / 1KB, 1))
exit 0
