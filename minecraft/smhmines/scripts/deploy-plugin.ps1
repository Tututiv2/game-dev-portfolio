# Build the plugin, deploy the JAR into the dev server, and restart it.
#   .\scripts\deploy-plugin.ps1
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\paper-process.ps1"

$root      = Get-ProjectRoot
$pluginDir = Join-Path $root "plugin"
$serverDir = Get-ServerDir
$pluginsDir = Join-Path $serverDir "plugins"

# --- 1. build -------------------------------------------------------------
Write-Host "[deploy] building..." -ForegroundColor Cyan
Push-Location $pluginDir
try {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"   # gradle writes progress to stderr
    & .\gradlew.bat build -q
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($code -ne 0) { throw "gradle build failed (exit $code)" }
} finally {
    Pop-Location
}

# --- 2. locate + sanity-check the jar ------------------------------------
$jar = Get-ChildItem -Path (Join-Path $pluginDir "build\libs") -Filter "*.jar" -File |
       Where-Object { $_.Name -notmatch "sources|javadoc" } |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw "No jar produced in plugin/build/libs" }
if ($jar.Length -lt 10000) { throw "Jar suspiciously small ($($jar.Length) bytes) - refusing to deploy" }

New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
Copy-Item $jar.FullName (Join-Path $pluginsDir "SmhMines.jar") -Force
Write-Host "[deploy] deployed $($jar.Name) ($([math]::Round($jar.Length/1kb)) KB)" -ForegroundColor Green

# --- 3. restart ----------------------------------------------------------
if (Get-PaperServerProcesses) {
    Set-RestartFlag
    Write-Host "[deploy] restart flag set - the managed window will cycle the server." -ForegroundColor Cyan
} else {
    Write-Host "[deploy] server is not running. Start it with 'Dev Server.bat'." -ForegroundColor Yellow
}
