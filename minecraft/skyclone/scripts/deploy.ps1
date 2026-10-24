<#
    Build SkyClone and drop it into the dev server.

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy.ps1
        ... -SkipBuild      copy the existing jar without rebuilding
        ... -Test           run the unit tests too (gradle build instead of gradle jar)
        ... -Quiet          only print the summary line

    Scar tissue, inherited from Shadow Prison's deploy script and deliberately
    not repeated here: that script ran with $ErrorActionPreference = 'Stop', so a
    single stderr line from a tool aborted it AFTER the jar was copied but BEFORE
    the server restarted. The symptom is "the deploy worked but my change isn't
    in game", and it costs an hour every time. This script fails on explicit
    checks only.

    Default is `gradle jar`, not `gradle build`: `build` also runs every unit test
    of ~35 modules, and one flaky test elsewhere would block a double-click deploy.
    The gate runs the tests on purpose with -Test.
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$Test,
    [switch]$Quiet
)

$ErrorActionPreference = 'Continue'

$root      = Split-Path -Parent $PSScriptRoot
$pluginDir = Join-Path $root 'plugin'
$serverDir = Join-Path $root 'server'
$pluginsIn = Join-Path $serverDir 'plugins'
$target    = Join-Path $pluginsIn 'SkyClone.jar'

# Truncation backstop. api + the foundation core alone compress to ~250 KB, so a
# jar under this floor means every other core and module is missing - the build
# collapsed. Raise it as the game grows (a full build with data is several MB).
$MIN_JAR_BYTES = 300KB

# Classes that must be inside or the server cannot boot at all.
$REQUIRED = @(
    'plugin.yml',
    'com/skyclone/SkyPlugin.class',
    'com/skyclone/api/Sky.class',
    'com/skyclone/core/ModuleList.class',
    'com/skyclone/core/foundation/FoundationCore.class'
)
# Cores SkyPlugin boots by reflection: missing = a warning (the game boots without them, badly).
$CORES = @(
    'com/skyclone/core/items/ItemsCore.class',
    'com/skyclone/core/combat/CombatCore.class',
    'com/skyclone/core/world/WorldCore.class',
    'com/skyclone/core/ui/UiCore.class'
)

function Say([string]$m) { if (-not $Quiet) { Write-Host $m } }
function Warn([string]$m) { Write-Host "WARNING: $m" -ForegroundColor Yellow }
function Die([string]$m) { Write-Host "DEPLOY FAILED: $m" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $pluginDir)) { Die "no plugin directory at $pluginDir" }

# ---- build -------------------------------------------------------------
if (-not $SkipBuild) {
    $task = if ($Test) { 'build' } else { 'jar' }
    Say "Building (gradle $task)..."
    Push-Location $pluginDir
    & .\gradlew.bat $task -q --console=plain
    $buildExit = $LASTEXITCODE
    Pop-Location
    if ($buildExit -ne 0) { Die "gradle $task exited $buildExit (scroll up for the compiler/test error)" }
}

# ---- locate + validate the jar ----------------------------------------
$jar = Get-ChildItem (Join-Path $pluginDir 'build\libs') -Filter '*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notmatch '(-sources|-javadoc|-plain)\.jar$' } |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { Die "no jar in plugin\build\libs - did the build actually run?" }
if ($jar.Length -lt $MIN_JAR_BYTES) {
    Die ("jar is only {0:N0} bytes (floor is {1:N0}); refusing to deploy a stub" -f $jar.Length, $MIN_JAR_BYTES)
}

# Confirm what the server needs is actually in there. A jar can build green and
# still be missing what the server needs to load.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $names = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($e in $zip.Entries) { [void]$names.Add($e.FullName) }
    $moduleCount = @($zip.Entries | Where-Object { $_.FullName -match '^com/skyclone/mod/[a-z0-9_]+/[A-Z][A-Za-z0-9]*Module\.class$' }).Count
    $dataCount   = @($zip.Entries | Where-Object { $_.FullName -match '^data/.+\.json$' }).Count
} finally { $zip.Dispose() }
foreach ($r in $REQUIRED) { if (-not $names.Contains($r)) { Die "jar has no $r" } }
$missingCores = @($CORES | Where-Object { -not $names.Contains($_) })
foreach ($c in $missingCores) { Warn "jar has no $c - that core will be skipped at boot" }

# ---- copy --------------------------------------------------------------
if (-not (Test-Path $pluginsIn)) { New-Item -ItemType Directory -Force -Path $pluginsIn | Out-Null }
# Stale copies under another name would load as a second plugin with the same name.
Get-ChildItem $pluginsIn -Filter 'SkyClone*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne 'SkyClone.jar' } |
    ForEach-Object { Warn "removing stale $($_.Name)"; Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue }

Copy-Item $jar.FullName $target -Force -ErrorAction SilentlyContinue
if (-not $?) { Die "copy to $target failed - is the server running and holding the jar open? Stop it first." }
$copied = Get-Item $target -ErrorAction SilentlyContinue
if (-not $copied -or $copied.Length -ne $jar.Length) { Die "copy to $target did not complete (size mismatch)" }

$kb = [math]::Round($jar.Length / 1KB, 1)
Write-Host ("Deployed SkyClone.jar ({0} KB, {1} modules, {2} data files, {3}/4 cores) -> server\plugins\" -f $kb, $moduleCount, $dataCount, (4 - $missingCores.Count))
exit 0
