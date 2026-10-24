<#
    Start the local dev server for SkyClone on port 25630 (RCON 25631, password skydev).

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-server.ps1
        ... -NoDeploy       start without rebuilding/redeploying the plugin
        ... -Detached       return immediately, printing PID=<n> (for harnesses that
                            need something to kill)
        ... -WaitReady      with -Detached: wait until RCON answers
        ... -MemoryGb 6     heap size (default 4)

    Ports: 25630 game, 25631 RCON. Other servers in this portfolio own 25565-25609.
#>
[CmdletBinding()]
param(
    [switch]$NoDeploy,
    [switch]$Detached,
    [switch]$WaitReady,
    [int]$ReadyTimeoutSec = 240,
    [int]$MemoryGb = 4
)

$ErrorActionPreference = 'Continue'

$GAME_PORT = 25630
$RCON_PORT = 25631

$root      = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $root 'server'
$paperJar  = Get-ChildItem $serverDir -Filter 'paper-*.jar' -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending | Select-Object -First 1

if (-not $paperJar) {
    Write-Host "No paper-*.jar in $serverDir" -ForegroundColor Red
    exit 1
}

# ---- Java 25 ------------------------------------------------------------
function Test-Java25([string]$exe) {
    if (-not $exe) { return $false }
    $out = & cmd /c "`"$exe`" -version 2>&1"
    return (($out | Out-String) -match 'version "25')
}
$java = $null
if ($env:JAVA_HOME) {
    $cand = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if ((Test-Path $cand) -and (Test-Java25 $cand)) { $java = $cand }
}
if (-not $java) {
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath -and (Test-Java25 $onPath.Source)) { $java = $onPath.Source }
}
if (-not $java) {
    foreach ($r in @("$env:ProgramFiles\Eclipse Adoptium", "$env:LOCALAPPDATA\Programs\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:USERPROFILE\.gradle\jdks")) {
        if (-not (Test-Path $r)) { continue }
        foreach ($j in (Get-ChildItem $r -Recurse -Filter java.exe -ErrorAction SilentlyContinue)) {
            if (Test-Java25 $j.FullName) { $java = $j.FullName; break }
        }
        if ($java) { break }
    }
}
if (-not $java) {
    Write-Host "Java 25 is required for Minecraft 26.1 / Paper 26.1.2 and was not found." -ForegroundColor Red
    Write-Host "Install Temurin 25 from https://adoptium.net and try again." -ForegroundColor Red
    exit 1
}

# ---- already running? ------------------------------------------------------
$busy = Get-NetTCPConnection -LocalPort $GAME_PORT -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    $owner = $busy | Select-Object -First 1 -ExpandProperty OwningProcess
    Write-Host "Port $GAME_PORT is already in use (process $owner) - the dev server is probably already running." -ForegroundColor Yellow
    Write-Host "Stop it first (type 'stop' in its window) and try again." -ForegroundColor Yellow
    exit 1
}

if (-not $NoDeploy) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'deploy.ps1')
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Deploy failed - not starting the server." -ForegroundColor Red
        exit 1
    }
}

$javaArgs = @(
    "-Xms$($MemoryGb)G"
    "-Xmx$($MemoryGb)G"
    '-XX:+UseG1GC'
    '-XX:+ParallelRefProcEnabled'
    '-XX:MaxGCPauseMillis=200'
    '-XX:+UnlockExperimentalVMOptions'
    '-XX:+DisableExplicitGC'
    '-XX:+AlwaysPreTouch'
    '--enable-native-access=ALL-UNNAMED'
    '-Dcom.mojang.eula.agree=true'
    '-jar', $paperJar.Name
    '--nogui'
)

if ($Detached) {
    $p = Start-Process -FilePath $java -ArgumentList $javaArgs `
                       -WorkingDirectory $serverDir -PassThru -WindowStyle Hidden

    if ($WaitReady) {
        # Poll RCON, never the log file.
        #
        # Waiting on a marker in latest.log is racy: on boot Paper rotates the
        # old log away and writes a new one, so for a second or two the file on
        # disk still contains the PREVIOUS run's "Done" line. A wait loop reading
        # it returns instantly and every assertion after it is measuring the
        # wrong process. RCON answering is proof this server is up, not proof
        # that some server once was.
        $rcon = Join-Path $PSScriptRoot 'rcon.ps1'
        $deadline = (Get-Date).AddSeconds($ReadyTimeoutSec)
        $ready = $false
        while ((Get-Date) -lt $deadline) {
            if ($p.HasExited) {
                Write-Host "Server exited during startup (code $($p.ExitCode)) - read server\logs\latest.log" -ForegroundColor Red
                exit 1
            }
            & powershell -NoProfile -ExecutionPolicy Bypass -File $rcon -Port $RCON_PORT -TimeoutMs 2000 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Milliseconds 1000
        }
        if (-not $ready) {
            Write-Host "Server did not answer RCON within ${ReadyTimeoutSec}s" -ForegroundColor Red
            exit 1
        }
    }

    # Emitted on stdout so a caller can capture it. The harness parses this.
    Write-Output "PID=$($p.Id)"
    exit 0
}

Write-Host ""
Write-Host "  ========================================" -ForegroundColor DarkGray
Write-Host "   SKYCLONE - dev server" -ForegroundColor White
Write-Host "   Connect to: localhost:$GAME_PORT" -ForegroundColor Gray
Write-Host "   RCON: $RCON_PORT (scripts\rcon.ps1 `"sb info`")" -ForegroundColor DarkGray
Write-Host "   Type 'stop' here to shut down cleanly." -ForegroundColor DarkGray
Write-Host "  ========================================" -ForegroundColor DarkGray
Write-Host ""

Push-Location $serverDir
& $java @javaArgs
$exit = $LASTEXITCODE
Pop-Location
exit $exit
