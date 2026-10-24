<#
    Build ShowControl (plugin\) and drop it into the show server. Also refreshes SkyClone.jar from
    SkyblockClone\plugin\build\libs when that build is newer (read-only: SkyClone is never built here).

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy_plugin.ps1
        ... -SkipBuild     copy the existing ShowControl.jar without rebuilding
        ... -NoSkyClone    leave the server's SkyClone.jar alone
        ... -Force         build even while Streamlabs / Rocket League / OBS are open

    The build is `gradlew jar --no-daemon` at below-normal priority: no daemon is left behind.
    The server must be stopped (Windows locks a loaded jar).
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$NoSkyClone,
    [switch]$Force
)
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_common.ps1')

function Die([string]$m) { Write-Host "DEPLOY FAILED: $m" -ForegroundColor Red; exit 1 }

$skyJar = Join-Path $SkyCloneRoot 'plugin\build\libs\SkyClone.jar'
if (-not (Test-Path $skyJar)) { Die "ShowControl compiles against $skyJar and it does not exist (SkyClone must be built by its own session)" }

if (-not $SkipBuild) {
    Assert-NotStreaming -Force:$Force
    $java = Find-Java25
    if ($java) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java) }
    Write-Host "Building ShowControl (gradlew jar --no-daemon)..."
    $gw = Join-Path $PluginDir 'gradlew.bat'
    $p = Start-Process -FilePath $gw -ArgumentList @('jar', '--no-daemon', '-q', '--console=plain') `
        -WorkingDirectory $PluginDir -NoNewWindow -PassThru
    $null = $p.Handle   # PS 5.1: without touching Handle, ExitCode reads back as null
    try { $p.PriorityClass = 'BelowNormal' } catch { }
    $p.WaitForExit()
    $jarNow = Join-Path $PluginDir 'build\libs\ShowControl.jar'
    if ($p.ExitCode -ne 0) { Die "gradle exited $($p.ExitCode) (scroll up for the compiler error)" }
    if (-not (Test-Path $jarNow)) { Die "no build\libs\ShowControl.jar after the build" }
}

$jar = Join-Path $PluginDir 'build\libs\ShowControl.jar'
if (-not (Test-Path $jar)) { Die "no $jar - build first" }

# Sanity: the classes the server needs are inside.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
} finally { $zip.Dispose() }
foreach ($r in @('plugin.yml', 'config.yml', 'rulesets.yml', 'com/aisky/showcontrol/ShowControlPlugin.class', 'com/aisky/showcontrol/HttpApi.class')) {
    if ($names -notcontains $r) { Die "jar has no $r" }
}

if (@(Get-ShowServerProcess).Count -gt 0) { Die "the show server is running - stop it first (scripts\stop_server.ps1)" }
$plugins = Join-Path $ServerDir 'plugins'
if (-not (Test-Path $plugins)) { Die "no $plugins - run scripts\setup_server.ps1 first" }

Copy-Item $jar (Join-Path $plugins 'ShowControl.jar') -Force
if (-not $?) { Die "copy of ShowControl.jar failed" }
Write-Host ("Deployed ShowControl.jar ({0:N0} KB)" -f ((Get-Item $jar).Length / 1KB)) -ForegroundColor Green

if (-not $NoSkyClone) {
    $dst = Join-Path $plugins 'SkyClone.jar'
    $src = Get-Item $skyJar
    if ((-not (Test-Path $dst)) -or ($src.LastWriteTime -gt (Get-Item $dst).LastWriteTime)) {
        Copy-Item $src.FullName $dst -Force
        Write-Host ("Refreshed SkyClone.jar from its latest build ({0})" -f $src.LastWriteTime) -ForegroundColor Green
    } else {
        Write-Host "SkyClone.jar is already the latest build."
    }
}
exit 0
