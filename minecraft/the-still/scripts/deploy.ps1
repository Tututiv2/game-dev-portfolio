<#
    Build The Still and drop it into the dev server.

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy.ps1
        ... -SkipBuild      copy the existing jar without rebuilding
        ... -Quiet          only print the summary line

    Scar tissue, inherited from Shadow Prison's deploy script and deliberately
    not repeated here: that script ran with $ErrorActionPreference = 'Stop', so a
    single stderr line from npm aborted it AFTER the jar was copied but BEFORE
    the server restarted. The symptom is "the deploy worked but my change isn't
    in game", and it costs an hour every time. This script fails on explicit
    checks only.
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
$target    = Join-Path $pluginsIn 'TheStill.jar'

# Truncation backstop only - the real validation is the class/plugin.yml check
# below. Raise this as the plugin grows so a build that collapses to near-nothing
# gets caught even if it happens to contain the main class.
$MIN_JAR_BYTES = 1KB

function Say([string]$m) { if (-not $Quiet) { Write-Host $m } }
function Die([string]$m) { Write-Host "DEPLOY FAILED: $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $pluginDir)) { Die "no plugin directory at $pluginDir" }

# ---- build -------------------------------------------------------------
if (-not $SkipBuild) {
    Say "Building..."
    Push-Location $pluginDir
    & .\gradlew.bat build -q --console=plain
    $buildExit = $LASTEXITCODE
    Pop-Location
    if ($buildExit -ne 0) { Die "gradle build exited $buildExit" }
}

# ---- locate + validate the jar ----------------------------------------
$jar = Get-ChildItem (Join-Path $pluginDir 'build\libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { Die "no jar in plugin\build\libs - did the build actually run?" }
if ($jar.Length -lt $MIN_JAR_BYTES) {
    Die ("jar is only {0} bytes (floor is {1}); refusing to deploy a stub" -f $jar.Length, $MIN_JAR_BYTES)
}

# Confirm the main class is actually in there. A jar can build green and still
# be missing what the server needs to load.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $hasMain = $zip.Entries | Where-Object { $_.FullName -eq 'com/thestill/TheStillPlugin.class' }
    $hasYml  = $zip.Entries | Where-Object { $_.FullName -eq 'plugin.yml' }
} finally { $zip.Dispose() }
if (-not $hasMain) { Die "jar has no com/thestill/TheStillPlugin.class" }
if (-not $hasYml)  { Die "jar has no plugin.yml" }

# ---- copy --------------------------------------------------------------
if (-not (Test-Path $pluginsIn)) { New-Item -ItemType Directory -Force -Path $pluginsIn | Out-Null }
Copy-Item $jar.FullName $target -Force
if (-not $?) { Die "copy to $target failed (is the server holding the file open?)" }

$kb = [math]::Round($jar.Length / 1KB, 1)
Say ("Deployed TheStill.jar ({0} KB) -> server\plugins\" -f $kb)
exit 0
