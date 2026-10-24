<#
    Start the show server (Paper 26.2 + SkyClone + ShowControl) on 127.0.0.1:25640.

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start_server.ps1 -Detached -WaitReady
        ... -Detached        launch fully detached (no console of its own, survives this shell)
        ... -WaitReady       wait until RCON answers AND ShowControl's /health answers
        ... -MemoryGb 6      heap (default 6)
        ... -Force           start even while Streamlabs / Rocket League / OBS are open

    Detached means detached: the JVM is created through WMI (Win32_Process.Create), so its parent is
    the WMI host, not this shell - it never inherits this console, never floods it, and survives the
    shell closing. Its command line carries -Daisky.show=true so stop_server.ps1 / reset_episode.ps1
    can find and stop ONLY this server (SkyClone's own dev server runs on 25630 and is never touched).
    Without -Detached the server runs in this window (type 'stop' to end it).
#>
[CmdletBinding()]
param(
    [switch]$Detached,
    [switch]$WaitReady,
    [int]$ReadyTimeoutSec = 600,
    [int]$MemoryGb = 6,
    [switch]$Force
)
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot '_common.ps1')

Assert-NotStreaming -Force:$Force

$paper = Get-ChildItem $ServerDir -Filter 'paper-*.jar' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if (-not $paper) { Write-Host "No paper-*.jar in $ServerDir - run scripts\setup_server.ps1 first" -ForegroundColor Red; exit 1 }
if (-not (Test-Path (Join-Path $ServerDir 'plugins\ShowControl.jar'))) {
    Write-Host "WARNING: plugins\ShowControl.jar is missing - run scripts\deploy_plugin.ps1 (the game will boot without the feed)" -ForegroundColor Yellow
}

if (@(Get-ShowServerProcess).Count -gt 0) {
    Write-Host "The show server is already running (pid $((Get-ShowServerProcess | Select-Object -First 1).ProcessId))." -ForegroundColor Yellow
    exit 0
}
$busy = Get-NetTCPConnection -LocalPort $GamePort -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    Write-Host "Port $GamePort is in use by process $($busy[0].OwningProcess) - not starting." -ForegroundColor Red
    exit 1
}

$java = Find-Java25
if (-not $java) { Write-Host "Java 25 not found (needed for Paper 26.2)." -ForegroundColor Red; exit 1 }

$jvm = @(
    "-Xms$($MemoryGb)G", "-Xmx$($MemoryGb)G",
    '-XX:+UseG1GC', '-XX:+ParallelRefProcEnabled', '-XX:MaxGCPauseMillis=200',
    '-XX:+UnlockExperimentalVMOptions', '-XX:+DisableExplicitGC',
    '--enable-native-access=ALL-UNNAMED',
    '-Dcom.mojang.eula.agree=true',
    $ShowMarker,
    '-jar', $paper.Name, '--nogui'
)

if (-not $Detached) {
    Write-Host "Show server on 127.0.0.1:$GamePort (RCON $RconPort, ShowControl http://127.0.0.1:$HttpPort). Type 'stop' to end."
    Push-Location $ServerDir
    & $java @jvm
    $code = $LASTEXITCODE
    Pop-Location
    exit $code
}

# ---- detached launch through WMI ----------------------------------------------------------------
$cmdLine = '"' + $java + '" ' + (($jvm | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' ')
$startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
$res = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine = $cmdLine; CurrentDirectory = $ServerDir; ProcessStartupInformation = $startup }
if (-not $res -or $res.ReturnValue -ne 0) {
    Write-Host "WMI launch failed (code $($res.ReturnValue)); falling back to a hidden Start-Process." -ForegroundColor Yellow
    $p = Start-Process -FilePath $java -ArgumentList $jvm -WorkingDirectory $ServerDir -WindowStyle Hidden -PassThru
    $serverPid = $p.Id
} else {
    $serverPid = $res.ProcessId
}
Write-Output "PID=$serverPid"

if ($WaitReady) {
    # Poll RCON + ShowControl, never the log file (a fresh boot briefly shows the previous run's log).
    $deadline = (Get-Date).AddSeconds($ReadyTimeoutSec)
    $rconUp = $false
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) {
            Write-Host "Server exited during startup - read server\logs\latest.log" -ForegroundColor Red
            exit 1
        }
        if (-not $rconUp) { $rconUp = Test-ShowRcon }
        if ($rconUp) {
            try {
                $h = Invoke-RestMethod -Uri "http://127.0.0.1:$HttpPort/health" -TimeoutSec 2
                if ($h.ok) { Write-Host "Ready: RCON up, ShowControl tps=$($h.tps)" -ForegroundColor Green; exit 0 }
            } catch { }
        }
        Start-Sleep -Seconds 2
    }
    if ($rconUp) { Write-Host "RCON is up but ShowControl /health never answered - is ShowControl.jar deployed?" -ForegroundColor Yellow; exit 2 }
    Write-Host "Server did not answer RCON within ${ReadyTimeoutSec}s (first boot builds every island - that can take minutes)" -ForegroundColor Red
    exit 1
}
exit 0
