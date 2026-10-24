<#
    Start the Shadow Prison 2 dev server on port 25610.   (ported from THE STILL)

    Usage:
        powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-server.ps1
        ... -NoDeploy       start without rebuilding/redeploying the plugin
        ... -Detached       return immediately, printing PID= (stdin stays attached to a hidden
                            console, so the server does NOT stop at stdin EOF — a background launch
                            that "crashes" three lines after Done is exactly that trap)
        ... -WaitReady      with -Detached: wait until RCON answers (never trust latest.log —
                            Paper rotates it on boot, so for a moment it holds the PREVIOUS run's Done)
#>
[CmdletBinding()]
param(
    [switch]$NoDeploy,
    [switch]$Detached,
    [switch]$WaitReady,
    [int]$ReadyTimeoutSec = 180,
    [int]$MemoryGb = 3
)

$ErrorActionPreference = 'Continue'

$root      = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $root 'server'
$paperJar  = Get-ChildItem $serverDir -Filter 'paper-*.jar' -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending | Select-Object -First 1
if (-not $paperJar) { Write-Host "No paper-*.jar in $serverDir" -ForegroundColor Red; exit 1 }

if (-not $NoDeploy) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'deploy.ps1')
    if ($LASTEXITCODE -ne 0) { Write-Host "Deploy failed - not starting the server." -ForegroundColor Red; exit 1 }
}

$javaArgs = @(
    "-Xms$($MemoryGb)G", "-Xmx$($MemoryGb)G",
    '-XX:+UseG1GC', '-XX:+ParallelRefProcEnabled', '-XX:MaxGCPauseMillis=200',
    '-XX:+UnlockExperimentalVMOptions', '-XX:+DisableExplicitGC',
    '--enable-native-access=ALL-UNNAMED',
    '-Dcom.mojang.eula.agree=true',
    '-jar', $paperJar.Name, '--nogui'
)

if ($Detached) {
    $p = Start-Process -FilePath 'java' -ArgumentList $javaArgs -WorkingDirectory $serverDir -PassThru -WindowStyle Hidden
    if ($WaitReady) {
        $rcon = Join-Path $PSScriptRoot 'rcon.ps1'
        $deadline = (Get-Date).AddSeconds($ReadyTimeoutSec)
        $ready = $false
        while ((Get-Date) -lt $deadline) {
            if ($p.HasExited) { Write-Host "Server exited during startup (code $($p.ExitCode))" -ForegroundColor Red; exit 1 }
            & powershell -NoProfile -ExecutionPolicy Bypass -File $rcon -TimeoutMs 2000 'list' 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Milliseconds 1000
        }
        if (-not $ready) { Write-Host "Server did not answer RCON within ${ReadyTimeoutSec}s" -ForegroundColor Red; exit 1 }
    }
    Write-Output "PID=$($p.Id)"
    exit 0
}

Write-Host ""
Write-Host "  ========================================" -ForegroundColor DarkGray
Write-Host "   SHADOW PRISON 2 - dev server" -ForegroundColor White
Write-Host "   localhost:25610" -ForegroundColor Gray
Write-Host "   Keep this window open while you work." -ForegroundColor DarkGray
Write-Host "  ========================================" -ForegroundColor DarkGray
Write-Host ""
Push-Location $serverDir
& java @javaArgs
$exit = $LASTEXITCODE
Pop-Location
exit $exit
